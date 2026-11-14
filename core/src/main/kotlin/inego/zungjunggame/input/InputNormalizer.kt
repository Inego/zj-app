package inego.zungjunggame.input

/**
 * Converts one frame of [RawInput] into ordered [InputEvent]s.
 *
 * Stateless; emit order is pointer (cancel, or down/move), pointer up/tap,
 * scroll, keys, then back. A cancelled frame never emits up/tap: the OS
 * withdrew the pointer, so the release the platform still reports is not a tap.
 * A wheel sample is emitted when either axis is non-zero and carries both; the
 * pointer events keep their single-pointer shape (position, `pointer`,
 * `pointerCount`), and the frame's full pointer set stays on [RawInput.pointers].
 */
class InputNormalizer {
    /** Normalizes [raw]; never null, empty when the frame carries no input. */
    fun normalize(raw: RawInput): List<InputEvent> {
        val events = ArrayList<InputEvent>(5)

        if (raw.touchCancelled) {
            events += InputEvent.PointerCancel(stillTouched = raw.isTouched)
        } else {
            if (raw.justTouched) {
                events += InputEvent.PointerDown(
                    raw.touchX(),
                    raw.touchY(),
                    pointerCount = raw.pointerCount,
                )
            } else if (raw.isTouched) {
                events += InputEvent.PointerMove(
                    raw.touchX(),
                    raw.touchY(),
                    pointerCount = raw.pointerCount,
                )
            }
            if (raw.justReleased) {
                val x = raw.touchX()
                val y = raw.touchY()
                events += InputEvent.PointerUp(x, y)
                events += InputEvent.Tap(x, y)
            }
        }

        // Read once each: the wheel deltas are a one-shot frame sample, and its
        // two axes are independent (a trackpad pan may deliver one alone).
        val scrollY = raw.scrollAmount
        val scrollX = raw.scrollAmountX
        if (scrollX != 0f || scrollY != 0f) {
            events += InputEvent.Scroll(scrollY, raw.scrollX, raw.scrollY, amountX = scrollX)
        }
        for (keycode in raw.keysJustPressed()) {
            events += InputEvent.KeyDown(keycode, raw.ctrl, raw.shift)
        }
        if (raw.backPressed) {
            events += InputEvent.SystemBack
        }
        return events
    }
}
