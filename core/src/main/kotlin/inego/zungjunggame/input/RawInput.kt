package inego.zungjunggame.input

import inego.zungjunggame.ui.FramePointers
import inego.zungjunggame.ui.PointerSample

/** Frame snapshot of a device backend consumed by [InputNormalizer]. */
interface RawInput {
    val justTouched: Boolean
    val justReleased: Boolean
    fun touchX(): Float
    fun touchY(): Float

    /** True while the primary pointer is held down this frame. */
    val isTouched: Boolean get() = justTouched

    /**
     * True when the OS took the touch stream over since the previous frame
     * (Android `ACTION_CANCEL`), so the pipeline aborts instead of reading the
     * vanished pointer as a finger-up.
     */
    val touchCancelled: Boolean get() = false

    /** Pointers currently down; a value above one is a system gesture, not a tap. */
    val pointerCount: Int get() = if (isTouched) 1 else 0

    /**
     * Every pointer this frame reports down, primary first, with its id and world
     * position ([FramePointers]): iterate it for the whole set, or read
     * `primary`/`secondary`. The legacy reading is a single pointer — slot 0 at
     * [touchX]/[touchY] while [isTouched] — which is what a backend that cannot
     * see a second pointer keeps.
     */
    val pointers: FramePointers
        get() = if (isTouched) {
            FramePointers(listOf(PointerSample(0, touchX(), touchY())))
        } else {
            FramePointers.EMPTY
        }

    /** Scroll amount this frame; 0 if none. */
    val scrollAmount: Float

    /**
     * Horizontal scroll amount of the same wheel sample; 0 if none. The two axes
     * arrive together, so a backend whose wheel is vertical only leaves this at
     * zero.
     */
    val scrollAmountX: Float get() = 0f

    val scrollX: Float
    val scrollY: Float
    fun keysJustPressed(): List<Int>
    val backPressed: Boolean
    val ctrl: Boolean
    val shift: Boolean
}
