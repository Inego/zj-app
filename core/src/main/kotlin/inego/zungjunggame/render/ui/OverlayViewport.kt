package inego.zungjunggame.render.ui

import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Shared viewport for hug-height overlay cards (game menu, language picker,
 * new-game setup, score breakdown, …).
 *
 * Cards **never** size or place themselves from the raw window: they use the
 * safe inner rect (display cutout / system bars) minus a small margin, hug
 * content up to that height, and scroll the body when it still overflows.
 */
object OverlayViewport {
    /** Design-dp inset inside [SafeArea] on every side. */
    const val MARGIN_DP = 12f

    data class Rect(
        val left: Float,
        val bottom: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = left + width
        val top: Float get() = bottom + height
    }

    /**
     * Fit a card with sticky chrome and a scrollable body into [availableH].
     *
     * [chromeH] is non-scrolling vertical chrome **inside** the card (padding,
     * title, in-card footer). [footerH] is reserved **below** the card (e.g.
     * score-breakdown Scores / Next hand). [bodyContentH] is the unconstrained
     * body height.
     */
    data class StickyFit(
        val cardH: Float,
        val bodyViewH: Float,
        val maxScrollY: Float,
        val totalH: Float,
    )

    fun available(
        screenW: Float,
        screenH: Float,
        safe: SafeArea.Insets = SafeArea.current(),
        marginPx: Float = Fonts.px(MARGIN_DP),
    ): Rect {
        val left = (safe.left + marginPx).coerceAtLeast(0f)
        val bottom = (safe.bottom + marginPx).coerceAtLeast(0f)
        val right = (screenW - safe.right - marginPx).coerceAtMost(screenW)
        val top = (screenH - safe.top - marginPx).coerceAtMost(screenH)
        return Rect(
            left = left,
            bottom = bottom,
            width = (right - left).coerceAtLeast(1f),
            height = (top - bottom).coerceAtLeast(1f),
        )
    }

    fun clampSize(desiredW: Float, desiredH: Float, avail: Rect): Pair<Float, Float> =
        desiredW.coerceIn(1f, avail.width) to desiredH.coerceIn(1f, avail.height)

    /** Center [panelW]×[panelH] in [avail], clamped so the panel stays inside. */
    fun placeCentered(panelW: Float, panelH: Float, avail: Rect): Pair<Float, Float> {
        val maxLeft = (avail.right - panelW).coerceAtLeast(avail.left)
        val maxBottom = (avail.top - panelH).coerceAtLeast(avail.bottom)
        val left = (avail.left + (avail.width - panelW) * 0.5f).coerceIn(avail.left, maxLeft)
        val bottom = (avail.bottom + (avail.height - panelH) * 0.5f).coerceIn(avail.bottom, maxBottom)
        return left to bottom
    }

    fun fitSticky(
        availableH: Float,
        chromeH: Float,
        bodyContentH: Float,
        footerH: Float = 0f,
    ): StickyFit {
        val footer = footerH.coerceAtLeast(0f)
        val chrome = chromeH.coerceAtLeast(0f)
        val body = bodyContentH.coerceAtLeast(0f)
        val room = availableH.coerceAtLeast(1f)
        val maxCardH = (room - footer).coerceAtLeast(1f)
        val desiredCardH = chrome + body
        val cardH = desiredCardH.coerceAtMost(maxCardH).coerceAtLeast(chrome.coerceAtMost(maxCardH))
        val bodyViewH = (cardH - chrome).coerceAtLeast(0f)
        val maxScrollY = (body - bodyViewH).coerceAtLeast(0f)
        return StickyFit(
            cardH = cardH,
            bodyViewH = bodyViewH,
            maxScrollY = maxScrollY,
            totalH = cardH + footer,
        )
    }

    /** Remaining width after [maxPanelW] is also limited by [avail]. */
    fun maxPanelWidth(desiredFractionW: Float, avail: Rect, screenW: Float, minGapPx: Float): Float =
        max(1f, minOf(desiredFractionW, avail.width, (screenW - minGapPx).coerceAtLeast(1f)))
}
