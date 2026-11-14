package inego.zungjunggame.platform

import com.badlogic.gdx.Gdx

/**
 * Cross-platform WebSocket client abstraction.
 *
 * Listener callbacks are marshalled onto the libGDX application thread via
 * [Gdx.app.postRunnable] when [Gdx.app] is available, or executed immediately
 * in headless/test environments.
 *
 * Platform launchers must install [factory] before the first [create] call.
 * The default is a no-op: `java.net.http.WebSocket` is not on the TeaVM
 * classpath, and `gdxTeaVM { reflection('inego.zungjunggame.**') }` would
 * otherwise keep a JVM client in the Wasm graph.
 */
interface WebSocketClient {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClose(code: Int, reason: String)
        fun onError(cause: Throwable)
    }

    fun connect(url: String, headers: Map<String, String> = emptyMap(), listener: Listener)

    fun send(text: String): Boolean

    fun close(code: Int = 1000, reason: String = "normal")

    val isConnected: Boolean

    companion object {
        var factory: () -> WebSocketClient = { NoOpWebSocketClient() }

        fun create(): WebSocketClient = factory()
    }
}

/** Safe default when no platform factory has been installed. */
class NoOpWebSocketClient : WebSocketClient {
    override val isConnected: Boolean = false

    override fun connect(url: String, headers: Map<String, String>, listener: WebSocketClient.Listener) {
        post {
            listener.onError(IllegalStateException("No WebSocket implementation installed"))
        }
    }

    override fun send(text: String): Boolean = false

    override fun close(code: Int, reason: String) {}
}

/**
 * Run [action] on the libGDX application thread, or immediately if none is set.
 *
 * On Wasm this post is also the wake. An idle page schedules no animation
 * frame, and [com.badlogic.gdx.Application.postRunnable] is what asks for one.
 * Running [action] inline because the browser has one thread updates the model
 * and leaves the picture frozen until a pointer event.
 */
fun postToAppThread(action: () -> Unit) {
    val app = Gdx.app
    if (app != null) {
        app.postRunnable(action)
    } else {
        action()
    }
}

private fun post(action: () -> Unit) = postToAppThread(action)
