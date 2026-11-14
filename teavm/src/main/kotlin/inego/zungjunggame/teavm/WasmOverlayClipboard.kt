package inego.zungjunggame.teavm

import com.badlogic.gdx.Gdx
import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber
import com.github.xpenatan.gdx.teavm.backends.web.dom.ClipboardEventWrapper
import com.github.xpenatan.gdx.teavm.backends.web.dom.impl.WebWindow
import inego.zungjunggame.platform.OverlayClipboard
import inego.zungjunggame.platform.postToAppThread
import org.teavm.jso.dom.events.Event

/**
 * Wasm overlay clipboard. Ctrl/Cmd+V arms a one-shot callback; a Java
 * document `paste` listener (same path as gdx-teavm WebClipboard) delivers
 * `clipboardData`. Do not use `navigator.clipboard.readText` — Chrome prompts
 * for clipboard-read and then drops the text.
 */
class WasmOverlayClipboard : OverlayClipboard {
    override fun copy(text: String) {
        try {
            Gdx.app?.clipboard?.contents = text
        } catch (_: Throwable) {
        }
        NativeClipboard.write(text)
    }

    override fun paste(onDone: (String?) -> Unit) {
        pending = onDone
    }

    companion object {
        val shared: OverlayClipboard by lazy { WasmOverlayClipboard() }

        private var installed = false
        private var pending: ((String?) -> Unit)? = null

        private fun install() {
            if (installed) return
            installed = true
            WebWindow.get().document.addEventListener("paste") { evt: Event ->
                BrowserFiber.enter { handleDocumentPaste(evt) }
            }
        }

        private fun handleDocumentPaste(evt: Event) {
            val fromEvent = try {
                (evt as ClipboardEventWrapper).clipboardData?.getData("text/plain") ?: ""
            } catch (_: Throwable) {
                ""
            }
            val fromGdx = try {
                Gdx.app?.clipboard?.contents ?: ""
            } catch (_: Throwable) {
                ""
            }
            val text = fromEvent.ifEmpty { fromGdx }
            val cb = pending
            pending = null
            if (cb != null) postToAppThread { cb(text) }
        }
    }

    init {
        install()
    }
}
