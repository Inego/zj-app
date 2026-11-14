package inego.zungjunggame.platform

import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop Google Sign-In via loopback + PKCE.
 *
 * [clientId] is a **Desktop app** OAuth client ID (not the web / server ID
 * Wasm and Android use as the ID-token audience). Register that client in
 * the same Google Cloud project and list it in `GOOGLE_CLIENT_IDS` so the
 * server accepts the token's `aud`. The nonce is forwarded to Google as-is.
 *
 * [clientSecret] is that same client's secret and is required: Google returns
 * `invalid_request: client_secret is missing` for a Desktop app client that
 * omits it, however valid the PKCE verifier and loopback `redirect_uri` are.
 * It never leaves this process — the server only sees the resulting ID token.
 * An ID without a secret is treated as unavailable rather than as a retryable
 * network failure, so a half-configured build fails fast and legibly.
 *
 * [clearCredentialState] is a no-op: there is no local Google credential
 * cache for loopback OAuth (not a global Google logout).
 */
class DesktopGoogleAuthProvider(
    override val clientId: String = loadDesktopClientId(),
    private val clientSecret: String = loadDesktopClientSecret(),
    private val openBrowser: (URI) -> Unit = Companion::openSystemBrowser,
    private val exchange: (redirectUri: String, code: String, verifier: String) -> String =
        { redirect, code, verifier ->
            exchangeAuthorizationCode(clientId, clientSecret, redirect, code, verifier)
        },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : GoogleAuthProvider {

    override val isAvailable: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private val requestGen = AtomicInteger(0)

    @Volatile
    private var loopback: LoopbackServer? = null

    override fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit) {
        cancel()
        val gen = requestGen.incrementAndGet()
        val finish: (Result<String>) -> Unit = { result ->
            postToAppThread {
                if (gen != requestGen.get()) return@postToAppThread
                requestGen.incrementAndGet()
                onDone(result)
            }
        }
        if (!isAvailable) {
            finish(Result.failure(GoogleSignInException.fromDesktopCode("init_failed")))
            return
        }
        Thread(
            {
                try {
                    runFlow(gen, nonce, finish)
                } catch (t: Throwable) {
                    if (gen != requestGen.get()) return@Thread
                    finish(Result.failure(mapException(t)))
                } finally {
                    loopback?.close()
                    loopback = null
                }
            },
            "zj-google-desktop",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun cancel() {
        requestGen.incrementAndGet()
        loopback?.close()
        loopback = null
    }

    override fun clearCredentialState() {
        // Loopback OAuth has no local Google session to forget.
    }

    private fun runFlow(gen: Int, nonce: String, finish: (Result<String>) -> Unit) {
        val server = LoopbackServer()
        if (gen != requestGen.get()) {
            server.close()
            return
        }
        val redirectUri = server.bind()
        if (gen != requestGen.get()) {
            server.close()
            return
        }
        loopback = server
        val verifier = DesktopOAuth.pkceVerifier()
        val challenge = DesktopOAuth.pkceChallengeS256(verifier)
        val state = DesktopOAuth.randomState()
        val authorize = DesktopOAuth.authorizeUrl(
            clientId = clientId,
            redirectUri = redirectUri,
            nonce = nonce,
            state = state,
            codeChallenge = challenge,
        )
        try {
            openBrowser(authorize)
        } catch (t: Throwable) {
            if (gen != requestGen.get()) return
            finish(Result.failure(GoogleSignInException.fromDesktopCode("browser")))
            return
        }
        val query = try {
            server.serveOne(timeoutMs) { q ->
                val parsed = DesktopOAuth.parseCallback(q, state)
                LoopbackResponse(parsed.httpStatus, parsed.html)
            }
        } catch (t: Throwable) {
            if (gen != requestGen.get()) return
            throw t
        }
        if (gen != requestGen.get()) return
        when (val parsed = DesktopOAuth.parseCallback(query, state)) {
            is DesktopOAuth.Callback.Cancelled -> {
                finish(Result.failure(GoogleSignInException.fromDesktopCode("access_denied")))
            }
            is DesktopOAuth.Callback.Failed -> {
                finish(Result.failure(GoogleSignInException.fromDesktopCode(parsed.code)))
            }
            is DesktopOAuth.Callback.Code -> {
                val idToken = try {
                    exchange(redirectUri, parsed.code, verifier)
                } catch (t: Throwable) {
                    if (gen != requestGen.get()) return
                    finish(Result.failure(mapException(t)))
                    return
                }
                if (gen != requestGen.get()) return
                if (idToken.isBlank()) {
                    finish(Result.failure(GoogleSignInException.fromDesktopCode("empty_credential")))
                } else {
                    finish(Result.success(idToken))
                }
            }
        }
    }

    private fun mapException(err: Throwable): Exception = when (err) {
        is GoogleSignInException -> err
        is GoogleSignInCancelled -> err
        is SocketTimeoutException -> GoogleSignInException.fromDesktopCode("timeout")
        is SocketException -> GoogleSignInException.fromDesktopCode("timeout")
        is IOException -> GoogleSignInException.fromDesktopCode("network")
        else -> GoogleSignInException.fromDesktopCode("unknown")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
        private const val RESOURCE = "/desktop-google.properties"
        private const val TOKEN_CONNECT_MS = 10_000
        private const val TOKEN_READ_MS = 10_000

        internal fun loadDesktopClientId(): String = loadSetting("clientId")

        internal fun loadDesktopClientSecret(): String = loadSetting("clientSecret")

        /** First matching `key=` line, or `""` when the resource is missing or unfiltered. */
        private fun loadSetting(key: String): String {
            val stream = DesktopGoogleAuthProvider::class.java.getResourceAsStream(RESOURCE)
                ?: return ""
            stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                for (line in reader.lineSequence()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("$key=")) continue
                    val value = trimmed.removePrefix("$key=").trim()
                    if (value.isBlank() || value.startsWith("__")) return ""
                    return value
                }
            }
            return ""
        }

        internal fun openSystemBrowser(uri: URI) {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(uri)
                    return
                }
            }
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val command = when {
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
                os.contains("mac") -> arrayOf("open", uri.toString())
                else -> arrayOf("xdg-open", uri.toString())
            }
            ProcessBuilder(*command).start()
        }

        internal fun exchangeAuthorizationCode(
            clientId: String,
            clientSecret: String,
            redirectUri: String,
            code: String,
            codeVerifier: String,
        ): String {
            val form = DesktopOAuth.tokenForm(clientId, clientSecret, redirectUri, code, codeVerifier)
            val conn = URI(DesktopOAuth.TOKEN_URL).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TOKEN_CONNECT_MS
            conn.readTimeout = TOKEN_READ_MS
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            try {
                conn.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
                if (status !in 200..299) {
                    throw IOException("token endpoint $status")
                }
                return DesktopOAuth.parseIdToken(body)
                    ?: throw IOException("token endpoint missing id_token")
            } finally {
                conn.disconnect()
            }
        }
    }
}
