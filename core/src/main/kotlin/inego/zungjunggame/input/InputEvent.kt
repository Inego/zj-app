package inego.zungjunggame.input

/** Device-agnostic input, normalized once per frame before routing. */
sealed interface InputEvent {
    data class Tap(val x: Float, val y: Float) : InputEvent
    data class PointerDown(
        val x: Float,
        val y: Float,
        val pointer: Int = 0,
        val pointerCount: Int = 1,
    ) : InputEvent

    data class PointerMove(
        val x: Float,
        val y: Float,
        val pointer: Int = 0,
        val pointerCount: Int = 1,
    ) : InputEvent

    data class PointerUp(val x: Float, val y: Float, val pointer: Int = 0) : InputEvent

    /**
     * OS-interrupted touch stream. [stillTouched] carries whether the platform
     * still reports a pointer down, so the arbitration layer knows to swallow the
     * remainder of the touch instead of treating a stale hold as a fresh press.
     */
    data class PointerCancel(val stillTouched: Boolean = false) : InputEvent

    /**
     * One wheel sample: both deltas of the same notch, and where the pointer was
     * when it arrived. A sample with both deltas zero is never emitted.
     *
     * [amountY] is positive when the wheel scrolls down (the content slides up),
     * libGDX's convention. [amountX] carries libGDX's x convention too: positive
     * when the content slides right, so a wheel turned right reports a negative —
     * the mirror of the vertical axis, because the desktop platform reports its
     * own x offset the other way round.
     */
    data class Scroll(
        val amountY: Float,
        val x: Float,
        val y: Float,
        val amountX: Float = 0f,
    ) : InputEvent
    data class KeyDown(val keycode: Int, val ctrl: Boolean = false, val shift: Boolean = false) : InputEvent
    data object SystemBack : InputEvent
}
