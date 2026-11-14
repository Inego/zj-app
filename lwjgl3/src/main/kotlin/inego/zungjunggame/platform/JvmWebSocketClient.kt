package inego.zungjunggame.platform

import com.badlogic.gdx.Gdx
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

/**
 * Standard JVM 21 [WebSocketClient] using [java.net.http.WebSocket].
 * Lives outside `:core` so TeaVM (`reflection('inego.zungjunggame.**')`) never
 * compiles `java.net.http` types.
 */
class JvmWebSocketClient(
    private val connectTimeout: Duration = Duration.ofSeconds(10),
) : WebSocketClient {

    private var ws: WebSocket? = null
    private var listener: WebSocketClient.Listener? = null
    private val buffer = StringBuilder()

    @Volatile
    private var connected: Boolean = false

    override val isConnected: Boolean
        get() = connected && ws != null

    override fun connect(url: String, headers: Map<String, String>, listener: WebSocketClient.Listener) {
        this.listener = listener
        this.connected = false
        this.buffer.clear()

        val client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build()

        val builder = client.newWebSocketBuilder()
            .connectTimeout(connectTimeout)

        for ((k, v) in headers) {
            builder.header(k, v)
        }

        builder.buildAsync(URI.create(url), object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                ws = webSocket
                connected = true
                webSocket.request(1)
                post { listener.onOpen() }
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val fullText = buffer.toString()
                    buffer.clear()
                    post { listener.onMessage(fullText) }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                connected = false
                ws = null
                post { listener.onClose(statusCode, reason) }
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                connected = false
                ws = null
                post { listener.onError(error) }
            }
        }).exceptionally { ex ->
            connected = false
            ws = null
            post { listener.onError(ex) }
            null
        }
    }

    override fun send(text: String): Boolean {
        val socket = ws ?: return false
        if (!connected) return false
        socket.sendText(text, true)
        return true
    }

    override fun close(code: Int, reason: String) {
        connected = false
        val socket = ws
        ws = null
        try {
            socket?.sendClose(code, reason)
        } catch (_: Throwable) {
        }
    }

    private fun post(action: () -> Unit) {
        val wrapped = { DesktopCrashHook.run("JvmWebSocketClient") { action() } }
        val app = Gdx.app
        if (app != null) {
            app.postRunnable(wrapped)
        } else {
            wrapped()
        }
    }
}
