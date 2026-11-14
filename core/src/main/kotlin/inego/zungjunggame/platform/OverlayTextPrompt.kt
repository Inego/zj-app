package inego.zungjunggame.platform

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import inego.zungjunggame.input.InputProfile

/**
 * One line of text to collect from the OS dialog.
 *
 * [selectAll] is how a pre-filled value is replaced by typing: the system
 * field starts with the seed highlighted. [maxLength] is enforced by the
 * native field when the platform honors it.
 */
data class LineRequest(
    val title: String,
    val text: String,
    val hint: String = "",
    val selectAll: Boolean = false,
    val maxLength: Int = Int.MAX_VALUE,
    /**
     * When set, the native field refuses an edit this predicate rejects.
     * Null keeps the platform's length cap only. A stored name longer than
     * the predicate's limit is still shown: the predicate runs on edits, and
     * the seed is applied before it.
     */
    val acceptEdit: ((before: String, after: String) -> Boolean)? = null,
)

/**
 * OS one-line text dialog.
 *
 * Desktop and Wasm type into an in-canvas field; a touch profile has no keys,
 * so a [request] is the way a string is collected. The result is always
 * asynchronous. Callers must ignore a late result after [dismiss] or a newer
 * [request].
 *
 * Android replaces [factory] with a dialog that shows the IME immediately and
 * honors [LineRequest.selectAll]. The default is [Gdx.input.getTextInput].
 */
interface OverlayTextPrompt {
    /**
     * True when this dialog replaces in-canvas typing (touch profiles).
     */
    val replacesKeyboard: Boolean

    fun request(request: LineRequest, onDone: (String?) -> Unit)

    /** Drop an in-flight dialog. A late [onDone] must not fire. */
    fun dismiss() {}

    companion object {
        var factory: () -> OverlayTextPrompt = { GdxOverlayTextPrompt() }

        fun create(): OverlayTextPrompt = factory()
    }
}

/** Fallback OS dialog via libGDX. Does not select-all or force the IME. */
class GdxOverlayTextPrompt : OverlayTextPrompt {
    override val replacesKeyboard: Boolean
        get() = !InputProfile.detect().usesKeyboard

    override fun request(request: LineRequest, onDone: (String?) -> Unit) {
        val input = Gdx.input ?: return
        input.getTextInput(
            object : Input.TextInputListener {
                override fun input(value: String?) {
                    postToAppThread { onDone(value) }
                }

                override fun canceled() {
                    postToAppThread { onDone(null) }
                }
            },
            request.title,
            request.text,
            request.hint,
            Input.OnscreenKeyboardType.Default,
        )
    }
}

/** In-memory prompt for tests. [complete] delivers the result. */
class MemoryOverlayTextPrompt(
    override val replacesKeyboard: Boolean = false,
) : OverlayTextPrompt {
    var requestCount: Int = 0
        private set
    var lastRequest: LineRequest? = null
        private set
    private var pending: ((String?) -> Unit)? = null

    override fun request(request: LineRequest, onDone: (String?) -> Unit) {
        requestCount += 1
        lastRequest = request
        pending = onDone
    }

    override fun dismiss() {
        pending = null
    }

    fun complete(value: String?) {
        val done = pending ?: return
        pending = null
        done(value)
    }
}
