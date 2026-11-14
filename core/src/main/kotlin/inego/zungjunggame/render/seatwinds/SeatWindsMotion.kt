package inego.zungjunggame.render.seatwinds

import inego.zungjunggame.tutorial.slide.SlideEase

/**
 * Pause-then-rotate clock for the East-player deal-pass.
 *
 * One cycle = hold a seat assignment, then ease 90° counter-clockwise.
 * Four cycles return every wind to its home seat (You is East again).
 */
object SeatWindsMotion {

    const val PAUSE_SEC = 1.55f
    const val ROTATE_SEC = 0.85f

    val cycleSec: Float get() = PAUSE_SEC + ROTATE_SEC

    /** Four-hand loop length; wrapping here keeps float time bounded. */
    val loopSec: Float get() = cycleSec * 4f

    data class Phase(
        /** Completed 90° ccw steps in the current 4-hand loop (0..3). */
        val steps: Int,
        /**
         * 0 at rest, 1 at the end of the current 90° turn (eased).
         * Positions use [rotationTurns] = steps + this.
         */
        val rotateT: Float,
    ) {
        val rotationTurns: Float get() = steps + rotateT
        val atRest: Boolean get() = rotateT <= 0f
        /** 1..4, the rest assignment this step is showing or leaving. */
        val handNumber: Int get() = (steps and 3) + 1
    }

    fun phase(elapsedSec: Float): Phase {
        val cycle = cycleSec
        val loop = loopSec
        val raw = elapsedSec.coerceAtLeast(0f)
        val t = raw % loop
        val steps = (t / cycle).toInt().coerceIn(0, 3)
        val u = ((t - steps * cycle) / cycle).coerceIn(0f, 1f)
        val pauseFrac = PAUSE_SEC / cycle
        val rotateT = if (u <= pauseFrac) {
            0f
        } else {
            SlideEase.InOutCubic.apply((u - pauseFrac) / (1f - pauseFrac))
        }
        return Phase(steps, rotateT)
    }
}
