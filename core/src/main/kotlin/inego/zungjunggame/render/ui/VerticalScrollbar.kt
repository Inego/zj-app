package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Right-edge vertical scrollbar chrome for any scrollable viewport.
 *
 * Shared by [ScrollableItemList], [inego.zungjunggame.render.richtext.RichTextView],
 * and future scroll panels. Geometry and thumb/track drag live here; the owner
 * keeps the scroll offset ([VerticalScroll] or equivalent), hands it to
 * [beginDrag]/[dragTo], and keeps the value those return.
 *
 * Layout (y-up world):
 * ```
 * | content area                         | gutter |
 * |                                      | track  |
 * |                                      | thumb  |
 * ```
 * [gutterWidth] is reserved so content never paints under the track.
 */
class VerticalScrollbar {

    /** Last-frame track/thumb layout in world coordinates (y-up). */
    data class Geom(
        val trackX: Float,
        val trackBottom: Float,
        val trackW: Float,
        val trackH: Float,
        val thumbBottom: Float,
        val thumbH: Float,
        val travel: Float,
        val viewLeft: Float,
        val viewW: Float,
        val maxScrollY: Float,
    )

    private var lastGeom: Geom? = null
    private var dragging = false
    private var grabOffsetY = 0f

    /** True while the thumb is being dragged (or a track jump is held). */
    val isDragging: Boolean
        get() = dragging

    fun clearDrag() {
        dragging = false
        grabOffsetY = 0f
    }

    /** Drop drag state and forget last-frame geometry (e.g. viewport gone). */
    fun clear() {
        clearDrag()
        lastGeom = null
    }

    /**
     * Compute geometry when content overflows; store for hit-testing next input.
     * @return null when no bar should show
     */
    fun layout(
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        contentHeight: Float,
        scrollY: Float,
        maxScrollY: Float,
    ): Geom? {
        if (maxScrollY <= 0.5f || contentHeight <= 0.5f || width <= 1f || height <= 1f) {
            lastGeom = null
            return null
        }

        val barW = Fonts.px(5f)
        val inset = Fonts.px(3f)
        val vPad = Fonts.px(4f)
        val trackH = (height - vPad * 2f).coerceAtLeast(1f)
        val trackBottom = bottom + vPad
        val trackX = left + width - inset - barW

        // Thumb length ≈ visible fraction of content; never smaller than a touchable min.
        val minThumb = Fonts.px(24f).coerceAtMost(trackH)
        val thumbH = max(minThumb, trackH * (height / contentHeight)).coerceAtMost(trackH)
        val travel = (trackH - thumbH).coerceAtLeast(0f)
        val t = if (maxScrollY > 0f) (scrollY / maxScrollY).coerceIn(0f, 1f) else 0f
        // y-up: scrollY=0 → thumb at top of track.
        val thumbBottom = trackBottom + travel * (1f - t)

        val geom = Geom(
            trackX = trackX,
            trackBottom = trackBottom,
            trackW = barW,
            trackH = trackH,
            thumbBottom = thumbBottom,
            thumbH = thumbH,
            travel = travel,
            viewLeft = left,
            viewW = width,
            maxScrollY = maxScrollY,
        )
        lastGeom = geom
        return geom
    }

    /**
     * Draw track + thumb. Hover uses the expanded gutter hit strip when
     * [pointerWorldX]/[pointerWorldY] are finite.
     */
    fun draw(
        batch: SpriteBatch,
        px: Texture,
        geom: Geom,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        val hovered =
            !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                hitsStrip(geom, pointerWorldX, pointerWorldY)
        val lit = dragging || hovered

        batch.setColor(if (lit) TRACK_HOVER else TRACK)
        batch.draw(px, geom.trackX, geom.trackBottom, geom.trackW, geom.trackH)

        if (lit) {
            // Soft halo around the thumb so hover reads as a glow, not just opacity.
            val glowPad = Fonts.px(3f)
            batch.setColor(GLOW)
            batch.draw(
                px,
                geom.trackX - glowPad,
                geom.thumbBottom - glowPad,
                geom.trackW + glowPad * 2f,
                geom.thumbH + glowPad * 2f,
            )
        }

        batch.setColor(
            when {
                dragging -> THUMB_ACTIVE
                hovered -> THUMB_HOVER
                else -> THUMB
            },
        )
        batch.draw(px, geom.trackX, geom.thumbBottom, geom.trackW, geom.thumbH)
        batch.setColor(Color.WHITE)
    }

    /**
     * Start a thumb grab or track press if the press hits the bar strip.
     * A track press centers the thumb on the pointer, then continues as drag.
     *
     * @return the absolute offset the press resolved (the track jump, or
     *   [current] for a thumb grab), or null when the press missed the strip;
     *   the caller applies the value it is handed
     */
    fun beginDrag(worldX: Float, worldY: Float, current: Float, max: Float): Float? {
        val geom = lastGeom ?: return null
        if (!hitsStrip(geom, worldX, worldY)) return null

        val onThumb =
            worldY >= geom.thumbBottom && worldY <= geom.thumbBottom + geom.thumbH
        grabOffsetY = if (onThumb) {
            worldY - geom.thumbBottom
        } else {
            geom.thumbH * 0.5f
        }
        dragging = true
        if (onThumb) return current.coerceIn(0f, max.coerceAtLeast(0f))
        val targetBottom = worldY - geom.thumbH * 0.5f
        return scrollYFromThumbBottom(geom, targetBottom)
    }

    /**
     * Continue a scrollbar drag started by [beginDrag].
     * @return the absolute offset for [worldY], or [current] when no drag is active
     */
    fun dragTo(worldY: Float, current: Float, max: Float): Float {
        if (!dragging) return current
        val geom = lastGeom ?: return current
        return scrollYFromThumbBottom(geom, worldY - grabOffsetY)
            .coerceIn(0f, max.coerceAtLeast(0f))
    }

    /**
     * End a scrollbar gesture.
     * @return true if a scrollbar drag was active (caller should treat as drag, not tap)
     */
    fun endDrag(): Boolean {
        val was = dragging
        clearDrag()
        return was
    }

    /** Expanded hit strip: full gutter + a little pad (thin bar is hard to grab). */
    fun hitsStrip(geom: Geom, worldX: Float, worldY: Float): Boolean {
        val hitLeft = geom.viewLeft + geom.viewW - gutterWidth()
        val hitRight = geom.viewLeft + geom.viewW + Fonts.px(2f)
        val y0 = geom.trackBottom
        val y1 = geom.trackBottom + geom.trackH
        return worldX in hitLeft..hitRight && worldY >= y0 && worldY <= y1
    }

    companion object {
        /**
         * Right strip reserved for the scrollbar so content never sits under the track.
         * Card bodies get this for free from [OverlayBodyScroll.contentWidth]; an
         * owner laying out its own viewport subtracts it itself.
         */
        fun gutterWidth(): Float = Fonts.px(12f)

        /** Semi-transparent light track on dark panels. */
        private val TRACK = AppPalette.scrollTrack
        private val TRACK_HOVER = AppPalette.scrollTrackHover

        /** Semi-transparent thumb — readable but not loud. */
        private val THUMB = AppPalette.scrollThumb

        /** Soft sage lift on hover (matches the rest of the chrome). */
        private val THUMB_HOVER = AppPalette.scrollThumbHover

        /** Brighter ivory while dragging the thumb. */
        private val THUMB_ACTIVE = AppPalette.scrollThumbActive

        /** Outer halo drawn behind the thumb on hover/drag. */
        private val GLOW = AppPalette.tint(AppPalette.accent, 0.16f)

        /**
         * Place the thumb so its bottom is [thumbBottom] (clamped to the track)
         * and map that position to a scroll offset.
         */
        fun scrollYFromThumbBottom(geom: Geom, thumbBottom: Float): Float {
            if (geom.travel <= 0f || geom.maxScrollY <= 0f) return 0f
            val clamped =
                thumbBottom.coerceIn(geom.trackBottom, geom.trackBottom + geom.travel)
            // y-up: top of travel → scrollY 0; bottom of travel → maxScrollY.
            val t = 1f - (clamped - geom.trackBottom) / geom.travel
            return (t * geom.maxScrollY).coerceIn(0f, geom.maxScrollY)
        }
    }
}
