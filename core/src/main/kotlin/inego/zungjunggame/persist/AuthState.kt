package inego.zungjunggame.persist

import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

private const val MAX_STATUSES = 32

/**
 * The durable half of a signed-in session: the refresh token and the profile it
 * belongs to.
 *
 * The **access** token is deliberately absent — it is memory-only on every
 * platform, and persisting it was the only reason the old format had a "drop
 * this key" concept. The stored key is [StateKey.secret], so the whole value is
 * sealed by the platform secret box rather than just the token field.
 */
data class AuthPersistedSession(
    val refreshToken: String,
    val user: AuthUser,
)

/**
 * Versioned codec for [AuthPersistedSession].
 *
 * v2: refresh token, then the profile: id, display name, statuses, Elo (empty =
 * absent), alias-customized timestamp (empty = absent), starting level,
 * starting-level timestamp. v1 also carried the removed account login and user
 * type; the version bump discards it rather than migrating it (the planned
 * prefs purge), so a stored v1 session is dropped and the player signs in again.
 *
 * Every value in the profile is kept, so a cold start renders the account chrome
 * from the save rather than from whatever the network happens to have answered.
 */
object AuthPersistedSessionSchema : Schema<AuthPersistedSession> {
    override val version: Int = 2

    override fun encode(value: AuthPersistedSession): ByteArray {
        val user = value.user
        val writer = RecordWriter()
            .field(value.refreshToken)
            .field(user.id)
            .field(user.displayName)
            .int(user.statuses.size)
        user.statuses.forEach { writer.field(it) }
        writer.field(user.eloRating?.toString().orEmpty())
            .field(user.aliasCustomizedAt.orEmpty())
            .field(user.startingLevel.orEmpty())
            .field(user.startingLevelSetAt.orEmpty())
        return writer.build()
    }

    override fun decode(bytes: ByteArray): AuthPersistedSession? = try {
        val reader = RecordReader(bytes)
        val refreshToken = reader.field()
        val id = reader.field()
        val displayName = reader.field()
        val statusCount = reader.count(MAX_STATUSES)
        val statuses = List(statusCount) { reader.field() }
        val elo = reader.field()
        val aliasCustomizedAt = reader.field()
        val startingLevel = reader.field()
        val startingLevelSetAt = reader.field()
        when {
            refreshToken.isBlank() || id.isBlank() -> null
            !reader.atEnd() -> null
            else -> AuthPersistedSession(
                refreshToken = refreshToken,
                user = AuthUser(
                    id = id,
                    displayName = displayName.ifBlank { id },
                    statuses = statuses,
                    eloRating = elo.toDoubleOrNull(),
                    aliasCustomizedAt = aliasCustomizedAt.ifBlank { null },
                    startingLevel = startingLevel.ifBlank { null },
                    startingLevelSetAt = startingLevelSetAt.ifBlank { null },
                ),
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Persisted signed-in session: namespace `auth`, name `session`, sealed by the
 * platform secret box.
 */
val AuthPersistedSessionKey: StateKey<AuthPersistedSession> = StateKey(
    namespace = "auth",
    name = "session",
    schema = AuthPersistedSessionSchema,
    secret = true,
)

/**
 * Users whose first-login prompt this install has already answered.
 *
 * Local because keeping the generated `user-<tag>` alias sends no request, so
 * there is no server-side fact to read back.
 */
object DismissedFirstLoginPromptsSchema : Schema<Set<String>> {
    override val version: Int = 1

    override fun encode(value: Set<String>): ByteArray {
        val writer = RecordWriter().int(value.size)
        value.sorted().forEach { writer.field(it) }
        return writer.build()
    }

    override fun decode(bytes: ByteArray): Set<String>? = try {
        val reader = RecordReader(bytes)
        val size = reader.count(MAX_STATUSES)
        val ids = LinkedHashSet<String>(size)
        repeat(size) { ids += reader.field() }
        if (!reader.atEnd()) null else ids
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted first-login prompt dismissals: namespace `auth`, name `firstLogin`. */
val DismissedFirstLoginPromptsKey: StateKey<Set<String>> = StateKey(
    namespace = "auth",
    name = "firstLogin",
    schema = DismissedFirstLoginPromptsSchema,
    default = { emptySet() },
)
