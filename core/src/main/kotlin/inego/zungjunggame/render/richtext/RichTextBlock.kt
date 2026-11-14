package inego.zungjunggame.render.richtext

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileDrawer
import kotlin.math.abs
import kotlin.math.max

/**
 * Non-scrolling rich-text host for short copy (tutorial coach / quiz body,
 * callouts, captions).
 *
 * Shares the same parse → layout → draw pipeline as [RichTextView], without
 * article chrome (scroll, scissors, scrollbar, edge fades). Callers measure
 * height for fit-first layout, then draw into a fixed band with optional
 * uniform [scale] and [alpha].
 *
 * Typical use:
 * ```
 * block.setMarkup(L10n.get(key)) // centered paragraphs by default
 * val h = block.measure(maxW, scale)
 * block.draw(batch, left, top, maxW, scale = scale, alpha = a)
 * ```
 */
class RichTextBlock(
    private val measurer: TextMeasurer = GdxTextMeasurer(),
    palette: RichColorPalette = RichColorPalette.Default,
    private val richRenderer: RichTextRenderer = RichTextRenderer(palette = palette),
    /**
     * Font role for body content — paragraphs, list items, table cells. Defaults
     * to [TextRole.Body], so every article, slide, and notice is unchanged.
     * A surface whose body text is chrome small print passes [TextRole.Small] so
     * it renders at the same size as the plain text beside it while keeping
     * markup (`**bold**`, `[hl]…[/]`) and proper wrapping.
     */
    private val bodyRole: TextRole = TextRole.Body,
) : Disposable {

    private var tileAssets: TileAssets? = null
    private var tileDrawer: TileDrawer? = null

    private var sourceDoc: RichDoc = RichDoc(emptyList())
    private var laidOut: LaidOutDoc? = null
    private var laidOutWidth: Float = -1f
    private var laidOutScale: Float = -1f
    /** Markup to warm into FreeType atlases on next measure/draw (may be null). */
    private var pendingWarmup: String? = null

    private var lastLeft = 0f
    private var lastTop = 0f
    private var wordSpaceExtra = 0f
    private var lastYPad = 0f

    /**
     * Replace content from markup with optional document rewrite.
     *
     * @param defaultAlign applied to paragraphs and headings that the parser
     *   left as left-aligned (tutorial coach defaults to center).
     * @param rewrite transformation applied to the parsed document (e.g. link sanitization).
     *
     * Does not touch FreeType until [measure] / [draw] so headless unit tests
     * can clear content via [setDoc] without a live [com.badlogic.gdx.Gdx] context.
     */
    fun setMarkup(
        markup: String,
        defaultAlign: RichAlign = RichAlign.Center,
        rewrite: (RichDoc) -> RichDoc = { it },
    ) {
        pendingWarmup = markup
        setDoc(rewrite(applyDefaultAlign(RichMarkupParser.parse(markup), defaultAlign)))
    }

    /** Replace content from a pre-parsed document (no align rewrite). */
    fun setDoc(doc: RichDoc) {
        sourceDoc = doc
        if (doc.blocks.isEmpty()) pendingWarmup = null
        invalidateLayout()
    }

    /**
     * Single centered (or [align]) paragraph from inline markup only —
     * no block structure (`#`, lists, `:::center`).
     */
    fun setInlinesMarkup(markup: String, align: RichAlign = RichAlign.Center) {
        pendingWarmup = markup
        setDoc(
            RichDoc(
                listOf(Block.Paragraph(RichMarkupParser.parseInlines(markup), align)),
            ),
        )
    }

    /** True when there is nothing to measure or draw. */
    fun isEmpty(): Boolean = sourceDoc.blocks.isEmpty()

    /**
     * Laid-out content height at [maxWidth] and uniform font [scale]
     * (fit-first shrink). Invalidates cache when width/scale change.
     */
    fun measure(maxWidth: Float, scale: Float = 1f): Float {
        if (maxWidth <= 1f || sourceDoc.blocks.isEmpty()) return 0f
        return ensureLaidOut(maxWidth, scale).height
    }

    /**
     * Height one body line advances in this block's layout, at any [scale].
     *
     * The layout reserves room for a tile on **every** line, so a plain-text
     * line advances [TILE_HEIGHT_FACTOR] × the face's line height — not the
     * face's [com.badlogic.gdx.graphics.g2d.BitmapFont.getLineHeight]. Callers
     * reserving a fixed band for N body lines must use this value; using the
     * font's line height under-reserves by the tile factor and a body that
     * fits its band then overflows it.
     *
     * Scale-independent: [Fonts.withDocFontScale] changes glyph scale, not the
     * line box the layout advances by.
     */
    fun lineAdvance(): Float {
        val lineHeight = measurer.lineHeight(bodyRole)
        return max(lineHeight, lineHeight * TILE_HEIGHT_FACTOR)
    }

    /**
     * Draw with the content **top** at [top] (BitmapFont-style: first line
     * sits just under [top]). [left] is the left edge of the max-width band.
     */
    fun draw(
        batch: SpriteBatch,
        left: Float,
        top: Float,
        maxWidth: Float,
        scale: Float = 1f,
        alpha: Float = 1f,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        if (maxWidth <= 1f || sourceDoc.blocks.isEmpty() || alpha <= 0.001f) return
        ensureFontsReady()
        val doc = ensureLaidOut(maxWidth, scale)
        if (doc.height <= 0f) return
        lastLeft = left
        lastTop = top
        val originY = top - doc.height
        val drawer = ensureTiles()
        val px = whitePixel()
        val hoverHref = if (!pointerWorldX.isNaN() && !pointerWorldY.isNaN()) {
            linkAt(pointerWorldX, pointerWorldY)
        } else {
            null
        }
        val gdx = measurer is GdxTextMeasurer
        if (gdx) {
            Fonts.withDocFontScale(scale) {
                richRenderer.draw(
                    batch = batch,
                    doc = doc,
                    drawer = drawer,
                    whitePixel = px,
                    originX = left,
                    originY = originY,
                    hoverLinkHref = hoverHref,
                    alpha = alpha,
                )
            }
        } else {
            richRenderer.draw(
                batch = batch,
                doc = doc,
                drawer = drawer,
                whitePixel = px,
                originX = left,
                originY = originY,
                hoverLinkHref = hoverHref,
                alpha = alpha,
            )
        }
    }

    /**
     * Markdown href under [worldX]/[worldY], or null.
     *
     * Production: last-frame geometry, same as Back/Next rects. [inego.zungjunggame.Main.render]
     * polls input **before** [draw]; resize / first visible frame use the
     * previous origin. After [setDoc] / [setMarkup], [laidOut] is null so
     * [linkAt] returns null until the next [draw]. Never layout on the
     * input path. Overlays must not call [bindHitOrigin].
     */
    fun linkAt(worldX: Float, worldY: Float): String? {
        val doc = laidOut ?: return null
        val originX = lastLeft
        val originY = lastTop - doc.height
        return RichTextLinkHit.hrefAt(
            ops = doc.ops,
            contentX = worldX - originX,
            contentY = worldY - originY,
            measurer = measurer,
            wordSpaceExtra = wordSpaceExtra,
            yPad = lastYPad,
        )
    }

    /**
     * Test-only: layout + origin without SpriteBatch or FreeType.
     * Production overlays never call this.
     */
    fun bindHitOrigin(left: Float, top: Float, maxWidth: Float, scale: Float = 1f) {
        lastLeft = left
        lastTop = top
        ensureLaidOut(maxWidth, scale)
    }

    private fun ensureFontsReady() {
        if (measurer !is GdxTextMeasurer) return
        Fonts.ensureDocFonts()
        pendingWarmup?.let {
            Fonts.warmDocGlyphs(it)
            pendingWarmup = null
        }
    }

    private fun ensureLaidOut(maxWidth: Float, scale: Float): LaidOutDoc {
        val existing = laidOut
        if (existing != null &&
            abs(laidOutWidth - maxWidth) < 0.5f &&
            abs(laidOutScale - scale) < 0.001f
        ) {
            return existing
        }
        val gdx = measurer is GdxTextMeasurer
        if (gdx) ensureFontsReady()
        val spaceExtra = Fonts.px(1.5f) * scale
        lastYPad = Fonts.px(3f) * scale
        fun layout(): LaidOutDoc {
            val params = RichTextLayoutParams(
                maxWidth = maxWidth,
                paragraphGap = Fonts.px(10f) * scale,
                headingGapAfter = Fonts.px(12f) * scale,
                listItemGap = Fonts.px(4f) * scale,
                listIndent = Fonts.px(24f) * scale,
                tileAspect = 3f / 4f,
                tileHeightFactor = TILE_HEIGHT_FACTOR,
                tileGapFraction = 0.06f,
                wordSpaceExtra = spaceExtra,
                bodyRole = bodyRole,
                tableCellPadX = Fonts.px(6f) * scale,
                tableCellPadY = Fonts.px(4f) * scale,
                tableColumnGap = Fonts.px(12f) * scale,
                tableBorderWidth = (Fonts.px(1f) * scale).coerceAtLeast(1f),
            )
            return RichTextLayout(measurer, params).layout(sourceDoc)
        }
        var laid = LaidOutDoc(maxWidth, 0f, emptyList())
        if (gdx) {
            Fonts.withDocFontScale(scale) {
                laid = layout()
            }
        } else {
            laid = layout()
        }
        laidOut = laid
        laidOutWidth = maxWidth
        laidOutScale = scale
        wordSpaceExtra = spaceExtra
        return laid
    }

    private fun invalidateLayout() {
        laidOut = null
        laidOutWidth = -1f
        laidOutScale = -1f
    }

    private fun whitePixel(): Texture = WhitePixel.get()

    private fun ensureTiles(): TileDrawer {
        tileDrawer?.let { return it }
        val assets = TileAssets()
        tileAssets = assets
        val drawer = TileDrawer(assets)
        tileDrawer = drawer
        return drawer
    }

    override fun dispose() {
        tileAssets?.dispose()
        tileAssets = null
        tileDrawer = null
        invalidateLayout()
        lastLeft = 0f
        lastTop = 0f
        wordSpaceExtra = 0f
        lastYPad = 0f
    }

    companion object {
        /**
         * Tile height as a multiple of the body line height. The layout applies
         * it to every line box, so this is also the per-line advance factor.
         */
        private const val TILE_HEIGHT_FACTOR = 1.25f

        /**
         * Re-apply [align] on paragraphs/headings that are still left-aligned
         * so short tutorial strings center without requiring `:::center` fences.
         * Explicit `:::center` or left lists are left alone.
         */
        fun applyDefaultAlign(doc: RichDoc, align: RichAlign): RichDoc {
            if (align == RichAlign.Left) return doc
            val blocks = doc.blocks.map { block ->
                when (block) {
                    is Block.Paragraph ->
                        if (block.align == RichAlign.Left) block.copy(align = align) else block
                    is Block.Heading ->
                        if (block.align == RichAlign.Left) block.copy(align = align) else block
                    else -> block
                }
            }
            return RichDoc(blocks)
        }
    }
}
