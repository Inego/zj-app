package inego.zungjunggame.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAuthProviderTest {

    @Test
    fun noOpRequestIdTokenFailsWithoutThrowing() {
        val provider = NoOpGoogleAuthProvider()
        var result: Result<String>? = null
        provider.requestIdToken("nonce") { result = it }
        assertTrue(result!!.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }

    @Test
    fun gisCodeMappingSweep() {
        val cancelled = listOf("cancelled", "user_cancel")
        val retryable = listOf(
            "script_load",
            "timeout",
            "popup_blocked",
            "empty_credential",
            "button_failed",
            "fedcm_failed",
        )
        val notRetryable = listOf("insecure", "init_failed")

        for (code in cancelled) {
            assertIs<GoogleSignInCancelled>(GoogleSignInException.fromGisCode(code), code)
        }
        for (code in retryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromGisCode(code), code)
            assertTrue(err.retryable, code)
            assertEquals(code, err.code, code)
        }
        for (code in notRetryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromGisCode(code), code)
            assertFalse(err.retryable, code)
            assertEquals(code, err.code, code)
        }
    }

    @Test
    fun gisCodeUnknownIsRetryableWithoutBlankCode() {
        val err = GoogleSignInException.fromGisCode("") as GoogleSignInException
        assertTrue(err.retryable)
        assertNull(err.code)
    }

    @Test
    fun androidCodeMappingSweep() {
        val cancelled = listOf("cancelled", "user_cancel")
        val retryable = listOf(
            "no_credential",
            "interrupted",
            "provider_config",
            "empty_credential",
            "parse",
        )
        val notRetryable = listOf("unsupported", "init_failed")

        for (code in cancelled) {
            assertIs<GoogleSignInCancelled>(GoogleSignInException.fromAndroidCode(code), code)
        }
        for (code in retryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromAndroidCode(code), code)
            assertTrue(err.retryable, code)
            assertEquals(code, err.code, code)
        }
        for (code in notRetryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromAndroidCode(code), code)
            assertFalse(err.retryable, code)
            assertEquals(code, err.code, code)
        }
    }

    @Test
    fun androidCodeUnknownIsRetryableWithoutBlankCode() {
        val err = GoogleSignInException.fromAndroidCode("") as GoogleSignInException
        assertTrue(err.retryable)
        assertNull(err.code)
    }

    @Test
    fun desktopCodeMappingSweep() {
        val cancelled = listOf("cancelled", "user_cancel", "access_denied")
        val retryable = listOf(
            "timeout",
            "browser",
            "network",
            "token",
            "empty_credential",
            "parse",
            "state",
        )
        val notRetryable = listOf("unsupported", "init_failed")

        for (code in cancelled) {
            assertIs<GoogleSignInCancelled>(GoogleSignInException.fromDesktopCode(code), code)
        }
        for (code in retryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromDesktopCode(code), code)
            assertTrue(err.retryable, code)
            assertEquals(code, err.code, code)
        }
        for (code in notRetryable) {
            val err = assertIs<GoogleSignInException>(GoogleSignInException.fromDesktopCode(code), code)
            assertFalse(err.retryable, code)
            assertEquals(code, err.code, code)
        }
    }

    @Test
    fun desktopCodeUnknownIsRetryableWithoutBlankCode() {
        val err = GoogleSignInException.fromDesktopCode("") as GoogleSignInException
        assertTrue(err.retryable)
        assertNull(err.code)
    }
}
