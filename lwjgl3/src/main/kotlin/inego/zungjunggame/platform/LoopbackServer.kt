package inego.zungjunggame.platform

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Single-shot HTTP/1.1 GET listener bound to 127.0.0.1.
 *
 * Ignores favicon / empty-query probes so the Google redirect (query contains
 * `code` or `error`) is the request that completes the flow.
 */
internal class LoopbackServer : AutoCloseable {
    private val lock = Any()
    private var server: ServerSocket? = null

    fun bind(): String {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        synchronized(lock) {
            closeLocked()
            server = ss
        }
        return redirectUri(ss.localPort)
    }

    /**
     * Block until one callback GET is handled, write [respond]'s HTML, return
     * the raw query string. [SocketTimeoutException] if [timeoutMs] elapses.
     */
    fun serveOne(timeoutMs: Long, respond: (query: String) -> LoopbackResponse): String {
        val ss = synchronized(lock) { server } ?: error("loopback not bound")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("loopback timeout")
            ss.soTimeout = remaining.toInt().coerceAtLeast(1)
            val socket = ss.accept()
            socket.soTimeout = READ_TIMEOUT_MS
            val req = try {
                readGet(socket)
            } catch (t: Throwable) {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
                throw t
            }
            if (!isCallback(req)) {
                write(socket, LoopbackResponse(204, ""))
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
                continue
            }
            val resp = respond(req.query)
            write(socket, resp)
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            return req.query
        }
    }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
    }

    internal data class Incoming(val path: String, val query: String)

    private fun isCallback(req: Incoming): Boolean {
        val q = req.query
        if (q.contains("code=") || q.contains("error=")) return true
        val path = req.path
        return q.isNotEmpty() && (path.isEmpty() || path == "/")
    }

    companion object {
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_HEADER_BYTES = 8_192

        fun redirectUri(port: Int): String = "http://127.0.0.1:$port"

        internal fun parseRequestLine(header: String): Incoming {
            val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
            val parts = first.split(' ')
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                throw IOException("expected GET")
            }
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q < 0) target else target.substring(0, q)
            val query = if (q < 0) "" else target.substring(q + 1)
            return Incoming(path = path, query = query)
        }

        internal fun readGet(socket: Socket): Incoming {
            val input = socket.getInputStream()
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(256)
            while (buf.size() < MAX_HEADER_BYTES) {
                val n = input.read(tmp)
                if (n < 0) break
                buf.write(tmp, 0, n)
                val bytes = buf.toByteArray()
                val end = indexOfHeaderEnd(bytes)
                if (end >= 0) {
                    val header = String(bytes, 0, end, StandardCharsets.US_ASCII)
                    return parseRequestLine(header)
                }
            }
            throw IOException("HTTP request too large or incomplete")
        }

        internal fun indexOfHeaderEnd(bytes: ByteArray): Int {
            for (i in 0 until bytes.size - 3) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i
                }
            }
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                    return i
                }
            }
            return -1
        }

        internal fun write(socket: Socket, resp: LoopbackResponse) {
            val body = resp.body.toByteArray(StandardCharsets.UTF_8)
            val reason = when (resp.status) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                else -> "OK"
            }
            val header = buildString {
                append("HTTP/1.1 ").append(resp.status).append(' ').append(reason).append("\r\n")
                append("Content-Type: ").append(resp.contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            val out = socket.getOutputStream()
            out.write(header)
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }
}

internal data class LoopbackResponse(
    val status: Int,
    val body: String,
    val contentType: String = "text/html; charset=utf-8",
)
