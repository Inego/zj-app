package inego.zungjunggame.ui

/**
 * Optional behavior a component exposes when it owns a text field (alias
 * editing, …).
 *
 * Character entry cannot go through the frame poll (libGDX delivers typed
 * characters and IME commits through the `InputProcessor`, outside the pointer
 * loop), so the platform processor asks the stack for the topmost component that
 * implements this contract and forwards the raw characters. Keys travel the
 * frame's key events instead, so the component receives the owning entry's
 * [Frame] and an Enter that submits the form emits through the same channel as
 * the card's own taps. The component in turn forwards both to the widget it
 * owns; the platform layer never reaches into an overlay.
 */
interface TextFieldHost {
    /** True while a field can accept keyboard/typed input. */
    val textFieldFocused: Boolean

    /** Raw key (arrows, backspace, Tab, Ctrl shortcuts). True when consumed. */
    fun onKeyDown(keycode: Int, frame: Frame): Boolean = false

    /** A composed/typed character. True when consumed. */
    fun onKeyTyped(character: Char): Boolean = false
}
