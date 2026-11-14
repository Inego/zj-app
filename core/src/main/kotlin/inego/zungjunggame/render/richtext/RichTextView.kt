package inego.zungjunggame.render.richtext

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.ui.VerticalScroll
import inego.zungjunggame.render.ui.VerticalScrollbar
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * Scrollable viewport that turns markup (or a [RichDoc]) into clipped, laid-out
 * rich text with inline mahjong tiles and clickable markdown hyperlinks.
 *
 * The **offset is the owner's**: [render] is handed the current value each frame
 * and every interaction method returns the value it produced, so the view keeps
 * only transient drag/inertia state and the frame's read value. It still owns
 * the layout cache, scissor clipping, edge fades, the shared [VerticalScrollbar]
 * chrome, fonts measure, and tile drawing.
 *
 * Typical use (reference article, tutorial page):
 * ```
 * view.setMarkup(source)
 * view.render(batch, camera, left, bottom, width, height, scrollY)
 * // wheel / drag → view.onScroll / onPointerDown / onPointerDrag / onPointerUp
 * // onPointerUp().linkHref → host navigates
 * ```
 */
class RichTextView(
    private val measurer: GdxTextMeasurer = GdxTextMeasurer(),
    palette: RichColorPalette = RichColorPalette.Default,
    private val richRenderer: RichTextRenderer = RichTextRenderer(measurer, palette),
) : Disposable {

    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    private var tileAssets: TileAssets? = null
    private var tileDrawer: TileDrawer? = null

    private var sourceDoc: RichDoc = RichDoc(emptyList())
    private var laidOut: LaidOutDoc? = null
    private var laidOutWidth: Float = -1f
    /** Matches [RichTextLayoutParams.wordSpaceExtra] from the last layout. */
    private var wordSpaceExtra: Float = 0f

    private val scroll = VerticalScroll()
    private val scrollbar = VerticalScrollbar()

    /** The frame's offset, installed by [render] from the owner's value. */
    private var frameScrollY = 0f

    /** The frame's measured range, from the last [render]. */
    private var frameMaxScroll = 0f

    /** Pixels scrolled down from the top of the content (0 = top). */
    val scrollY: Float
        get() = frameScrollY

    /** True while a released content drag is still coasting. */
    val isFlinging: Boolean
        get() = scroll.isFlinging

    /**
     * True while the view is still moving on its own — a fling coast or a wheel
     * ease. What a host asks before painting another frame.
     */
    val isCoasting: Boolean
        get() = scroll.isCoasting

    /**
     * Color of the top/bottom edge fade when content overflows.
     * Defaults to the standard dark panel fill so partial lines dissolve into chrome.
     * Host overlays with a different panel tint should set this to match.
     */
    var edgeFadeColor: Color = AppPalette.tint(AppPalette.panel, 0.75f)

    /** Last viewport used in [render] (for hit-testing). */
    private var viewLeft = 0f
    private var viewBottom = 0f
    private var viewW = 0f
    private var viewH = 0f

    private var pressX: Float = Float.NaN
    private var pressY: Float = Float.NaN

    /** Result of [onPointerUp]: scroll vs link tap. */
    data class PointerUpResult(
        val wasDrag: Boolean,
        /** Non-null when the user tapped a markdown hyperlink (not a scroll drag). */
        val linkHref: String? = null,
    )

    /** Replace content from markup and reset scroll to the top. */
    fun setMarkup(markup: String) {
        // Shared primary (+ lazy CJK fallback); warm this page's non-ASCII only.
        Fonts.ensureDocFonts()
        Fonts.warmDocGlyphs(markup)
        setDoc(RichMarkupParser.parse(markup))
    }

    /** Replace content from a pre-parsed document and reset scroll to the top. */
    fun setDoc(doc: RichDoc) {
        Fonts.ensureDocFonts()
        sourceDoc = doc
        laidOut = null
        laidOutWidth = -1f
        scroll.cancel()
        scrollbar.clearDrag()
        pressX = Float.NaN
        pressY = Float.NaN
    }

    /** Drop an in-progress press/drag/link without activating a hyperlink. */
    fun cancelGesture() {
        clearDrag()
        pressX = Float.NaN
        pressY = Float.NaN
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        worldX >= viewLeft && worldX <= viewLeft + viewW &&
            worldY >= viewBottom && worldY <= viewBottom + viewH

    /**
     * Mouse-wheel / trackpad scroll.
     * libGDX [amountY]: positive when the wheel scrolls down (content moves up).
     * Arms a wheel ease; the offset moves through [advance].
     * @return the offset handed back (unchanged — the ease has not run yet)
     */
    fun onScroll(amountY: Float): Float {
        frameScrollY = scroll.wheel(amountY, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /**
     * Start a potential scroll when the press is inside the viewport.
     *
     * Prefer the shared scrollbar when the press hits its (expanded) hit strip;
     * otherwise content drag-scroll via [VerticalScroll]. Link activation is
     * decided on pointer-up if the gesture was not a drag.
     *
     * @return the offset a track press jumped to, or null when it began a content
     *   drag / missed the viewport
     */
    fun onPointerDown(worldX: Float, worldY: Float): Float? {
        clearDrag()
        pressX = Float.NaN
        pressY = Float.NaN
        if (!contains(worldX, worldY)) return null
        pressX = worldX
        pressY = worldY
        val barOffset = scrollbar.beginDrag(worldX, worldY, frameScrollY, frameMaxScroll)
        if (barOffset != null) {
            frameScrollY = barOffset.coerceIn(0f, frameMaxScroll)
            return frameScrollY
        }
        scroll.beginDrag(worldY, frameScrollY)
        return null
    }

    /** @return the offset the drag resolved (or the frame's value when idle) */
    fun onPointerDrag(worldY: Float): Float {
        val next = if (scrollbar.isDragging) {
            scrollbar.dragTo(worldY, frameScrollY, frameMaxScroll)
        } else {
            scroll.dragTo(worldY, frameMaxScroll)
        }
        frameScrollY = next.coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /** Advance the wheel ease or the fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float {
        frameScrollY = scroll.advance(deltaSec, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /**
     * End gesture.
     * When [PointerUpResult.linkHref] is non-null, the user tapped a link.
     */
    fun onPointerUp(): PointerUpResult {
        if (scrollbar.isDragging) {
            scrollbar.endDrag()
            scroll.cancel()
            pressX = Float.NaN
            pressY = Float.NaN
            return PointerUpResult(wasDrag = true)
        }
        val wasDrag = scroll.endDrag()
        val x = pressX
        val y = pressY
        pressX = Float.NaN
        pressY = Float.NaN
        if (wasDrag || x.isNaN() || y.isNaN()) {
            return PointerUpResult(wasDrag = wasDrag)
        }
        val href = linkAt(x, y)
        return PointerUpResult(wasDrag = false, linkHref = href)
    }

    /**
     * Markdown link destination under the pointer, or null.
     * Safe during/after [render] once layout exists.
     */
    fun linkAt(worldX: Float, worldY: Float): String? {
        val doc = laidOut ?: return null
        if (!contains(worldX, worldY)) return null
        // Rows never use the right gutter.
        if (worldX >= viewLeft + viewW - VerticalScrollbar.gutterWidth()) return null

        val originX = viewLeft
        val originY = viewBottom + viewH - doc.height + frameScrollY
        return RichTextLinkHit.hrefAt(
            ops = doc.ops,
            contentX = worldX - originX,
            contentY = worldY - originY,
            measurer = measurer,
            wordSpaceExtra = wordSpaceExtra,
            yPad = Fonts.px(3f),
        )
    }

    /**
     * Layout (if needed) and draw content into the given viewport rectangle.
     * Clips with scissors so content never paints outside the band.
     *
     * @param pointerWorldX desktop mouse / press-hover X for scrollbar + link highlight
     * @param pointerWorldY desktop mouse / press-hover Y for scrollbar + link highlight
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        if (width <= 1f || height <= 1f) {
            scrollbar.clear()
            frameScrollY = 0f
            frameMaxScroll = 0f
            return
        }
        Fonts.ensureDocFonts()

        viewLeft = left
        viewBottom = bottom
        viewW = width
        viewH = height

        val px = whitePixel()
        val drawer = ensureTiles()
        // Keep text clear of the right-edge scrollbar track.
        val contentW = (width - VerticalScrollbar.gutterWidth()).coerceAtLeast(1f)
        val doc = ensureLaidOut(contentW)

        frameMaxScroll = max(0f, doc.height - height)
        frameScrollY = scrollY.coerceIn(0f, frameMaxScroll)

        val hoverHref =
            if (!pointerWorldX.isNaN() && !pointerWorldY.isNaN() &&
                !scroll.isDrag && !scrollbar.isDragging
            ) {
                linkAt(pointerWorldX, pointerWorldY)
            } else {
                null
            }

        batch.flush()
        clipBounds.set(left, bottom, width, height)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val scissorOn = ScissorStack.pushScissors(scissors)

        // Content top at (left, bottom + height) when scrollY = 0.
        val originX = left
        val originY = bottom + height - doc.height + frameScrollY
        richRenderer.draw(
            batch,
            doc,
            drawer,
            px,
            originX,
            originY,
            hoverLinkHref = hoverHref,
        )

        batch.flush()
        if (scissorOn) ScissorStack.popScissors()

        drawEdgeFades(batch, px, left, bottom, width, height)
        val geom = scrollbar.layout(
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            contentHeight = doc.height,
            scrollY = frameScrollY,
            maxScrollY = frameMaxScroll,
        )
        if (geom != null) {
            scrollbar.draw(batch, px, geom, pointerWorldX, pointerWorldY)
        }
    }

    private fun drawEdgeFades(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
    ) {
        if (frameMaxScroll <= 0.5f) return
        val fadeH = Fonts.px(12f)
        val fade = edgeFadeColor
        // Leave the scrollbar gutter free so the track stays crisp.
        val fadeW = (width - VerticalScrollbar.gutterWidth()).coerceAtLeast(1f)
        if (frameScrollY > 0.5f) {
            batch.setColor(fade)
            batch.draw(px, left, bottom + height - fadeH, fadeW, fadeH)
            batch.setColor(Color.WHITE)
        }
        if (frameScrollY < frameMaxScroll - 0.5f) {
            batch.setColor(fade)
            batch.draw(px, left, bottom, fadeW, fadeH)
            batch.setColor(Color.WHITE)
        }
    }

    private fun ensureLaidOut(contentWidth: Float): LaidOutDoc {
        val existing = laidOut
        if (existing != null && abs(laidOutWidth - contentWidth) < 0.5f) {
            return existing
        }
        // Roboto’s default space is a bit tight at UI body size.
        val spaceExtra = Fonts.px(1.5f)
        val params = RichTextLayoutParams(
            maxWidth = contentWidth,
            paragraphGap = Fonts.px(12f),
            headingGapAfter = Fonts.px(16f),
            listItemGap = Fonts.px(6f),
            listIndent = Fonts.px(28f),
            tileAspect = 3f / 4f,
            tileHeightFactor = 1.25f,
            tileGapFraction = 0.06f,
            wordSpaceExtra = spaceExtra,
            tableCellPadX = Fonts.px(6f),
            tableCellPadY = Fonts.px(4f),
            tableColumnGap = Fonts.px(12f),
            tableBorderWidth = Fonts.px(1f).coerceAtLeast(1f),
        )
        val laid = RichTextLayout(measurer, params).layout(sourceDoc)
        laidOut = laid
        laidOutWidth = contentWidth
        wordSpaceExtra = spaceExtra
        return laid
    }

    private fun clearDrag() {
        scroll.cancel()
        scrollbar.clearDrag()
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
        laidOut = null
        scrollbar.clear()
    }
}
