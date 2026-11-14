package inego.zungjunggame.chart

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The chart's visible time window as an **ease**: a `[from, to]` epoch range that
 * chases the range its owner asked for, so a zoom control or a wheel notch
 * re-scales the time axis over a few frames instead of jumping.
 *
 * The **target** is the window the owner holds - the route state, which is also
 * what gets persisted. The **displayed** window is what the geometry maps through
 * and what the paint draws. They differ only while an ease runs, which is the one
 * thing this class exists for; it is the horizontal counterpart of [ChartYAxis].
 *
 * The edges stay `Double` for the reason [ChartViewport] keeps them there: at
 * 1.7e12 a `Float` cannot name a millisecond.
 *
 * A first target snaps, because easing from an arbitrary default is not a change
 * the player made. A direct manipulation snaps too ([snapTo]): a window under a
 * finger must be where the finger put it, never trailing behind it.
 */
class ChartWindowEase {

    /** Displayed range start; the paint maps through this. */
    var fromEpochMs: Double = 0.0
        private set

    /** Displayed range end. */
    var toEpochMs: Double = 0.0
        private set

    /** The range the owner asked for; geometry never reads these. */
    var targetFromEpochMs: Double = 0.0
        private set

    var targetToEpochMs: Double = 0.0
        private set

    /** True once a real range is set, so the displayed edges may be read. */
    var hasValue: Boolean = false
        private set

    /** True while the displayed window is still chasing the target. */
    val isEasing: Boolean
        get() = hasValue && (
            abs(targetFromEpochMs - fromEpochMs) > epsilon() ||
                abs(targetToEpochMs - toEpochMs) > epsilon()
            )

    /**
     * Hand the ease the window the owner wants. The displayed window snaps when
     * there is no previous value; otherwise it eases on [advance].
     */
    fun setTarget(fromEpochMs: Double, toEpochMs: Double) {
        if (!isValid(fromEpochMs, toEpochMs)) {
            clear()
            return
        }
        targetFromEpochMs = fromEpochMs
        targetToEpochMs = toEpochMs
        if (!hasValue) {
            this.fromEpochMs = fromEpochMs
            this.toEpochMs = toEpochMs
            hasValue = true
        }
    }

    /**
     * [fromEpochMs], [toEpochMs] as both the target and the displayed window,
     * at once: a first value, or a gesture taking the window over from an ease.
     */
    fun snapTo(fromEpochMs: Double, toEpochMs: Double) {
        if (!isValid(fromEpochMs, toEpochMs)) {
            clear()
            return
        }
        targetFromEpochMs = fromEpochMs
        targetToEpochMs = toEpochMs
        this.fromEpochMs = fromEpochMs
        this.toEpochMs = toEpochMs
        hasValue = true
    }

    /** Forget the window; the next target snaps. */
    fun clear() {
        hasValue = false
    }

    /**
     * Advance the ease by [deltaSec]. Returns true when the displayed window is
     * still changing, which is what keeps the chart requesting frames.
     */
    fun advance(deltaSec: Float): Boolean {
        if (!hasValue || !isEasing) return false
        if (deltaSec <= 0f) return true
        val step = 1.0 - exp(-(deltaSec / EASE_SECONDS).toDouble())
        fromEpochMs += (targetFromEpochMs - fromEpochMs) * step
        toEpochMs += (targetToEpochMs - toEpochMs) * step
        // Land exactly when the remainder is invisible, so the frame loop can stop
        // instead of chasing a sub-pixel difference forever.
        if (!isEasing) {
            fromEpochMs = targetFromEpochMs
            toEpochMs = targetToEpochMs
            return false
        }
        return true
    }

    private fun isValid(fromEpochMs: Double, toEpochMs: Double): Boolean =
        fromEpochMs.isFinite() && toEpochMs.isFinite() && toEpochMs > fromEpochMs

    /** Remainder, as a fraction of the target span, that counts as landed. */
    private fun epsilon(): Double =
        max(MIN_EPSILON_MS, (targetToEpochMs - targetFromEpochMs) * EASE_EPSILON)

    private companion object {
        /**
         * Time constant of the exponential ease; ~63% of the way in this long.
         * Matches [ChartYAxis], so a zoom's two axes move together.
         */
        const val EASE_SECONDS = 0.12f

        /** Sub-pixel for any span the chart can show: 0.1 px of a typical plot. */
        const val EASE_EPSILON = 1e-4

        /** Floor, so a degenerate span cannot ease forever. */
        const val MIN_EPSILON_MS = 1e-3
    }
}
