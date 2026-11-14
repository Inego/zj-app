package inego.zungjunggame.net

/**
 * Private account profile from `/me` and the sign-in exchange.
 *
 * Other players never see this object; table seats use [PublicPlayerProfile] /
 * [TablePlayerWire].
 */
data class AuthUser(
    val id: String,
    val displayName: String,
    val statuses: List<String> = emptyList(),
    /** Server-side Elo rating; null when the server did not provide one. */
    val eloRating: Double? = null,
    /** ISO-8601 timestamp from `/me`; null until the player confirms or changes the default alias. */
    val aliasCustomizedAt: String? = null,
    /** Novice ('novice'), Amateur ('amateur'), or Strong ('strong'). */
    val startingLevel: String? = null,
    /** ISO-8601 timestamp from `/me`; null until starting level is chosen. */
    val startingLevelSetAt: String? = null,
) {
    /** Alias shown in own chrome, not an identifier. */
    fun identityLabel(): String = displayName.trim()

    /** True while the generated `user-<tag>` alias has not been confirmed or changed. */
    fun needsFirstLoginAlias(): Boolean = aliasCustomizedAt.isNullOrBlank()

    /** True when the player has not yet chosen their starting rating level. */
    fun needsStartingLevel(): Boolean = startingLevelSetAt.isNullOrBlank()
}

/** Identity other players may see. Never email, tokens, or a private account id. */
data class PublicPlayerProfile(
    val displayName: String,
)

data class AuthLogin(
    /** Opaque access token. Identity is [user] from the sign-in/`/me` body, not JWT claims. */
    val token: String,
    val user: AuthUser,
    /** Opaque refresh token. Null when the server (or a fixture) omitted it. */
    val refreshToken: String? = null,
)

/** One-time Google sign-in challenge from `POST /auth/google/challenge`. */
data class GoogleChallenge(
    /** Passed to Google; echoed back as the ID token `nonce`. */
    val nonce: String,
    /** Seconds until the challenge expires. */
    val expiresInSeconds: Long,
)


/**
 * UTF-16 units an alias field will hold. The write limits are [AliasPolicy]:
 * 16 graphemes and 16 columns. This stays larger so a name saved under the
 * previous 24-grapheme rule is not cut off when the editor opens.
 */
const val MAX_ALIAS = AliasPolicy.HOLD_LENGTH

/**
 * UTF-16 units the special-key field holds. The key is an opaque review
 * credential whose length the server does not publish, so the field allows more
 * than a display name while still bounding a paste.
 */
const val MAX_SPECIAL_KEY = 128