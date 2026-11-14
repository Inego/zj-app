package inego.zungjunggame.platform

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class DesktopGoogleAuthProviderTest {
    @Test
    fun blankClientIdIsUnavailable() {
        val provider = DesktopGoogleAuthProvider(clientId = "", clientSecret = SECRET)
        assertFalse(provider.isAvailable)
        val result = awaitResult(provider, "nonce")
        val err = assertIs<GoogleSignInException>(result.exceptionOrNull())
        assertEquals("init_failed", err.code)
        assertFalse(err.retryable)
    }

    /**
     * A Desktop app client cannot exchange without its secret, so an ID without
     * one is a config gap, not a retryable network error: the loopback round
     * trip would otherwise succeed and only the token call would fail.
     */
    @Test
    fun blankClientSecretIsUnavailableNotRetryable() {
        val provider = DesktopGoogleAuthProvider(clientId = CLIENT_ID, clientSecret = "")
        assertFalse(provider.isAvailable)
        val result = awaitResult(provider, "nonce")
        val err = assertIs<GoogleSignInException>(result.exceptionOrNull())
        assertEquals("init_failed", err.code)
        assertFalse(err.retryable)
    }

    @Test
    fun requestIdTokenRunsLoopbackPkceAndReturnsIdToken() {
        var opened: URI? = null
        var exchangedCode: String? = null
        var exchangedVerifier: String? = null
        val provider = DesktopGoogleAuthProvider(
            clientId = CLIENT_ID,
            clientSecret = SECRET,
            openBrowser = { uri ->
                opened = uri
                thread(isDaemon = true) { hitCallback(uri, "code=auth-code") }
            },
            exchange = { _, code, verifier ->
                exchangedCode = code
                exchangedVerifier = verifier
                "id-token-from-google"
            },
            timeoutMs = 5_000,
        )
        assertTrue(provider.isAvailable)

        val result = awaitResult(provider, "c2-nonce-unchanged")
        assertEquals("id-token-from-google", result.getOrThrow())
        assertEquals("c2-nonce-unchanged", DesktopOAuth.queryParam(opened!!, "nonce"))
        assertEquals("S256", DesktopOAuth.queryParam(opened, "code_challenge_method"))
        assertEquals("auth-code", exchangedCode)
        assertFalse(exchangedVerifier.isNullOrBlank())
    }

    @Test
    fun accessDeniedIsCancelledNotAnAuthError() {
        val provider = DesktopGoogleAuthProvider(
            clientId = CLIENT_ID,
            clientSecret = SECRET,
            openBrowser = { uri ->
                thread(isDaemon = true) { hitCallback(uri, "error=access_denied") }
            },
            exchange = { _, _, _ -> error("should not exchange") },
            timeoutMs = 5_000,
        )
        val result = awaitResult(provider, "nonce")
        assertIs<GoogleSignInCancelled>(result.exceptionOrNull())
    }

    @Test
    fun cancelDropsInFlightLoopbackWithoutCallback() {
        val bound = CountDownLatch(1)
        var called = false
        val provider = DesktopGoogleAuthProvider(
            clientId = CLIENT_ID,
            clientSecret = SECRET,
            openBrowser = { bound.countDown() },
            exchange = { _, _, _ -> error("should not exchange") },
            timeoutMs = 5_000,
        )
        provider.requestIdToken("nonce") { called = true }
        assertTrue(bound.await(5, TimeUnit.SECONDS))
        provider.cancel()
        Thread.sleep(200)
        assertFalse(called)
    }

    @Test
    fun timeoutIsRetryable() {
        val provider = DesktopGoogleAuthProvider(
            clientId = CLIENT_ID,
            clientSecret = SECRET,
            openBrowser = { },
            exchange = { _, _, _ -> error("should not exchange") },
            timeoutMs = 200,
        )
        val result = awaitResult(provider, "nonce", waitMs = 5_000)
        val err = assertIs<GoogleSignInException>(result.exceptionOrNull())
        assertEquals("timeout", err.code)
        assertTrue(err.retryable)
    }

    private companion object {
        const val CLIENT_ID = "desktop.apps.googleusercontent.com"
        const val SECRET = "desktop-client-secret"
    }

    private fun awaitResult(
        provider: DesktopGoogleAuthProvider,
        nonce: String,
        waitMs: Long = 8_000,
    ): Result<String> {
        val latch = CountDownLatch(1)
        var result: Result<String>? = null
        provider.requestIdToken(nonce) {
            result = it
            latch.countDown()
        }
        assertTrue(latch.await(waitMs, TimeUnit.MILLISECONDS), "requestIdToken did not finish")
        return result!!
    }

    private fun hitCallback(authorize: URI, extraQuery: String) {
        val redirect = DesktopOAuth.queryParam(authorize, "redirect_uri")
            ?: error("missing redirect_uri")
        val state = DesktopOAuth.queryParam(authorize, "state") ?: ""
        val sep = if (extraQuery.contains("state=")) extraQuery else "$extraQuery&state=$state"
        val conn = URI("$redirect/?$sep").toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        try {
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            conn.errorStream?.use { it.readBytes() }
        }
    }
}
