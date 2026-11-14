package inego.zungjunggame.platform

import com.badlogic.gdx.utils.JsonReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Desktop Google OAuth helpers: PKCE S256, authorize URL, loopback callback
 * parse, token-response `id_token` extract. No network I/O here.
 *
 * The nonce is placed on the authorize URL unchanged — hashing it would fail the
 * server's nonce check.
 */
internal object DesktopOAuth {
    const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    // `email` on top of `openid`: the ID token must carry the verified address
    // the server keeps for notification mail. With `openid`
    // alone there is no `email` claim and the account is never mailable.
    const val SCOPE = "openid email"
    const val HTML_SUCCESS =
        "<!doctype html><meta charset=\"utf-8\"><title>zj-game</title>" +
            "<p>Signed in. You can close this window and return to the game.</p>"
    const val HTML_CANCELLED =
        "<!doctype html><meta charset=\"utf-8\"><title>zj-game</title>" +
            "<p>Sign-in cancelled. You can close this window.</p>"
    const val HTML_FAILED =
        "<!doctype html><meta charset=\"utf-8\"><title>zj-game</title>" +
            "<p>Sign-in failed. You can close this window and try again in the game.</p>"

    private val unreserved = Regex("^[A-Za-z0-9\\-._~]+$")

    fun pkceVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun pkceChallengeS256(verifier: String): String {
        require(verifier.length in 43..128 && unreserved.matches(verifier)) {
            "PKCE verifier must be 43–128 unreserved characters"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return base64Url(digest)
    }

    fun randomState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun authorizeUrl(
        clientId: String,
        redirectUri: String,
        nonce: String,
        state: String,
        codeChallenge: String,
    ): URI {
        val query = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPE,
            "nonce" to nonce,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "prompt" to "select_account",
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return URI("$AUTHORIZE_URL?$query")
    }

    fun parseCallback(query: String, expectedState: String): Callback {
        val params = parseQuery(query)
        val state = params["state"].orEmpty()
        if (state.isBlank() || state != expectedState) {
            return Callback.Failed("state")
        }
        val error = params["error"].orEmpty()
        if (error.isNotBlank()) {
            return if (error == "access_denied") {
                Callback.Cancelled
            } else {
                Callback.Failed(error)
            }
        }
        val code = params["code"].orEmpty()
        if (code.isBlank()) return Callback.Failed("empty_credential")
        return Callback.Code(code)
    }

    fun parseIdToken(body: String): String? {
        return try {
            val root = JsonReader().parse(body) ?: return null
            root.getString("id_token", null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Token-endpoint form body. `client_secret` is included whenever it is set:
     * a Google **Desktop app** client is not a public client, so the exchange is
     * rejected with `invalid_request: client_secret is missing` without it —
     * PKCE and the loopback `redirect_uri` do not replace it.
     */
    fun tokenForm(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
    ): String = buildList {
        add("client_id" to clientId)
        if (clientSecret.isNotBlank()) add("client_secret" to clientSecret)
        add("redirect_uri" to redirectUri)
        add("grant_type" to "authorization_code")
        add("code" to code)
        add("code_verifier" to codeVerifier)
    }.joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawName = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val name = dec(rawName)
            if (name.isEmpty() || name in out) continue
            out[name] = dec(rawValue)
        }
        return out
    }

    fun queryParam(uri: URI, name: String): String? = parseQuery(uri.rawQuery.orEmpty())[name]

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun dec(value: String): String =
        URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    sealed class Callback {
        data class Code(val code: String) : Callback()
        data object Cancelled : Callback()
        data class Failed(val code: String) : Callback()

        val html: String
            get() = when (this) {
                is Code -> HTML_SUCCESS
                Cancelled -> HTML_CANCELLED
                is Failed -> HTML_FAILED
            }

        val httpStatus: Int
            get() = when (this) {
                is Code, Cancelled -> 200
                is Failed -> 400
            }
    }
}
