package inego.zungjunggame.android

import com.badlogic.gdx.Gdx
import inego.zungjunggame.platform.WebSocketClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Android WebSocket implementation backed by OkHttp. */
class AndroidWebSocketClient : WebSocketClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connected = false

    override val isConnected: Boolean
        get() = connected && socket != null

    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: WebSocketClient.Listener,
    ) {
        close()
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        socket = httpClient.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                connected = true
                post { listener.onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                post { listener.onMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                post { listener.onClose(code, reason) }
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                if (socket === webSocket) socket = null
                post { listener.onError(t) }
            }
        })
    }

    override fun send(text: String): Boolean {
        val current = socket ?: return false
        return connected && current.send(text)
    }

    override fun close(code: Int, reason: String) {
        connected = false
        val current = socket
        socket = null
        current?.close(code, reason)
    }

    private fun post(action: () -> Unit) {
        val app = Gdx.app
        if (app != null) app.postRunnable(action) else action()
    }
}