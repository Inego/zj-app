package inego.zungjunggame.tutorial.slide.layout

import kotlin.math.min

/**
 * Viewport classification for tutorial slide chrome and diagrams.
 *
 * Design sizes are density-independent; [px] multiplies by [uiScale] (same policy
 * as [inego.zungjunggame.render.fonts.Fonts.px]). Layouts must **fit** into [width]×[height]
 * — prefer smaller tiles/type over overflow.
 */
data class SlideViewport(
    val width: Float,
    val height: Float,
    val touch: Boolean,
    val uiScale: Float,
) {
    init {
        require(width > 0f && height > 0f) { "viewport must be positive" }
        require(uiScale > 0f) { "uiScale must be positive" }
    }

    val shortSide: Float get() = min(width, height)

    val orientation: SlideOrientation =
        if (height > width * 1.05f) SlideOrientation.Portrait
        else SlideOrientation.Landscape

    /**
     * Overall roominess from the shorter edge (in density-scaled design px).
     * Compact phones / small windows shrink chrome and labels first.
     */
    val sizeClass: SlideSizeClass
        get() {
            val shortDesign = shortSide / uiScale
            return when {
                shortDesign < 420f -> SlideSizeClass.Compact
                shortDesign < 720f -> SlideSizeClass.Regular
                else -> SlideSizeClass.Comfortable
            }
        }

    val isPortrait: Boolean get() = orientation == SlideOrientation.Portrait
    val isLandscape: Boolean get() = orientation == SlideOrientation.Landscape
    val isCompact: Boolean get() = sizeClass == SlideSizeClass.Compact

    fun px(designPx: Float): Float = designPx * uiScale
}

enum class SlideOrientation {
    Portrait,
    Landscape,
}

enum class SlideSizeClass {
    /** Phones, small windows — minimal chrome, stack labels. */
    Compact,
    /** Typical laptop / large phone landscape. */
    Regular,
    /** Desktop / tablet — room for larger tiles when they still fit. */
    Comfortable,
}
