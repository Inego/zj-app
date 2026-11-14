package inego.zungjunggame.session.board

/**
 * Shared pause used by [NonHumanTurnStepper] and
 * [inego.zungjunggame.session.online.PresentationSequencer].
 *
 * A step is applied after this frame's motion has already run, so the next idle
 * [allow] is skipped until render has had a chance to start the flight. Once the
 * board has actually been busy, that flight has started and finished, and the
 * following idle [allow] may take the next step. A pause that never sees a busy
 * board still skips exactly one idle frame.
 */
internal class AnimationIdleGate {
    var pauseUntilNextIdle: Boolean = false
        set(value) {
            field = value
            if (value) motionSeen = false
        }

    /** True once a busy board was observed while [pauseUntilNextIdle] was armed. */
    private var motionSeen: Boolean = false

    /**
     * @return true when a step may be applied this call.
     */
    fun allow(boardIdle: Boolean): Boolean {
        if (!boardIdle) {
            if (pauseUntilNextIdle) motionSeen = true
            return false
        }
        if (pauseUntilNextIdle) {
            val motionStarted = motionSeen
            pauseUntilNextIdle = false
            motionSeen = false
            if (!motionStarted) return false
        }
        return true
    }

    fun armAfterApply() {
        pauseUntilNextIdle = true
    }

    fun reset() {
        pauseUntilNextIdle = false
        motionSeen = false
    }
}
