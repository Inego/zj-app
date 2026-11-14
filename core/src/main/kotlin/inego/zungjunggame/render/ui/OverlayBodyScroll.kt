package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import kotlin.math.max

/**
 * The scrollable body of a hug-height overlay card: **one** implementation of
 * the whole scroll contract, shared by every scrolling overlay.
 *
 * An overlay supplies only what is genuinely its own — its chrome height, its
 * content, and how to paint a row. Everything that used to be re-implemented per
 * screen lives here:
 *
 *  - **fit + place** — [fitAndPlace] sizes the card around sticky chrome and a
 *    body that hugs its content, then centers it and installs the viewport.
 *  - **wrap width** — [measureWidth] is the width to measure wrapped text at,
 *    with the scrollbar strip already taken out. It does not vary with whether
 *    the body is scrolling: a width that moved when the bar appeared would
 *    re-wrap the prose, change the content's height, and jump the column.
 *  - **coordinates** — [contentYFrom] and [rowIndexAt] replace the hand-written
 *    `(bottom + height) - worldY + scrollY` arithmetic.
 *  - **hit area** — [hitsContent] rejects the scrollbar strip.
 *  - **input** — [beginPointer] / [dragTo] / [endPointer] / [cancel] / [wheel]:
 *    every interaction is handed the frame's offset through [layout] and
 *    **returns** the value it produced; the owner keeps the authoritative one.
 *  - **hover** — [hoverX] / [hoverY] blank the pointer while a drag owns the
 *    gesture, so a flick does not read as a hover or a tap.
 *  - **paint** — [drawBody] clips the rows and then draws the scrollbar, so the
 *    two can be neither reordered nor forgotten.
 */
class OverlayBodyScroll {
    private val scroll = VerticalScroll()
    private val scrollbar = VerticalScrollbar()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    var left: Float = 0f
        private set
    var bottom: Float = 0f
        private set
    var width: Float = 0f
        private set
    var height: Float = 0f
        private set

    /**
     * The frame's offset, installed by [layout] from the owner and updated by an
     * interaction with the value it just resolved. It is a read view for hit
     * tests and paint — the next [layout] overwrites it — never an owner.
     */
    var scrollY: Float = 0f
        private set

    /** The frame's measured scroll range, from the last [layout]. */
    var maxScrollY: Float = 0f
        private set

    val overflowing: Boolean
        get() = maxScrollY > 0.5f

    /** True while the scrollbar thumb/track owns the gesture. */
    val isScrollbarDrag: Boolean
        get() = scrollbar.isDragging

    /** True while a content drag has passed slop. */
    val isDrag: Boolean
        get() = scroll.isDrag

    /** True while a released content drag is still coasting. */
    val isFlinging: Boolean
        get() = scroll.isFlinging

    /**
     * True while the body is still moving on its own — a fling coast or a wheel
     * ease. What a host asks before painting another frame.
     */
    val isCoasting: Boolean
        get() = scroll.isCoasting

    /** Right gutter reserved for the track when the body overflows; else 0. */
    val gutter: Float
        get() = if (overflowing) VerticalScrollbar.gutterWidth() else 0f

    /** Top of the first content row when the frame's offset is applied (y-up). */
    val contentTop: Float
        get() = bottom + height + scrollY

    /** Card geometry produced by [fitAndPlace]. */
    data class Fitted(
        val left: Float,
        val bottom: Float,
        val cardH: Float,
        val bodyViewH: Float,
        val maxScrollY: Float,
    )

    // ── Fit ─────────────────────────────────────────────────────────────────

    /**
     * Width available to content inside a [panelW]-wide card, after [insetX]
     * horizontal inset **and** the scrollbar strip.
     *
     * The strip is subtracted unconditionally, whether or not this body happens
     * to be overflowing. A width that depended on the answer it helps decide is
     * circular: measuring at the full gutter and narrowing once the content
     * overflowed re-wraps every line of prose at the moment the bar appears, so
     * the content changes height because of the change in width, and the whole
     * column jumps — the control that was just pressed included. [gutter] is the
     * price of not being able to jump, and it is the same few px either way.
     */
    fun measureWidth(panelW: Float, insetX: Float): Float =
        (panelW - insetX - VerticalScrollbar.gutterWidth()).coerceAtLeast(0f)

    /**
     * Size the card around sticky [chromeH] and a hugging body, center it in
     * [avail], and install the body viewport.
     *
     * [bodyInset] is the gap between the card's bottom edge and the body's bottom
     * edge (bottom padding, or a pinned footer's height). The body keeps that
     * inset at every offset, so scrolled to the end its content ends exactly
     * [bodyInset] above the card's bottom.
     *
     * @return the placed card; the body viewport is installed as a side effect.
     */
    fun fitAndPlace(
        avail: OverlayViewport.Rect,
        panelW: Float,
        chromeH: Float,
        bodyContentH: Float,
        bodyInset: Float,
        footerH: Float = 0f,
        scrollY: Float = 0f,
    ): Fitted {
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH, footerH)
        val (left, bottom) = OverlayViewport.placeCentered(panelW, fit.cardH, avail)
        layout(left, bottom + bodyInset, panelW, fit.bodyViewH, bodyContentH, scrollY)
        return Fitted(
            left = left,
            bottom = bottom,
            cardH = fit.cardH,
            bodyViewH = fit.bodyViewH,
            maxScrollY = fit.maxScrollY,
        )
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    /** Content width inside a [panelW] card: [insetX] inset and [gutter] removed. */
    fun contentWidth(panelW: Float, insetX: Float, minWidth: Float = 0f): Float =
        (panelW - insetX - gutter).coerceAtLeast(minWidth)

    /**
     * Install this frame's body geometry and the owner's offset. [scrollY] is
     * clamped to the measured range and becomes the frame's read value; the
     * widget stores nothing across frames.
     */
    fun layout(
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        contentHeight: Float,
        scrollY: Float,
    ) {
        this.left = left
        this.bottom = bottom
        this.width = width.coerceAtLeast(0f)
        this.height = height.coerceAtLeast(0f)
        maxScrollY = max(0f, contentHeight - this.height)
        this.scrollY = scrollY.coerceIn(0f, maxScrollY)
        if (this.height <= 1f || this.width <= 1f) {
            scrollbar.clear()
        } else {
            scrollbar.layout(
                left = this.left,
                bottom = this.bottom,
                width = this.width,
                height = this.height,
                contentHeight = contentHeight,
                scrollY = this.scrollY,
                maxScrollY = maxScrollY,
            )
        }
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        width > 1f && height > 1f &&
            worldX >= left && worldX <= left + width &&
            worldY >= bottom && worldY <= bottom + height

    /**
     * Content-space y of [worldY]: 0 at the top of the first content row, growing
     * downward in the order content is laid out.
     */
    fun contentYFrom(worldY: Float): Float = (bottom + height) - worldY + scrollY

    /** True when the point is inside the body and clear of the scrollbar strip. */
    fun hitsContent(worldX: Float, worldY: Float): Boolean =
        contains(worldX, worldY) && worldX < left + width - gutter

    /**
     * Index of the fixed-stride row under [worldY], or null when the point misses
     * the rows.
     *
     * [headerH] is content above the first row; rows repeat every [strideY], of
     * which only the first [rowH] is row (the rest is the gap between rows).
     */
    fun rowIndexAt(
        worldY: Float,
        headerH: Float,
        rowH: Float,
        strideY: Float,
        count: Int,
    ): Int? {
        if (strideY <= 0f || rowH <= 0f || count <= 0) return null
        val y = contentYFrom(worldY) - headerH
        if (y < 0f) return null
        val index = (y / strideY).toInt()
        if (index !in 0 until count) return null
        if (y - index * strideY > rowH) return null
        return index
    }

    /**
     * The offset that brings the row at [index] fully inside the body viewport,
     * moving minimally from [currentScrollY]. A no-op while the row is already
     * visible, [index] is negative, or the body has no height.
     */
    fun revealRowOffset(
        index: Int,
        headerH: Float = 0f,
        rowH: Float,
        strideY: Float,
        currentScrollY: Float = scrollY,
    ): Float {
        if (index < 0 || height <= 1f || rowH <= 0f || strideY <= 0f) return currentScrollY
        val clamped = currentScrollY.coerceIn(0f, maxScrollY)
        val itemTop = headerH + index * strideY
        val itemBottom = itemTop + rowH
        val viewBottom = clamped + height
        val next = when {
            itemBottom - itemTop >= height -> itemTop
            itemTop < clamped -> itemTop
            itemBottom > viewBottom -> itemBottom - height
            else -> clamped
        }
        return next.coerceIn(0f, maxScrollY)
    }

    // ── Input ───────────────────────────────────────────────────────────────

    /** Pointer x for hover hit-tests; NaN while a drag owns the gesture. */
    fun hoverX(worldX: Float): Float = if (isDrag) Float.NaN else worldX

    /** Pointer y for hover hit-tests; NaN while a drag owns the gesture. */
    fun hoverY(worldY: Float): Float = if (isDrag) Float.NaN else worldY

    /**
     * Mouse-wheel / trackpad. libGDX [amountY]: positive when the wheel scrolls
     * down. Arms a wheel ease; the offset moves through [advance].
     * @return the offset handed back (unchanged — the ease has not run yet)
     */
    fun wheel(amountY: Float): Float = record(scroll.wheel(amountY, scrollY, maxScrollY))

    /**
     * Claim a press in this viewport (scrollbar strip preferred; a press on the
     * track jumps the thumb and returns through [scrollY]).
     * @return true when the press is inside the body
     */
    fun beginPointer(worldX: Float, worldY: Float): Boolean {
        if (!contains(worldX, worldY)) return false
        val barOffset = scrollbar.beginDrag(worldX, worldY, scrollY, maxScrollY)
        if (barOffset != null) {
            record(barOffset)
            return true
        }
        scroll.beginDrag(worldY, scrollY)
        return true
    }

    /** @return the offset the drag resolved (or the frame's value when idle) */
    fun dragTo(worldY: Float): Float = record(
        if (scrollbar.isDragging) {
            scrollbar.dragTo(worldY, scrollY, maxScrollY)
        } else {
            scroll.dragTo(worldY, maxScrollY)
        },
    )

    /**
     * End a content/thumb drag.
     * @return true if this was a scroll (a released drag may leave inertia coasting)
     */
    fun endPointer(): Boolean {
        if (scrollbar.isDragging) {
            scrollbar.endDrag()
            scroll.cancel()
            return true
        }
        return scroll.endDrag()
    }

    /** Advance the wheel ease or the fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = record(scroll.advance(deltaSec, scrollY, maxScrollY))

    /** Drop an in-flight drag/inertia (a modal claimed the pointer, OS cancel). */
    fun cancel() {
        scroll.cancel()
        scrollbar.clearDrag()
    }

    /** Make the resolved interaction value this frame's read value. */
    private fun record(value: Float): Float {
        scrollY = value.coerceIn(0f, maxScrollY)
        return scrollY
    }

    // ── Paint ───────────────────────────────────────────────────────────────

    /**
     * Clip to the body, paint [draw], then paint the scrollbar over the gutter.
     *
     * The only public way to paint a body: the clip and the bar cannot be
     * separated, reordered, or forgotten.
     */
    fun drawBody(
        batch: SpriteBatch,
        camera: Camera,
        px: Texture,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        draw: () -> Unit,
    ) {
        withClip(batch, camera, draw)
        drawScrollbar(batch, px, pointerWorldX, pointerWorldY)
    }

    private fun withClip(batch: SpriteBatch, camera: Camera, draw: () -> Unit) {
        if (width <= 1f || height <= 1f) return
        batch.flush()
        clipBounds.set(left, bottom, width, height)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val on = ScissorStack.pushScissors(scissors)
        try {
            draw()
        } finally {
            batch.flush()
            if (on) ScissorStack.popScissors()
        }
    }

    private fun drawScrollbar(
        batch: SpriteBatch,
        px: Texture,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        val geom = scrollbar.layout(
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            contentHeight = height + maxScrollY,
            scrollY = scrollY,
            maxScrollY = maxScrollY,
        ) ?: return
        scrollbar.draw(batch, px, geom, pointerWorldX, pointerWorldY)
    }

    // ── Test hooks ──────────────────────────────────────────────────────────

    val maxScrollYForTest: Float get() = maxScrollY
    val overflowingForTest: Boolean get() = overflowing
}
