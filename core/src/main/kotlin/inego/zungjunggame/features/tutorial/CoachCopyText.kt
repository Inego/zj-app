package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.Gdx
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.reference.CatalogLinkPolicy
import inego.zungjunggame.render.richtext.RichDoc
import inego.zungjunggame.render.richtext.RichTextBlock
import inego.zungjunggame.render.richtext.TileNotation
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialTargets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The coach's title and body copy: localization, the tile substitution in a
 * `{0}` body, and the rich-text block that draws it.
 *
 * A body can be about a tile ("hold the {0}"), so the focused tile is
 * substituted in as inline tile markup before the block is parsed. The block is
 * re-parsed only when the markup actually changes, not every frame.
 */
internal class CoachCopyText(
    sharedTileAssets: (() -> TileAssets)? = null,
) : AutoCloseable {

    /** Coach / complete body: shared rich pipeline (markup, tiles, emphasis). */
    private val bodyBlock = RichTextBlock(sharedTileAssets = sharedTileAssets)

    /**
     * Scratch block for the other beats in this beat's copy band. Measuring a
     * sibling must not disturb [bodyBlock]'s layout or its link geometry, so it
     * gets its own block; it never draws.
     */
    private val bandBlock = RichTextBlock(sharedTileAssets = sharedTileAssets)

    /** Last markup fed to [bodyBlock] (avoid re-parse every frame). */
    private var lastBodyMarkup: String? = null

    /** Last band measured, so paging through a run does not re-parse every frame. */
    private var bandMarkups: List<String> = emptyList()
    private var bandWidth = -1f
    private var bandScale = -1f
    private var bandHeight = 0f

    /** Last body fitted to a fixed band, so a steady frame does not re-search. */
    private var fitMarkup: String? = null
    private var fitWidth = -1f
    private var fitBaseScale = -1f
    private var fitMaxH = -1f
    private var fitScale = 1f

    /** The localized title, or empty when this beat has none. */
    fun title(titleKey: String): String = if (titleKey.isEmpty()) "" else L10n.get(titleKey)

    /**
     * The localized body, with the focused tile substituted for `{0}`.
     *
     * The wall count beat formats the committed dice roll instead. The caller
     * supplies the roll, because it belongs to the wall diagram's clock rather
     * than to the copy.
     */
    fun body(bodyKey: String, focus: TutorialFocus?, diceFaces: Triple<Int, Int, Int>?): String =
        when {
            bodyKey.isEmpty() -> ""
            diceFaces != null -> L10n.format(bodyKey, diceFaces.first, diceFaces.second, diceFaces.third)
            else -> {
                val pattern = L10n.get(bodyKey)
                val tile = focus?.let { tileFor(it) }
                if (tile != null && pattern.contains("{0}")) {
                    L10n.format(bodyKey, TileNotation.markup(tile))
                } else {
                    pattern
                }
            }
        }

    /**
     * The tile a focus names, if any.
     *
     * A target may name its tile directly, or name a place a pending-discard
     * tile sits in, or be the river's last tile.
     */
    private fun tileFor(focus: TutorialFocus): Int? =
        TutorialTargets.parseTile(focus.targetId)
            ?: focus.highlight?.groups?.firstOrNull { it.where == HighlightWhere.PendingDiscard }?.types?.firstOrNull()
            ?: (if (focus.targetId == "river.last") {
                focus.highlight?.groups?.firstNotNullOfOrNull { it.types.firstOrNull() }
            } else {
                null
            })

    /** Parse [body] into the drawable block if it differs from the last parse. */
    fun prepare(body: String) {
        if (body == lastBodyMarkup) return
        lastBodyMarkup = body
        setBody(bodyBlock, body)
    }

    /**
     * Tallest of [markups] at [maxW] / [scale], measured through the same rich
     * pipeline as the visible body. The coach reserves this height as the copy
     * band for a run of beats, so the diagram below the copy keeps one position
     * while the learner pages through it.
     *
     * Cached: the caller may hand the same list every frame. A new beat, a
     * resize, a type-scale change, or a locale change (which changes the
     * markups themselves) re-measures.
     */
    fun measureBandH(markups: List<String>, maxW: Float, scale: Float): Float {
        if (markups.isEmpty()) return 0f
        if (markups == bandMarkups &&
            abs(maxW - bandWidth) < 0.5f &&
            abs(scale - bandScale) < 0.001f
        ) {
            return bandHeight
        }
        var tallest = 0f
        for (markup in markups) {
            setBody(bandBlock, markup)
            tallest = max(tallest, bandBlock.measure(maxW, scale))
        }
        bandMarkups = markups
        bandWidth = maxW
        bandScale = scale
        bandHeight = tallest
        return tallest
    }

    private fun setBody(block: RichTextBlock, body: String) {
        if (body.isEmpty()) {
            block.setDoc(RichDoc(emptyList()))
        } else {
            block.setMarkup(body) { doc ->
                CatalogLinkPolicy.stripNonCatalogLinks(doc) { href, reason ->
                    Gdx.app.log("tutorial", "strip href $reason=$href")
                }
            }
        }
    }

    /** Measure the laid-out body height at [scale] within [maxW]. */
    fun measureBodyH(maxW: Float, scale: Float): Float = bodyBlock.measure(maxW, scale)

    /**
     * Largest scale at or below [baseScale] at which the current body fits
     * [maxHeight] in [maxW].
     *
     * A stacked live board reserves a fixed copy band so its pane cannot move.
     * A body too long for that band shrinks for its own beat instead of
     * pushing the table down. Height falls roughly with the square of the
     * scale — a smaller face both shortens the lines and fits more characters
     * on each — so the square-root estimate lands close and one correction
     * absorbs the line-break quantisation.
     *
     * Cached for the current markup, width, base scale and height: a settled
     * beat reuses the answer instead of re-laying the body every frame.
     */
    fun fitBodyScale(maxW: Float, baseScale: Float, maxHeight: Float): Float {
        if (maxHeight <= 0f) return baseScale
        if (fitMarkup == lastBodyMarkup &&
            abs(maxW - fitWidth) < 0.5f &&
            abs(baseScale - fitBaseScale) < 0.001f &&
            abs(maxHeight - fitMaxH) < 0.5f
        ) {
            return fitScale
        }
        val natural = measureBodyH(maxW, baseScale)
        var scale = baseScale
        if (natural > maxHeight) {
            var factor = sqrt((maxHeight / natural).coerceIn(0f, 1f))
            var height = measureBodyH(maxW, baseScale * factor)
            if (height > maxHeight) {
                factor *= sqrt((maxHeight / height).coerceIn(0f, 1f))
            }
            scale = baseScale * factor
        }
        fitMarkup = lastBodyMarkup
        fitWidth = maxW
        fitBaseScale = baseScale
        fitMaxH = maxHeight
        fitScale = scale
        return scale
    }

    /** Height one body line advances in the laid-out body (tile-aware). */
    fun bodyLineAdvance(): Float = bodyBlock.lineAdvance()

    fun drawBody(
        batch: com.badlogic.gdx.graphics.g2d.SpriteBatch,
        left: Float,
        top: Float,
        maxWidth: Float,
        scale: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        bodyBlock.draw(
            batch = batch,
            left = left,
            top = top,
            maxWidth = maxWidth,
            scale = scale,
            alpha = 1f,
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
        )
    }

    /** The hyperlink under a point, for a tap on the copy. */
    fun linkAt(worldX: Float, worldY: Float): String? = bodyBlock.linkAt(worldX, worldY)

    fun reset() {
        lastBodyMarkup = null
        bodyBlock.setDoc(RichDoc(emptyList()))
        // The band measurement is a cached fact about copy that is not on
        // screen, not a live layout: keep it across a hide. A board band spans
        // the campaign, so re-parsing it on every re-presented beat would cost
        // a hundred rich-text parses per page turn. [close] drops it.
    }

    override fun close() {
        bodyBlock.dispose()
        bandBlock.dispose()
        lastBodyMarkup = null
        bandMarkups = emptyList()
        fitMarkup = null
    }
}