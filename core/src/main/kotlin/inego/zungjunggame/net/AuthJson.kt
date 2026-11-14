package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Request/response JSON for the Google challenge/exchange, refresh, and
 * `POST /me`. No Gdx app required (unit-testable).
 */
object AuthJson {
    fun refreshRequestBody(refreshToken: String): String =
        """{"refresh_token":${quote(refreshToken)}}"""

    fun googleChallengeRequestBody(clientId: String, verifierHash: String): String =
        """{"client_id":${quote(clientId)},"verifier_hash":${quote(verifierHash)}}"""

    fun googleExchangeRequestBody(verifier: String, idToken: String): String =
        """{"verifier":${quote(verifier)},"id_token":${quote(idToken)}}"""

    fun specialKeyRequestBody(key: String): String =
        """{"key":${quote(key)}}"""

    fun patchMeRequestBody(displayName: String): String =
        """{"display_name":${quote(displayName)}}"""

    fun startingLevelRequestBody(level: String): String =
        """{"level":${quote(level)}}"""

    fun parseLogin(body: String): AuthLogin {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty sign-in response")
        // Prefer access_token; `token` is an accepted alias of it.
        val token = optionalString(root, "access_token")
            ?: optionalString(root, "token")
            ?: throw IllegalArgumentException("missing access_token")
        val refreshToken = optionalString(root, "refresh_token")
        val userVal = root.get("user")
            ?: throw IllegalArgumentException("missing user")
        return AuthLogin(token = token, user = parseUser(userVal), refreshToken = refreshToken)
    }

    fun parseMe(body: String): AuthUser {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty me response")
        val userVal = root.get("user")
            ?: throw IllegalArgumentException("missing user")
        return parseUser(userVal)
    }

    fun parseGoogleChallenge(body: String): GoogleChallenge {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty challenge response")
        val nonce = optionalString(root, "nonce")
            ?: throw IllegalArgumentException("missing nonce")
        val expires = root.get("expires_in")
            ?: throw IllegalArgumentException("missing expires_in")
        if (expires.isNull) throw IllegalArgumentException("missing expires_in")
        return GoogleChallenge(nonce = nonce, expiresInSeconds = expires.asLong())
    }

    fun parseErrorMessage(body: String?, fallback: String): String {
        if (body.isNullOrBlank()) return fallback
        return try {
            val root = JsonReader().parse(body) ?: return fallback
            root.getString("error", fallback)
        } catch (_: Exception) {
            fallback
        }
    }

    /** The server's machine-readable error code (`NOT_FOUND`, …), or null. */
    fun parseErrorCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JsonReader().parse(body) ?: return null
            root.getString("code")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private fun optionalString(value: JsonValue, name: String): String? {
        val child = value.get(name) ?: return null
        if (child.isNull) return null
        return child.asString()
    }

    /** Private `/me` and sign-in body. Public table/presence parsers must not call this. */
    internal fun parseUser(value: JsonValue): AuthUser {
        val id = value.getString("id")
            ?: throw IllegalArgumentException("missing user.id")
        val displayName = value.getString("display_name")
            ?: throw IllegalArgumentException("missing user.display_name")
        val statuses = ArrayList<String>()
        val statusesValue = value.get("statuses")
        if (statusesValue != null && !statusesValue.isNull) {
            var child = statusesValue.child
            while (child != null) {
                statuses.add(child.asString())
                child = child.next
            }
        }
        val eloValue = value.get("elo_rating")
        val eloRating = if (eloValue == null || eloValue.isNull) null else eloValue.asDouble()
        val aliasCustomizedAt = optionalString(value, "alias_customized_at")
        val startingLevel = optionalString(value, "starting_level")
        val startingLevelSetAt = optionalString(value, "starting_level_set_at")
        return AuthUser(
            id = id,
            displayName = displayName,
            statuses = statuses,
            eloRating = eloRating,
            aliasCustomizedAt = aliasCustomizedAt,
            startingLevel = startingLevel,
            startingLevelSetAt = startingLevelSetAt,
        )
    }
}
