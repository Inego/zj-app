package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.PatternlessRule
import inego.zungjunggame.net.ShadowRankedStartFailure
import inego.zungjunggame.online.ShadowRankedStartState
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.InfoButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.tapSlopPx
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * The hub's **Shadow Ranked** tab: the draft config and a link to the attempts
 * panel, in one scrolling body the [OnlineHubOverlay] shell hands down.
 *
 * [ShadowRankedStartSection] owns the start rows: the one-or-two-round draft, the
 * chicken choice, and the start control, which resolves to Start, an inline
 * pending state with Cancel, or a typed failure with Retry. The attempts
 * themselves live in their own card above the hub
 * ([inego.zungjunggame.features.challenges.ShadowRankedAttemptsOverlay]); this tab
 * only offers the link that opens it.
 *
 * The panel only lays the rows out, paints them, and reports gestures; the values
 * are [ShadowRankedView] + the persisted [GameConfig] draft, both handed in each
 * frame.
 */
class ShadowRankedTabPanel : Disposable, GestureSurface<HubVerdict> {
    private fun whitePixel(): Texture = WhitePixel.get()

    private val body = OverlayBodyScroll()
    private val regions = HitRegions<HubVerdict>()

    private val startSection = ShadowRankedStartSection()

    /** The description's rimless (i): one instance, laid out each frame. */
    private val descriptionInfo = InfoButton()

    private var pressTarget: HubVerdict? = null
    private var pressBody = false
    private var pressY = Float.NaN
    private var dragged = false

    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    private fun beginBodyScroll(x: Float, y: Float): Boolean {
        return body.overflowing && body.beginPointer(x, y)
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        if (!body.contains(x, y)) return false
        val hit = regions.hitAt(x, y)
        pressTarget = hit
        pressBody = hit == null && beginBodyScroll(x, y)
        pressY = y
        dragged = false
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): HubVerdict? {
        if (abs(y - pressY) > tapSlopPx()) dragged = true
        if (pressBody) return HubVerdict.BodyScroll(body.dragTo(y))
        return null
    }

    override fun onPointerUp(x: Float, y: Float): HubVerdict {
        if (pressBody) {
            val wasDrag = body.endPointer()
            val settled = if (wasDrag) body.scrollY else null
            clearPress()
            return settled?.let(HubVerdict::BodyScroll) ?: HubVerdict.None
        }
        val resolved = pressTarget?.takeIf { !dragged }
        clearPress()
        return resolved ?: HubVerdict.None
    }

    override fun cancelGesture() {
        body.cancel()
        clearPress()
    }

    private fun clearPress() {
        pressTarget = null
        pressBody = false
        pressY = Float.NaN
        dragged = false
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        bodyRect: Rect,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        view: ShadowRankedView = ShadowRankedView(),
        draft: GameConfig? = null,
    ) {
        Fonts.ensureCreated()
        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)
        regions.clear()

        val px = whitePixel()
        val labelFont = Fonts.label
        // The start control's face: bold, like the Live primary and the shell's
        // Rejoin, so the one plate that commits to a game reads as the action.
        val itemFont = Fonts.buttonBold

        val model = bodyFor(view, draft, bodyRect.width)
        val m = model.m
        body.layout(
            bodyRect.left, bodyRect.bottom, bodyRect.width, bodyRect.height, model.rows.height, scrollY,
        )

        val placed = model.rows.place(body.contentTop)
        // One gutter, shared with Live and with the tab strip: the shell sized
        // [bodyRect] to the strip's measure, so taking it verbatim is what makes
        // every band here line up with the tabs above it.
        val contentLeft = bodyRect.left
        val rowLeft = bodyRect.left
        val rowW = bodyRect.width
        for (p in placed) registerRegions(p, contentLeft, rowLeft, rowW, m)

        val border = HubDraw.borderPx()

        body.drawBody(batch, camera, px, hoverX, hoverY) {
            for (p in placed) paintRow(
                p = p,
                batch = batch,
                px = px,
                labelFont = labelFont,
                itemFont = itemFont,
                contentLeft = contentLeft,
                rowLeft = rowLeft,
                rowW = rowW,
                textW = model.textW,
                border = border,
                hoverX = hoverX,
                hoverY = hoverY,
                m = m,
            )
        }
    }

    /** Height of the body's rows at [width]; the hub card hugs this. */
    internal fun contentHeight(width: Float, view: ShadowRankedView, draft: GameConfig?): Float =
        bodyFor(view, draft, width).rows.height

    /** Everything one frame's body layout derives, before it is painted. */
    private class ShadowBody(
        val m: ShadowMetrics,
        val rows: HubStack<ShadowRow>,
        val textW: Float,
    )

    private fun bodyFor(
        view: ShadowRankedView,
        draft: GameConfig?,
        gutterW: Float,
    ): ShadowBody {
        Fonts.ensureCreated()
        val labelFont = Fonts.label
        val itemFont = Fonts.buttonBold
        val infoSide = InfoButton.defaultSide()
        val infoGap = InfoButton.siblingGap()
        val m = ShadowMetrics(
            // Dense-list rhythm, shared with Live's game column so both hub
            // bodies pack a list of items the same way.
            tightGap = Fonts.px(3f),
            gap = Fonts.px(6f),
            sectionGap = Fonts.px(14f),
            // Same beat the Live tab uses between its text block and its
            // settings, and between its settings and its primary.
            blockGap = Fonts.px(20f),
            rowH = max(Fonts.px(40f), labelFont.lineHeight + Fonts.px(14f)),
            actionH = max(Fonts.px(48f), itemFont.lineHeight + Fonts.px(20f)),
            noteH = max(Fonts.px(28f), labelFont.lineHeight + Fonts.px(8f)),
            linkRowH = HubDraw.linkRowHeight(itemFont),
        )

        /**
         * The measure text gets, and the one the description gets.
         *
         * The choice and toggle plates span the body's whole gutter, exactly as
         * the tabs above do, and a scrolling body overlays that gutter's right
         * edge with a scrollbar strip — so prose wraps short of it, the
         * description short of the info control's column as well, and neither
         * last line runs under the bar or the glyph whatever the panel width.
         * The start plate is the exception: it is centred in the gutter at the
         * width its label asks for.
         *
         * Both are fixed for the body, not re-derived once it starts scrolling: a
         * width that moved at that moment would re-wrap the prose, change the
         * content's height, and jump the column. And a row is laid out and
         * painted at one width — measuring at one width and painting at another
         * re-breaks its lines between the passes, which leaves the bottom of the
         * row blank.
         */
        val textW = body.measureWidth(gutterW, 0f)
        val descriptionW = (textW - infoSide - infoGap).coerceAtLeast(Fonts.px(120f))

        val description = L10n.get("shadowRanked.description")
        val rows = HubStack<ShadowRow>(m.tightGap, m.gap, m.sectionGap, m.blockGap)
        // Signed out there is no body: the hub is a signed-in surface, so this
        // exists only for the cold-start window where a persisted hub is restored
        // before the session is. No copy, no control — Log in lives on the main
        // menu, and a start the player cannot make is noise.
        if (!view.signedIn) return ShadowBody(m, rows, textW)
        if (description.isNotEmpty()) {
            val descriptionH = HubDraw.measureWrapped(Fonts.small, description, descriptionW)
            rows.add(
                ShadowDescriptionRow(
                    text = description,
                    textWidth = descriptionW,
                    action = HubVerdict.ShadowRankedReference,
                    height = descriptionH,
                ),
                height = descriptionH,
                trail = HubGap.Block,
            )
        }
        startSection.add(
            rows, m, view, draft,
            { text -> HubDraw.measureWrapped(labelFont, text, textW) },
            { label -> HubDraw.buttonWidth(itemFont, label, gutterW) },
        )
        // The attempts are the account's, and the body above is the signed-in one.
        val link = L10n.get("shadowRanked.past_attempts")
        rows.add(
            ShadowLinkRow(
                link = link,
                action = HubVerdict.ViewAttempts,
                linkW = minOf(HubDraw.linkWidth(link), gutterW),
                height = m.linkRowH,
            ),
            height = m.linkRowH,
            lead = HubGap.Section,
        )
        return ShadowBody(m, rows, textW)
    }

    /**
     * Cell [i] of a [ShadowChoicesRow] of [n] options sharing the body's full
     * measure: its left edge and its width.
     *
     * [gap] is the air *between* plates, so every cell but the last gives it up
     * and the last keeps its whole width — the row then ends flush with the
     * gutter, level with the tabs above it, while the start plate below is the
     * narrower, centred control.
     *
     * Painting and hit
     * regions both come from here, so a plate and the region that answers for it
     * cannot drift apart.
     */
    private fun choiceCell(rowLeft: Float, rowW: Float, n: Int, i: Int, gap: Float): Pair<Float, Float> {
        val each = rowW / n.coerceAtLeast(1)
        val w = if (i == n - 1) each else each - gap
        return (rowLeft + each * i) to w
    }

    private fun registerRegions(p: ShadowPlaced, contentLeft: Float, rowLeft: Float, rowW: Float, m: ShadowMetrics) {
        when (val row = p.row) {
            is ShadowChoicesRow -> {
                val n = row.options.size.coerceAtLeast(1)
                row.options.forEachIndexed { i, option ->
                    val (left, w) = choiceCell(rowLeft, rowW, n, i, m.gap)
                    regions.add(
                        Rect(left, p.bottom, w, row.height),
                        option.action,
                    )
                }
            }
            is ShadowToggleRow -> regions.add(
                Rect(rowLeft, p.bottom, rowW, row.height),
                row.action,
            )
            is ShadowActionRow -> regions.add(
                Rect(rowLeft + (rowW - row.width) * 0.5f, p.bottom, row.width, row.height),
                row.action,
            )
            is ShadowLinkRow -> regions.add(
                Rect(rowLeft + rowW - row.linkW, p.bottom, row.linkW, row.height),
                row.action,
            )
            is ShadowDescriptionRow -> regions.add(
                descriptionInfoRect(row, p.top, contentLeft),
                row.action,
            )
            else -> Unit
        }
    }

    /** The description's info control rect, at the first line's top-right. */
    private fun descriptionInfoRect(row: ShadowDescriptionRow, top: Float, contentLeft: Float): Rect {
        val side = InfoButton.defaultSide()
        return Rect(
            left = contentLeft + row.textWidth + InfoButton.siblingGap(),
            bottom = top - side,
            width = side,
            height = side,
        )
    }

    @Suppress("LongParameterList")
    private fun paintRow(
        p: ShadowPlaced,
        batch: SpriteBatch,
        px: Texture,
        labelFont: BitmapFont,
        itemFont: BitmapFont,
        contentLeft: Float,
        rowLeft: Float,
        rowW: Float,
        textW: Float,
        border: Float,
        hoverX: Float,
        hoverY: Float,
        m: ShadowMetrics,
    ) {
        val row = p.row
        val top = p.top

        fun hovered(rect: Rect?): Boolean = rect != null && rect.contains(hoverX, hoverY)
        when (row) {
            is ShadowChoicesRow -> {
                val n = row.options.size.coerceAtLeast(1)
                row.options.forEachIndexed { i, option ->
                    val (left, w) = choiceCell(rowLeft, rowW, n, i, m.gap)
                    val rect = Rect(left, top - m.rowH, w, m.rowH)
                    HubDraw.drawCycleRow(
                        batch, px, labelFont,
                        rect.left, rect.bottom, rect.width, rect.height, border,
                        label = "", value = option.label,
                        hovered = option.selected || hovered(rect),
                        muted = !option.selected,
                    )
                }
            }

            is ShadowToggleRow -> HubDraw.drawCycleRow(
                batch, px, labelFont,
                rowLeft, top - m.rowH, rowW, m.rowH, border,
                label = row.label, value = row.value, hovered = hovered(null),
            )

            is ShadowActionRow -> HubDraw.drawButtonRow(
                batch, px, itemFont, row.label,
                rowLeft + (rowW - row.width) * 0.5f, top - m.actionH, row.width, m.actionH, border,
                pointerX = hoverX, pointerY = hoverY,
            )

            is ShadowDescriptionRow -> {
                HubDraw.drawWrapped(
                    batch, Fonts.small, row.text, contentLeft, top - Fonts.small.capHeight,
                    row.textWidth, AppPalette.inkMuted,
                )
                val info = descriptionInfoRect(row, top, contentLeft)
                descriptionInfo.layoutAt(info.left, info.bottom, info.width)
                descriptionInfo.render(batch, px, hoverX, hoverY)
            }

            is ShadowNoteRow -> HubDraw.drawWrapped(
                batch, labelFont, row.text, contentLeft, top - labelFont.capHeight, textW,
                noteColor(row.tone),
            )

            is ShadowLinkRow -> {
                val rect = Rect(rowLeft + rowW - row.linkW, top - row.height, row.linkW, row.height)
                HubDraw.drawLink(
                    batch = batch,
                    px = px,
                    label = row.link,
                    rect = rect,
                    hovered = hovered(rect),
                )
            }
        }
    }

    private fun noteColor(tone: ShadowTone): Color = when (tone) {
        ShadowTone.Normal -> HubDraw.bodyInk
        ShadowTone.Error -> AppPalette.danger
    }

    override fun dispose() {
    }
}

/** Numeric layout knobs for the Shadow Ranked panel; one instance per frame. */
internal class ShadowMetrics(
    val tightGap: Float,
    val gap: Float,
    val sectionGap: Float,
    /**
     * Space above a primary action and below the description — the two places
     * that begin a new kind of thing rather than continuing the current rhythm.
     * The rows between them keep the tighter [gap]: rounds and patternless are
     * one control group.
     */
    val blockGap: Float,
    val rowH: Float,
    val actionH: Float,
    val noteH: Float,
    val linkRowH: Float,
)

internal enum class ShadowTone { Normal, Error }

internal sealed interface ShadowRow {
    val height: Float
}

internal class ShadowDescriptionRow(
    val text: String,
    /** Wrap width: the body width less the info control's reserved column. */
    val textWidth: Float,
    /** The reference deep-link the info control reports. */
    val action: HubVerdict,
    override val height: Float,
) : ShadowRow
internal class ShadowChoice(val label: String, val action: HubVerdict, val selected: Boolean)
internal class ShadowChoicesRow(val options: List<ShadowChoice>, override val height: Float) : ShadowRow
internal class ShadowToggleRow(
    val label: String,
    val value: String,
    val action: HubVerdict,
    override val height: Float,
) : ShadowRow
internal class ShadowActionRow(
    val label: String,
    val action: HubVerdict,
    /** The plate's width: the label's ink plus its side air, centred in the row. */
    val width: Float,
    override val height: Float,
) : ShadowRow
internal class ShadowNoteRow(
    val text: String,
    val tone: ShadowTone,
    override val height: Float,
) : ShadowRow

/** A standalone link that opens a card of its own. */
internal class ShadowLinkRow(
    val link: String,
    val action: HubVerdict,
    val linkW: Float,
    override val height: Float,
) : ShadowRow

/** A placed Shadow Ranked row; [HubStack] supplies the top edge and the gap. */
internal typealias ShadowPlaced = HubStack.Placed<ShadowRow>

/** The Shadow Ranked start controls: the draft and the start lifecycle. */
internal class ShadowRankedStartSection {
    fun add(
        rows: HubStack<ShadowRow>,
        m: ShadowMetrics,
        view: ShadowRankedView,
        draft: GameConfig?,
        measure: (String) -> Float,
        buttonW: (String) -> Float,
    ) {
        // The start controls are the signed-in body's: `bodyFor` returns before
        // this for a signed-out one, so a draft that cannot be started is never
        // shown.
        val rounds = draft?.rounds
        // Rounds and the patternless toggle are one control group: the tight
        // rhythm continues between them, and the toggle opens the block gap to
        // whatever the player does next.
        rows.add(
            ShadowChoicesRow(
                options = listOf(
                    ShadowChoice(L10n.get("presence.rounds_one"), HubVerdict.SetRoundsOne, rounds == 1),
                    ShadowChoice(L10n.get("presence.rounds_two"), HubVerdict.SetRoundsTwo, rounds == 2),
                ),
                height = m.rowH,
            ),
            height = m.rowH,
        )
        rows.add(
            ShadowToggleRow(
                label = PatternlessRule.caption(),
                value = PatternlessRule.settingValue(draft?.allowChickenHands == true),
                action = HubVerdict.ToggleChicken,
                height = m.rowH,
            ),
            height = m.rowH,
            trail = HubGap.Block,
        )
        // Every start state resolves to one plate, centred, at the width its own
        // label asks for: a start control that spanned the gutter would read as
        // the panel's footer rather than as the one choice it is.
        fun action(label: String, verdict: HubVerdict) {
            rows.add(
                ShadowActionRow(label, verdict, buttonW(label), m.actionH),
                height = m.actionH,
            )
        }
        when (val start = view.start) {
            is ShadowRankedStartState.Starting -> {
                val pending = L10n.get("shadowRanked.pending")
                val noteH = max(m.noteH, measure(pending))
                // A note is not a control: it reports state, and the action below
                // acts on it. [HubGap.Block] is the same boundary the description
                // opens above the controls — without it the line sits flush on the
                // button plate and reads as covered by it.
                rows.add(
                    ShadowNoteRow(pending, ShadowTone.Normal, noteH),
                    height = noteH,
                    trail = HubGap.Block,
                )
                action(L10n.get("shadowRanked.cancel"), HubVerdict.CancelStart)
            }
            is ShadowRankedStartState.Failed -> {
                val copy = failureCopy(start.failure)
                val noteH = max(m.noteH, measure(copy))
                // Same boundary as the pending note above: the failure explains
                // the Retry that follows it, so the two are one thought and the
                // air belongs on that side of the pair.
                rows.add(
                    ShadowNoteRow(copy, ShadowTone.Error, noteH),
                    height = noteH,
                    trail = HubGap.Block,
                )
                action(L10n.get("shadowRanked.retry"), HubVerdict.Retry)
            }
            else -> action(L10n.get("shadowRanked.start"), HubVerdict.Start)
        }
    }

    private fun failureCopy(failure: ShadowRankedStartFailure): String = when (failure) {
        ShadowRankedStartFailure.Unauthorized -> L10n.get("shadowRanked.failure.unauthorized")
        ShadowRankedStartFailure.Unavailable -> L10n.get("shadowRanked.failure.unavailable")
        ShadowRankedStartFailure.Network -> L10n.get("shadowRanked.failure.network")
        ShadowRankedStartFailure.Timeout -> L10n.get("shadowRanked.failure.timeout")
        // No token is the same condition `shadowRanked.signedOut` names for the
        // account, so the two share one key rather than duplicating its copy.
        ShadowRankedStartFailure.NoSession -> L10n.get("shadowRanked.signedOut")
    }
}
