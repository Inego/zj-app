package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.UiPlate
import kotlin.math.max
import kotlin.math.min

/**
 * The callout bubble that explains a focused tile: box, rule and (for a live
 * table) a leader stem back onto the tile.
 *
 * Two placements share one painter. A diagram focus puts the bubble beside the
 * target, preferring right, then left, then above, then below. A live-board
 * focus hangs it from a vertical leader above the tile, flipping below when the
 * copy scrim would clip it. Both clear the primary button band.
 *
 * The painter owns the balloon's own measurement, placement and ink; the caller
 * owns the target rect, the motion channel and the alpha.
 */
internal class CoachCallout(private val glyphLayout: GlyphLayout = GlyphLayout()) {

    /**
     * Measure the box for [text] within [maxBubbleW].
     *
     * The box size always includes the inner pad. If the line would overflow,
     * wrap — never shrink the box around unwrapped glyphs (that eats the pad).
     */
    fun layout(text: String, maxBubbleW: Float, align: Int): Layout {
        val border = max(1.5f, Fonts.px(2f))
        val padX = Fonts.px(16f) + border
        val padY = Fonts.px(12f) + border
        val innerMax = (maxBubbleW - padX * 2f).coerceAtLeast(1f)
        glyphLayout.setText(Fonts.button, text, Color.WHITE, innerMax, align, true)
        val bubbleW = (glyphLayout.width + padX * 2f)
            .coerceAtLeast(Fonts.px(88f))
            .coerceAtMost(maxBubbleW)
        val innerW = (bubbleW - padX * 2f).coerceAtLeast(1f)
        glyphLayout.setText(Fonts.button, text, Color.WHITE, innerW, align, true)
        val bubbleH = glyphLayout.height + padY * 2f
        return Layout(text, bubbleW, bubbleH, padX, padY, align)
    }

    /** Width a callout may take in [availableW]: never wider than the guide rail. */
    fun maxBubbleW(availableW: Float): Float = min(availableW * 0.42f, Fonts.px(280f))

    fun paint(
        batch: SpriteBatch,
        px: Texture,
        layout: Layout,
        left: Float,
        bottom: Float,
        scale: Float,
        alpha: Float,
    ) {
        val w = layout.bubbleW * scale
        val h = layout.bubbleH * scale
        val padX = layout.padX * scale
        val padY = layout.padY * scale
        UiPlate.drawPlate(
            batch, px, left, bottom, w, h,
            Color(AppPalette.calloutFill.r, AppPalette.calloutFill.g, AppPalette.calloutFill.b, AppPalette.calloutFill.a * alpha),
            Color(AppPalette.accent.r, AppPalette.accent.g, AppPalette.accent.b, 0.90f * alpha),
            max(1.5f, Fonts.px(2f)),
        )
        val font: BitmapFont = Fonts.button
        font.color = Color(AppPalette.ink.r, AppPalette.ink.g, AppPalette.ink.b, alpha)
        font.draw(
            batch, layout.text, left + padX, bottom + h - padY,
            (w - padX * 2f).coerceAtLeast(1f), layout.align, true,
        )
        font.color = Color.WHITE
    }

    /**
     * Draw the vertical leader from the balloon down onto the top edge of a
     * live tile, and the balloon that hangs from it.
     *
     * Prefers above (stem down onto the top edge); flips below when the copy
     * scrim would clip it (Opposite river, wall marker). The floor is the button
     * band, which is the other scrim. [avoidRight] is the first pixel the bubble
     * may not cross — the copy column in a side layout — so the balloon stays on
     * the board's side of it.
     */
    fun paintLeader(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        anchor: AnchorRect,
        layout: Layout,
        scale: Float,
        alpha: Float,
        copyScrimBottom: Float,
        buttonBandTop: Float,
        avoidRight: Float = Float.POSITIVE_INFINITY,
    ) {
        val bubbleW = layout.bubbleW * scale
        val bubbleH = layout.bubbleH * scale

        val margin = Fonts.px(10f)
        val stem = Fonts.px(22f)
        val gap = Fonts.px(8f)
        val tileTop = anchor.bottom + anchor.h
        val tileBottom = anchor.bottom
        val maxTop = copyScrimBottom - margin
        val minBottom = buttonBandTop + margin
        val aboveFits = tileTop + stem + bubbleH <= maxTop + 0.5f
        val belowFits = tileBottom - stem - bubbleH >= minBottom - 0.5f
        val placeAbove = when {
            aboveFits -> true
            belowFits -> false
            else -> (maxTop - tileTop) >= (tileBottom - minBottom)
        }

        var bubbleBottom: Float
        val stemFromY: Float
        val stemToY: Float
        if (placeAbove) {
            bubbleBottom = tileTop + stem
            if (bubbleBottom + bubbleH > maxTop) {
                bubbleBottom = (maxTop - bubbleH).coerceAtLeast(tileTop + gap)
            }
            stemFromY = tileTop
            stemToY = bubbleBottom
        } else {
            bubbleBottom = tileBottom - stem - bubbleH
            if (bubbleBottom < minBottom) {
                bubbleBottom = minBottom
            }
            stemFromY = bubbleBottom + bubbleH
            stemToY = tileBottom
        }
        var bubbleLeft = anchor.cx - bubbleW * 0.5f
        val rightLimit = min(screenW, avoidRight)
        bubbleLeft = bubbleLeft.coerceIn(margin, (rightLimit - bubbleW - margin).coerceAtLeast(margin))

        val lineW = max(2f, Fonts.px(2.25f))
        val attachX = anchor.cx.coerceIn(bubbleLeft + lineW, bubbleLeft + bubbleW - lineW)
        val stemH = (stemToY - stemFromY).coerceAtLeast(0f)
        if (stemH > 0.5f) {
            batch.setColor(AppPalette.accent.r, AppPalette.accent.g, AppPalette.accent.b, 0.92f * alpha)
            batch.draw(px, attachX - lineW * 0.5f, stemFromY, lineW, stemH)
        }

        paint(batch, px, layout, bubbleLeft, bubbleBottom, scale, alpha)
    }

    /**
     * Prefer right of target, then left, then above, then below — clamped
     * on-screen, clear of the primary button strip, and left of [avoidRight].
     *
     * @return the bubble's un-scaled left and bottom; the caller scales about
     * its center so the bubble grows in place.
     */
    fun place(
        anchor: AnchorRect,
        bubbleW: Float,
        bubbleH: Float,
        screenW: Float,
        screenH: Float,
        avoidBottom: Float,
        avoidRight: Float = Float.POSITIVE_INFINITY,
    ): Pair<Float, Float> {
        val margin = Fonts.px(10f)
        val rightLimit = min(screenW, avoidRight)
        val minBottom = max(margin, avoidBottom + margin)
        val maxBottom = (screenH - bubbleH - margin).coerceAtLeast(minBottom)
        fun clampBottom(b: Float) = b.coerceIn(minBottom, maxBottom)
        fun clampLeft(l: Float) = l.coerceIn(margin, (rightLimit - bubbleW - margin).coerceAtLeast(margin))

        val midY = clampBottom(anchor.cy - bubbleH * 0.5f)

        val rightLeft = anchor.left + anchor.w + margin
        if (rightLeft + bubbleW <= rightLimit - margin) {
            return rightLeft to midY
        }
        val leftLeft = anchor.left - bubbleW - margin
        if (leftLeft >= margin) {
            return leftLeft to midY
        }
        val aboveBottom = anchor.bottom + anchor.h + margin
        if (aboveBottom + bubbleH <= screenH - margin) {
            return clampLeft(anchor.cx - bubbleW * 0.5f) to aboveBottom
        }
        val belowBottom = clampBottom(anchor.bottom - bubbleH - margin)
        return clampLeft(anchor.cx - bubbleW * 0.5f) to belowBottom
    }

    /** A measured callout box. [bubbleW] / [bubbleH] exclude no padding. */
    data class Layout(
        val text: String,
        val bubbleW: Float,
        val bubbleH: Float,
        val padX: Float,
        val padY: Float,
        val align: Int,
    )
}
