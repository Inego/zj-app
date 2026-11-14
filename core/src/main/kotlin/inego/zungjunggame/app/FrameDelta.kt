package inego.zungjunggame.app

/**
 * Caps the simulation step the frame loop hands to motion.
 *
 * A stalled present (a pause, a GC spike, a missed blank) reports a multi-second
 * [com.badlogic.gdx.Graphics.getDeltaTime]. Feeding that straight into motion
 * fast-forwards every in-flight animation. One step is clamped to [MAX_SEC].
 */
internal object FrameDelta {
    /** Longest step motion is allowed to take, in seconds. */
    const val MAX_SEC = 0.25f

    fun clamp(raw: Float): Float =
        if (raw.isNaN()) 0f else raw.coerceIn(0f, MAX_SEC)
}
