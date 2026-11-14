package inego.zungjunggame.render.tiles

import kotlin.math.abs
import kotlin.math.min

/** Pure easing / interpolation helpers for tile motion. */
object TileEasing {
    fun clamp01(t: Float): Float = when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        else -> t
    }

    /** Smooth decelerate into the target (nice for short flights). */
    fun easeOutCubic(t: Float): Float {
        val u = clamp01(t)
        val inv = 1f - u
        return 1f - inv * inv * inv
    }

    /**
     * Smooth accelerate then decelerate (cubic). Good for hand strokes so
     * motion does not read as constant-speed linear translation.
     */
    fun easeInOutCubic(t: Float): Float {
        val u = clamp01(t)
        return if (u < 0.5f) {
            4f * u * u * u
        } else {
            val v = -2f * u + 2f
            1f - (v * v * v) * 0.5f
        }
    }

    /**
     * Derivative of [easeInOutCubic] w.r.t. unit time in [0, 1].
     * Peak is 3 at mid; 0 at the endpoints.
     */
    fun easeInOutCubicDerivative(t: Float): Float {
        val u = clamp01(t)
        return if (u < 0.5f) {
            12f * u * u
        } else {
            val w = 1f - u
            12f * w * w
        }
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Shortest-path angle interpolation in degrees.
     * Result is not normalized to a fixed range (fine for drawing).
     */
    fun angleLerpDeg(from: Float, to: Float, t: Float): Float {
        val delta = angleDeltaSignedDeg(from, to)
        return from + delta * t
    }

    /** Signed shortest delta from [from] to [to] in (-180, 180]. */
    fun angleDeltaSignedDeg(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Absolute shortest angular distance in degrees, in [0, 180]. */
    fun angleDeltaDeg(from: Float, to: Float): Float = abs(angleDeltaSignedDeg(from, to))

    /**
     * Face fold progress: 0 = fully closed, 1 = fully open.
     * Returns (showOpen, scaleX) for a horizontal flip around the tile center.
     */
    fun faceDrawParams(face: Float): Pair<Boolean, Float> {
        val f = clamp01(face)
        return if (f >= 0.5f) {
            true to min(1f, (f - 0.5f) * 2f)
        } else {
            false to min(1f, (0.5f - f) * 2f)
        }
    }
}
