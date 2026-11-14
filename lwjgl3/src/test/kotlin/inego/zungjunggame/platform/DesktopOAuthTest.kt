package inego.zungjunggame.platform

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopOAuthTest {
    @Test
    fun pkceChallengeMatchesRfc7636AppendixB() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DesktopOAuth.pkceChallengeS256(verifier),
        )
    }

    @Test
    fun generatedVerifierIsUnreservedAndChallenges() {
        val verifier = DesktopOAuth.pkceVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        val challenge = DesktopOAuth.pkceChallengeS256(verifier)
        assertTrue(challenge.isNotBlank())
        assertTrue('=' !in challenge)
    }

    @Test
    fun authorizeUrlPassesNonceUnchangedAndUsesPkceS256() {
        val uri = DesktopOAuth.authorizeUrl(
            clientId = "desktop.apps.googleusercontent.com",
            redirectUri = "http://127.0.0.1:12345",
            nonce = "c2-nonce-hex",
            state = "csrf-state",
            codeChallenge = "challenge-value",
        )
        assertEquals("accounts.google.com", uri.host)
        assertEquals("c2-nonce-hex", DesktopOAuth.queryParam(uri, "nonce"))
        assertEquals("http://127.0.0.1:12345", DesktopOAuth.queryParam(uri, "redirect_uri"))
        assertEquals("code", DesktopOAuth.queryParam(uri, "response_type"))
        assertEquals("openid email", DesktopOAuth.queryParam(uri, "scope"))
        assertEquals("S256", DesktopOAuth.queryParam(uri, "code_challenge_method"))
        assertEquals("challenge-value", DesktopOAuth.queryParam(uri, "code_challenge"))
        assertEquals("csrf-state", DesktopOAuth.queryParam(uri, "state"))
        assertEquals("desktop.apps.googleusercontent.com", DesktopOAuth.queryParam(uri, "client_id"))
    }

    @Test
    fun parseCallbackReturnsCodeWhenStateMatches() {
        val result = DesktopOAuth.parseCallback("code=abc%2Fdef&state=csrf", "csrf")
        val code = assertIs<DesktopOAuth.Callback.Code>(result)
        assertEquals("abc/def", code.code)
        assertEquals(200, result.httpStatus)
    }

    @Test
    fun parseCallbackAccessDeniedIsCancelled() {
        val result = DesktopOAuth.parseCallback("error=access_denied&state=csrf", "csrf")
        assertIs<DesktopOAuth.Callback.Cancelled>(result)
        assertEquals(DesktopOAuth.HTML_CANCELLED, result.html)
    }

    @Test
    fun parseCallbackRejectsStateMismatchAndMissingCode() {
        val mismatch = DesktopOAuth.parseCallback("code=abc&state=other", "csrf")
        val failedState = assertIs<DesktopOAuth.Callback.Failed>(mismatch)
        assertEquals("state", failedState.code)
        val missing = DesktopOAuth.parseCallback("state=csrf", "csrf")
        val failedCode = assertIs<DesktopOAuth.Callback.Failed>(missing)
        assertEquals("empty_credential", failedCode.code)
    }

    @Test
    fun parseIdTokenReadsTheFieldAndIgnoresTheRest() {
        val body = """{"access_token":"ya29.no","id_token":"eyJ.header.payload","token_type":"Bearer"}"""
        assertEquals("eyJ.header.payload", DesktopOAuth.parseIdToken(body))
        assertNull(DesktopOAuth.parseIdToken("""{"access_token":"only"}"""))
        assertNull(DesktopOAuth.parseIdToken("not-json"))
    }

    @Test
    fun tokenFormIncludesPkceVerifierAndExactRedirect() {
        val form = DesktopOAuth.tokenForm(
            clientId = "desktop.apps.googleusercontent.com",
            clientSecret = "desktop-secret",
            redirectUri = "http://127.0.0.1:9",
            code = "a/b",
            codeVerifier = "verifier",
        )
        val uri = URI("http://example/?$form")
        assertEquals("authorization_code", DesktopOAuth.queryParam(uri, "grant_type"))
        assertEquals("http://127.0.0.1:9", DesktopOAuth.queryParam(uri, "redirect_uri"))
        assertEquals("a/b", DesktopOAuth.queryParam(uri, "code"))
        assertEquals("verifier", DesktopOAuth.queryParam(uri, "code_verifier"))
        assertEquals("desktop.apps.googleusercontent.com", DesktopOAuth.queryParam(uri, "client_id"))
    }

    /**
     * Google rejects a Desktop app exchange without the secret
     * (`invalid_request: client_secret is missing`), so the secret is a real
     * form field, not optional decoration. An unset secret is omitted rather
     * than sent empty, which keeps that same error the one Google reports.
     */
    @Test
    fun tokenFormCarriesClientSecretAndOmitsItWhenUnset() {
        val withSecret = DesktopOAuth.tokenForm(
            clientId = "desktop.apps.googleusercontent.com",
            clientSecret = "desktop-secret",
            redirectUri = "http://127.0.0.1:9",
            code = "code",
            codeVerifier = "verifier",
        )
        assertEquals(
            "desktop-secret",
            DesktopOAuth.queryParam(URI("http://example/?$withSecret"), "client_secret"),
        )

        val withoutSecret = DesktopOAuth.tokenForm(
            clientId = "desktop.apps.googleusercontent.com",
            clientSecret = "  ",
            redirectUri = "http://127.0.0.1:9",
            code = "code",
            codeVerifier = "verifier",
        )
        assertNull(DesktopOAuth.queryParam(URI("http://example/?$withoutSecret"), "client_secret"))
    }
}
