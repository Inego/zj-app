package inego.zungjunggame.platform

import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackServerTest {
    @Test
    fun bindIsLoopbackAndServeOneReturnsQuery() {
        LoopbackServer().use { server ->
            val redirect = server.bind()
            assertTrue(redirect.startsWith("http://127.0.0.1:"))
            val port = redirect.substringAfterLast(':').toInt()

            thread(isDaemon = true) {
                httpGet("$redirect/?code=the-code&state=s")
            }

            val query = server.serveOne(5_000) { q ->
                LoopbackResponse(200, "ok:$q", "text/plain; charset=utf-8")
            }
            assertEquals("code=the-code&state=s", query)
            assertTrue(port in 1..65535)
        }
    }

    @Test
    fun faviconProbeIsIgnoredUntilTheCallback() {
        LoopbackServer().use { server ->
            val redirect = server.bind()
            thread(isDaemon = true) {
                try {
                    httpGet("$redirect/favicon.ico")
                } catch (_: Exception) {
                }
                httpGet("$redirect/?error=access_denied&state=s")
            }
            val query = server.serveOne(5_000) { LoopbackResponse(200, "done") }
            assertEquals("error=access_denied&state=s", query)
        }
    }

    @Test
    fun parseRequestLineSplitsPathAndQuery() {
        val req = LoopbackServer.parseRequestLine("GET /?code=abc&state=s HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        assertEquals("/", req.path)
        assertEquals("code=abc&state=s", req.query)
    }

    private fun httpGet(uri: String): String {
        val conn = URI(uri).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        conn.inputStream.use { return it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
