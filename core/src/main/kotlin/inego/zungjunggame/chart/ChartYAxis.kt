package inego.zungjunggame.chart

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The chart's vertical scale: a rating range that eases toward the bounds of what
 * is visible now, so a resolution swap or a zoom re-scales the axis over a few
 * frames instead of jumping.
 *
 * The **target** is exactly what the renderer measured from the candles and
 * carries in the window (padded by a little air). The **displayed** range is what
 * geometry maps through. They differ only while an ease is running, which is the
 * one thing this class exists for.
 *
 * The values stay `Double`: a rating is full precision and only its *labels* are
 * rounded. A first target snaps, because easing from an arbitrary default is not
 * a change the player made; every later target eases.
 */
class ChartYAxis {

    /** Displayed lower bound; geometry maps through this. */
    var low: Double = 0.0
        private set

    /** Displayed upper bound. */
    var high: Double = 1.0
        private set

    /** The range the visible data names; labels and ticks are derived from it. */
    var targetLow: Double = 0.0
        private set

    var targetHigh: Double = 1.0
        private set

    /** True once [setTarget] has a real range and geometry may be drawn. */
    var hasTarget: Boolean = false
        private set

    private var hasValue: Boolean = false

    /** True while the displayed range is still chasing a target. */
    val isEasing: Boolean
        get() = hasTarget && hasValue && (
            abs(targetLow - low) > epsilon() || abs(targetHigh - high) > epsilon()
            )

    /**
     * Hand the axis the visible data's raw minimum and maximum. [snap] jumps
     * straight to the padded range (a first value, or a different chart).
     */
    fun setTarget(min: Double, max: Double, snap: Boolean = false) {
        if (!min.isFinite() || !max.isFinite() || max < min) {
            clear()
            return
        }
        val span = max - min
        // A single-value range (an empty interval carried flat) still needs a
        // band to draw the line in, and a real range gets a little air above and
        // below so a wick does not sit on the panel rim.
        val pad = if (span <= 0.0) max(1.0, abs(max) * 0.01) else span * PADDING_FRACTION
        targetLow = min - pad
        targetHigh = max + pad
        hasTarget = true
        if (!hasValue || snap) {
            low = targetLow
            high = targetHigh
            hasValue = true
        }
    }

    /** Forget the target (no drawable data); the next value snaps. */
    fun clear() {
        hasTarget = false
        hasValue = false
    }

    /** Jump to the current target without easing; a first paint's safety net. */
    fun snapToTarget() {
        if (!hasTarget) return
        low = targetLow
        high = targetHigh
        hasValue = true
    }

    /**
     * Advance the ease by [deltaSec]. Returns true when the displayed range is
     * still changing, which is what keeps the chart requesting frames.
     */
    fun advance(deltaSec: Float): Boolean {
        if (!hasTarget || !hasValue) return false
        if (!isEasing) {
            low = targetLow
            high = targetHigh
            return false
        }
        if (deltaSec <= 0f) return true
        val step = 1.0 - exp(-(deltaSec / EASE_SECONDS).toDouble())
        low += (targetLow - low) * step
        high += (targetHigh - high) * step
        // Land exactly when the remainder is invisible, so the frame loop can
        // stop instead of chasing a sub-pixel difference forever.
        if (!isEasing) {
            low = targetLow
            high = targetHigh
            return false
        }
        return true
    }

    /** Plot y of [rating] in a plot of [height] starting at [bottom]. */
    fun y(rating: Double, bottom: Float, height: Float): Float {
        val span = high - low
        if (span <= 0.0) return bottom + height * 0.5f
        return bottom + (((rating - low) / span) * height).toFloat()
    }

    private fun epsilon(): Double = max(1e-9, (targetHigh - targetLow) * EASE_EPSILON)

    private companion object {
        /** Time constant of the exponential ease; ~63% of the way in this long. */
        const val EASE_SECONDS = 0.12f

        /** Air above and below the visible range. */
        const val PADDING_FRACTION = 0.06

        /** Remainder, as a fraction of the target span, that counts as landed. */
        const val EASE_EPSILON = 1e-4
    }
}
