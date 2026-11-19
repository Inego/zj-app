package inego.zungjunggame.lwjgl3

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.backends.lwjgl3.DefaultLwjgl3Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Input
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window
import org.lwjgl.glfw.GLFW

/**
 * The default desktop input, except that a typed character keeps its code point.
 *
 * `DefaultLwjgl3Input` casts every GLFW code point to `char`
 * (`lastCharacter = (char)codepoint`), so anything above U+FFFF is truncated:
 * 😊 (U+1F60A) arrives as U+F60A, a private-use glyph no face can draw, and a
 * supplementary Han character arrives as whatever BMP code point its low half
 * happens to form. Keys, pointer and scroll stay with the delegate; only the
 * character callback is replaced. The full code point is split with
 * [Character.toChars] and queued, and [update] hands the UTF-16 units to the
 * processor on the render thread — the shape `OverlayTextBuffer` already pairs.
 */
class CodepointInput(
    private val delegate: DefaultLwjgl3Input,
) : Lwjgl3Input by delegate {
    private val typed = ArrayDeque<Char>()
    private var processor: InputProcessor? = null

    override fun setInputProcessor(processor: InputProcessor?) {
        this.processor = processor
        delegate.inputProcessor = processor
    }

    override fun windowHandleChanged(windowHandle: Long) {
        delegate.windowHandleChanged(windowHandle)
        // Installed after the delegate's own callbacks: GLFW keeps one character
        // callback per window, and the delegate's is the one that truncates.
        GLFW.glfwSetCharCallback(windowHandle) { _, codepoint ->
            // Function keys on macOS, which the default input drops as well.
            if (codepoint and 0xFF00 != 0xF700) {
                for (unit in Character.toChars(codepoint)) typed.addLast(unit)
                Gdx.graphics?.requestRendering()
            }
        }
    }

    override fun update() {
        delegate.update()
        while (typed.isNotEmpty()) processor?.keyTyped(typed.removeFirst())
    }
}

/** The desktop application, with [CodepointInput] in place of the default input. */
class ZjLwjgl3Application(
    listener: ApplicationListener,
    config: Lwjgl3ApplicationConfiguration,
) : Lwjgl3Application(listener, config) {
    override fun createInput(window: Lwjgl3Window): Lwjgl3Input =
        CodepointInput(DefaultLwjgl3Input(window))
}
