package inego.zungjunggame.platform

import com.badlogic.gdx.Gdx

/**
 * OS / browser clipboard for overlay text fields.
 *
 * [paste] is always asynchronous so Wasm document `paste` events and desktop
 * [com.badlogic.gdx.utils.Clipboard] share one call shape. Callers must ignore
 * a late result after the field loses focus.
 *
 * Platform launchers may replace [factory] before the first [create] call.
 * Desktop and Android keep the default ([GdxOverlayClipboard]); Wasm installs
 * a paste-event bridge (not `navigator.clipboard.readText`).
 */
interface OverlayClipboard {
    fun copy(text: String)

    fun paste(onDone: (String?) -> Unit)

    companion object {
        var factory: () -> OverlayClipboard = { GdxOverlayClipboard() }

        fun create(): OverlayClipboard = factory()
    }
}

/** Desktop / Android: [Gdx.app.clipboard]. Null app or clipboard is a no-op. */
class GdxOverlayClipboard : OverlayClipboard {
    override fun copy(text: String) {
        try {
            Gdx.app?.clipboard?.contents = text
        } catch (_: Throwable) {
        }
    }

    override fun paste(onDone: (String?) -> Unit) {
        val value = try {
            Gdx.app?.clipboard?.contents
        } catch (_: Throwable) {
            null
        }
        postToAppThread { onDone(value) }
    }
}

/** In-memory clipboard for tests. */
class MemoryOverlayClipboard(
    var contents: String = "",
) : OverlayClipboard {
    override fun copy(text: String) {
        contents = text
    }

    override fun paste(onDone: (String?) -> Unit) {
        onDone(contents)
    }
}
