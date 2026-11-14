package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.ui.TextFieldHost

/**
 * Minimal platform input processor for the component pipeline.
 *
 * The frame pipeline (RawInput → InputNormalizer → ComponentHost) owns pointer,
 * gesture, keyboard, and scroll routing. This processor only carries the Gdx
 * callbacks the frame poll cannot observe:
 *  - typed characters and IME commits, forwarded to the topmost stacked
 *    [TextFieldHost] (raw keys reach it as frame key events, so a submit emits
 *    through the owning entry's frame);
 *  - the scroll wheel sample, both axes, forwarded to [install]'s `onScroll`;
 *  - the OS touch-cancel signal, forwarded to `onTouchCancelled`.
 *
 * The diagnostics surface the runtime logs through is preserved; the retired
 * pointer/keyboard routers' gesture state is no longer available.
 */
class PlatformInput(
    private val profile: InputProfile,
    /**
     * The topmost component that currently owns a text field, or null. Wire to
     * the router stack after construction (the stack does not exist yet here).
     */
    private val textFocus: () -> TextFieldHost? = { null },
) {

    /**
     * Install the platform input processor: overlay text fields and the scroll
     * wheel on desktop, and the OS touch-cancel signal on every profile.
     *
     * The cancel half matters on touch: Android `ACTION_CANCEL` (three-finger
     * swipe screenshot, shade pull, palm rejection) reaches libGDX as
     * `touchCancelled` and simultaneously clears `isTouched`, so without this
     * hook the frame poll cannot tell it apart from a deliberate finger-up.
     * Call from [inego.zungjunggame.app.AppGraph.create] while the graph is being
     * assembled, before it is handed back.
     *
     * @param onTouchCancelled invoked when the OS withdraws the touch stream,
     *   so the [inego.zungjunggame.input.RawInput]-based pipeline can abort too.
     * @param onScroll invoked with the wheel sample's horizontal and vertical
     *   deltas (`amountX`, `amountY`) so the new pipeline can observe both axes
     *   of the one sample.
     */
    fun install(
        onTouchCancelled: (() -> Unit)? = null,
        onScroll: ((Float, Float) -> Unit)? = null,
    ) {
        if (profile.usesPressHover) {
            Gdx.input.setCatchKey(Input.Keys.BACK, true)
        }
        val useProcessor = profile.usesKeyboard || profile.usesScrollWheel || profile.usesPressHover
        if (!useProcessor) return
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun keyTyped(character: Char): Boolean =
                textFocus()?.onKeyTyped(character) ?: false

            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                onScroll?.invoke(amountX, amountY)
                return true
            }

            override fun touchCancelled(
                screenX: Int,
                screenY: Int,
                pointer: Int,
                button: Int,
            ): Boolean {
                onTouchCancelled?.invoke()
                TouchDiagnostics.log("touchCancelled pointer=$pointer screen=$screenX,$screenY")
                return false
            }
        }
    }

    /**
     * Release the runtime's input processor (call before teardown).
     *
     * [install] re-registers one, so clearing cannot strand a later feature.
     */
    fun close() {
        if (profile.usesKeyboard || profile.usesScrollWheel || profile.usesPressHover) {
            Gdx.input.inputProcessor = null
        }
    }

    fun logLifecycle(message: String) {
        TouchDiagnostics.log(message)
    }
}
