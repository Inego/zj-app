package inego.zungjunggame.render.ui

import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.abs
import kotlin.math.exp

/**
 * Transient vertical-scroll motion: content drag, wheel, and fling inertia.
 *
 * The offset is the **owner's**: every method is handed the current value and
 * returns the value the interaction produced, so this class carries nothing
 * across frames. What it does keep is what no snapshot can describe — the drag
 * origin (pointer y + offset), whether the gesture passed the slop, the sampled
 * velocity, whether inertia is still coasting, and where a wheel ease is heading.
 *
 * A wheel notch does not move the offset: it arms a target and returns the value
 * it was handed, and [advance] eases toward that target over the following
 * frames. The offset still belongs to the owner throughout — this only decides
 * *when* the owner's value moves, never what it is. A trackpad's stream of small
 * deltas accumulates into one target and reads as a single continuous glide; a
 * discrete notch eases over [WHEEL_EASE_SEC].
 *
 * y-up world: finger/mouse up → content moves up → the offset increases (later
 * content revealed). Wheel [amountY] > 0 means scroll down (same direction).
 */
class VerticalScroll {
    private var dragOriginY: Float = Float.NaN
    private var dragOriginOffset: Float = 0f
    private var dragLastOffset: Float = 0f
    private var dragMoved: Boolean = false

    /** Instantaneous / smoothed scroll velocity in px/s (same sign as the offset delta). */
    private var velocityY: Float = 0f
    private var flinging: Boolean = false
    private var lastSampleNanos: Long = 0L

    /** Where a wheel ease is heading; NaN when no ease is armed. */
    private var wheelTarget: Float = Float.NaN

    private val dragSlop: Float
        get() = Fonts.px(6f)

    /** True while a content drag has moved past slop (or was marked as drag). */
    val isDrag: Boolean
        get() = dragMoved

    /** True while a released fling is still coasting. */
    val isFlinging: Boolean
        get() = flinging

    /**
     * True while the offset is moving on its own, with no pointer on it.
     *
     * The wider predicate: a fling coast and a wheel ease both move the offset
     * across frames, and both must stay visible to the present decision
     * ([inego.zungjunggame.ui.GestureSurface.scrollCoasting] →
     * `ComponentHost.consumePaintRequest`) or the motion stops after one frame.
     */
    val isCoasting: Boolean
        get() = flinging || !wheelTarget.isNaN()

    /**
     * Mouse-wheel / trackpad. libGDX [amountY]: positive when the wheel scrolls
     * down (content moves up). Desktop notches are typically ±1; trackpads send
     * smaller fractional steps.
     *
     * Arms an ease toward the stepped offset and returns [current] unchanged; the
     * offset itself moves through [advance]. Successive notches accumulate into
     * the armed target rather than restarting it, so a burst reads as one glide.
     *
     * @return the offset handed back, clamped to `0..max`
     */
    fun wheel(amountY: Float, current: Float, max: Float): Float {
        if (amountY == 0f) return current
        stopInertia()
        val from = if (wheelTarget.isNaN()) current else wheelTarget
        val target = (from + amountY * Fonts.px(WHEEL_STEP_DP)).coerceIn(0f, max.coerceAtLeast(0f))
        // Already sitting where the notch asked: nothing to animate. Leaving the
        // target armed would keep the frame loop alive for no visible change.
        wheelTarget = if (abs(target - current) <= Fonts.px(WHEEL_SETTLE_DP)) Float.NaN else target
        return clamped(current, max)
    }

    /** Begin a potential content drag at [worldY] (y-up) from [current]. */
    fun beginDrag(worldY: Float, current: Float) {
        stopInertia()
        // A pointer landing outranks an armed wheel ease: drop the target rather
        // than let the next [advance] pull the content back toward it.
        wheelTarget = Float.NaN
        dragOriginY = worldY
        dragOriginOffset = current
        dragLastOffset = current
        dragMoved = false
        lastSampleNanos = System.nanoTime()
    }

    /**
     * Continue a content drag: the offset for [worldY], computed from the drag
     * origin so every move event in one frame accumulates. Clamped to `0..max`;
     * only the clamped motion feeds the fling velocity.
     */
    fun dragTo(worldY: Float, max: Float): Float {
        if (dragOriginY.isNaN()) return dragLastOffset
        if (abs(worldY - dragOriginY) >= dragSlop) dragMoved = true
        if (!dragMoved) return dragOriginOffset
        // Content sticks to finger (y-up): finger up → later content, offset up.
        val next = clamped(dragOriginOffset + (worldY - dragOriginY), max)
        sampleVelocity(next - dragLastOffset)
        dragLastOffset = next
        return next
    }

    /**
     * End the gesture.
     * @return true if this was a scroll drag (caller should not treat as tap);
     *   a released drag with enough residual speed is left flinging.
     */
    fun endDrag(): Boolean {
        val wasDrag = dragMoved
        dragOriginY = Float.NaN
        dragMoved = false
        lastSampleNanos = 0L
        if (wasDrag && abs(velocityY) >= Fonts.px(FLING_MIN_DP_PER_SEC)) {
            flinging = true
        } else {
            stopInertia()
        }
        return wasDrag
    }

    /**
     * Advance the wheel ease or the fling inertia by [deltaSec].
     *
     * @return the new offset, or [current] unchanged while idle
     */
    fun advance(deltaSec: Float, current: Float, max: Float): Float {
        if (deltaSec <= 0f) return current
        if (!wheelTarget.isNaN()) return advanceWheelEase(deltaSec, current, max)
        if (!flinging) return current
        val next = clamped(current + velocityY * deltaSec, max)
        if (!flinging) return next
        // Frame-rate independent exponential decay.
        velocityY *= exp(-FLING_FRICTION * deltaSec)
        if (abs(velocityY) < Fonts.px(FLING_STOP_DP_PER_SEC)) {
            stopInertia()
        }
        return next
    }

    /**
     * Ease [current] toward the armed wheel target, landing exactly on it.
     *
     * The step is a convex combination of the remaining distance, so it can never
     * overshoot — a stalled frame (a throttled Wasm tab, a long GC) lands on the
     * target instead of bouncing past it. [max] is re-applied because content
     * height can shrink under an armed target.
     */
    private fun advanceWheelEase(deltaSec: Float, current: Float, max: Float): Float {
        val target = wheelTarget.coerceIn(0f, max.coerceAtLeast(0f))
        val remaining = target - current
        if (abs(remaining) <= Fonts.px(WHEEL_SETTLE_DP)) {
            wheelTarget = Float.NaN
            return target
        }
        val step = 1f - exp(-deltaSec / WHEEL_EASE_SEC)
        return current + remaining * step
    }

    /** Drop drag and inertia (a modal claimed the pointer, the viewport went away). */
    fun cancel() {
        dragOriginY = Float.NaN
        dragMoved = false
        lastSampleNanos = 0L
        wheelTarget = Float.NaN
        stopInertia()
    }

    /** Clamp to the range; hitting a hard edge kills leftover fling energy. */
    private fun clamped(raw: Float, max: Float): Float {
        val next = raw.coerceIn(0f, max.coerceAtLeast(0f))
        if (next != raw) stopInertia()
        return next
    }

    private fun stopInertia() {
        flinging = false
        velocityY = 0f
    }

    private fun sampleVelocity(dy: Float) {
        val now = System.nanoTime()
        if (lastSampleNanos == 0L) {
            lastSampleNanos = now
            return
        }
        val dt = (now - lastSampleNanos) * NANOS_TO_SEC
        lastSampleNanos = now
        // Ignore stalls (app pause) and zero-length samples.
        if (dt <= 0f || dt > MAX_VELOCITY_SAMPLE_SEC) return
        val instant = dy / dt
        // Light EMA so one large slop-crossing sample does not dominate the fling.
        velocityY = velocityY * (1f - VELOCITY_BLEND) + instant * VELOCITY_BLEND
    }

    companion object {
        /** Design-dp step per wheel unit (~3 old 28dp notches). */
        private const val WHEEL_STEP_DP = 88f

        /**
         * Wheel-ease time constant, in seconds. The step is `1 - e^(-dt/τ)`, so
         * τ is the time to cover ~63% of the remaining distance: small enough to
         * read as one gesture, long enough that a notch is not a jump cut.
         */
        private const val WHEEL_EASE_SEC = 0.13f

        /** Remaining distance below which the ease snaps and disarms (design dp). */
        private const val WHEEL_SETTLE_DP = 0.4f

        /** Exponential friction coefficient (1/s). Higher = stops sooner. */
        private const val FLING_FRICTION = 5.2f

        /** Launch fling only if release speed exceeds this (design dp/s). */
        private const val FLING_MIN_DP_PER_SEC = 180f

        /** Coast ends under this residual speed (design dp/s). */
        private const val FLING_STOP_DP_PER_SEC = 28f

        private const val VELOCITY_BLEND = 0.35f
        private const val MAX_VELOCITY_SAMPLE_SEC = 0.064f
        private const val NANOS_TO_SEC = 1e-9f
    }
}
