package inego.zungjunggame.features.ratinghistory

import com.badlogic.gdx.graphics.g2d.BitmapFont
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect

/**
 * The candle area inside the chart's plot rect: the plot less the rating gutter on
 * the right and the time-label band below it.
 *
 * **One owner.** The painter and the hit test must agree on where the candles are,
 * or a hover names a different candle than the one under the pointer. Both read
 * this function, so there is one answer.
 */
internal class RatingChartArea(
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val top: Float get() = bottom + height

    /** True when the point is over the candles rather than over a gutter. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= bottom && y <= top
}

/** The candle area of [plot] at [font]'s metrics. */
internal fun ratingChartArea(plot: Rect, font: BitmapFont): RatingChartArea {
    val inset = Fonts.px(CHART_INSET_DP)
    val timeBand = font.capHeight + Fonts.px(CHART_TIME_GAP_DP)
    val ratingGutter = Fonts.textWidth(font, RATING_LABEL_SAMPLE) + Fonts.px(CHART_LABEL_GAP_DP)
    val left = plot.left + inset
    val bottom = plot.bottom + inset + timeBand
    return RatingChartArea(
        left = left,
        bottom = bottom,
        width = (plot.width - inset * 2f - ratingGutter).coerceAtLeast(1f),
        height = (plot.top - inset - bottom).coerceAtLeast(1f),
    )
}

/** Air between the plot's edge and the chart, in design px. */
internal const val CHART_INSET_DP = 5f

/** Air between the candle area and the time labels under it, in design px. */
internal const val CHART_TIME_GAP_DP = 6f

/** Air between the plot's right edge and the rating gutter, in design px. */
internal const val CHART_LABEL_GAP_DP = 7f

/** Rating gutter sample: wide enough for a signed five-digit rating. */
internal const val RATING_LABEL_SAMPLE = "-00000"
