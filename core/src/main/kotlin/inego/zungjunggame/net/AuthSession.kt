package inego.zungjunggame.net

import inego.zungjunggame.persist.AuthPersistedSession
import inego.zungjunggame.state.StateSlot

/**
 * Current signed-in user, access token, and optional refresh token.
 *
 * The access token is memory-only on every platform. The refresh token and the
 * profile it belongs to live in the `auth.session` key, which is marked secret:
 * the platform [inego.zungjunggame.state.SecretBox] seals it (Android Keystore,
 * desktop OS credential store), and on a platform with no secret storage — the
 * browser — the store keeps it in the clear, which is that platform's accepted
 * behaviour. A restart or page reload restores the session by rotating the token
 * at boot. Tabs on one origin therefore share one account, which is accepted in
 * exchange for durable sign-in. [signOut] clears the key.
 *
 * The key is **committed**, not batched: every write below publishes to the
 * container before it returns. The reason is the rotation. The server consumes
 * the presented token the moment it answers, so a rotation that only reached
 * backend memory is a credential the next boot presents already burned — and a
 * token two rotations behind is what the server reads as theft, which revokes
 * the whole family and costs the player a sign-in nobody can explain. The
 * login is therefore durable here, where the event happens, rather than at the
 * runtime's next flush.
 */
class AuthSession(
    private val sessionSlot: StateSlot<AuthPersistedSession>,
) {
    var token: String? = null
        private set
    var refreshToken: String? = null
        private set
    var user: AuthUser? = null
        private set

    /**
     * Diagnostic sink for the refresh-token lifecycle. Set by the app layer; a
     * no-op here. Lines carry [tokenId] only — never the token or a usable hash
     * of it — so the browser console and the desktop log can be compared against
     * the server's `auth_sessions` rows without leaking a credential.
     */
    var lifecycleLog: ((String) -> Unit)? = null

    val isSignedIn: Boolean
        get() = token != null && user != null

    /**
     * Adopt a persisted refresh session into memory (no access token yet).
     *
     * [persisted] is handed in rather than read here because the cold start reads
     * every artifact once (`AppState`) and must have the credential in memory
     * before the online-table probe, which only a signed-in session can pass.
     * Later callers read their own key — the session owns `auth.session`.
     */
    fun load(persisted: AuthPersistedSession?) {
        if (persisted == null) {
            lifecycleLog?.invoke("load: nothing usable in the store")
            return
        }
        applyPersisted(persisted.refreshToken, persisted.user)
        lifecycleLog?.invoke("load: token=${tokenId(persisted.refreshToken)} user=${persisted.user.id}")
    }

    /**
     * Adopt a signed-in session and persist it, reporting the write. Returns
     * whether it landed durably — see [persist]. Callers that treat the session
     * as durable (a rotation, in particular) must check it.
     */
    fun signIn(result: AuthLogin): Boolean {
        val landed = adopt(result)
        val stored = if (landed) "ok" else "lost"
        lifecycleLog?.invoke(
            "signIn: token=${tokenId(refreshToken)} user=${result.user.id} STORE=$stored",
        )
        return landed
    }

    /**
     * Adopt a session and persist it, with no log of its own.
     *
     * [applyRefreshed] is a different event from a sign-in and reports itself, so
     * it takes the session through here rather than through [signIn] — otherwise
     * one rotation emits two lines saying the same thing.
     */
    private fun adopt(result: AuthLogin): Boolean {
        token = result.token
        refreshToken = result.refreshToken
        user = result.user
        return persist()
    }

    fun updateUser(user: AuthUser) {
        this.user = user
        val landed = persist()
        lifecycleLog?.invoke(
            "updateUser: token=${tokenId(refreshToken)} STORE=${if (landed) "ok" else "lost"}",
        )
    }

    fun signOut() {
        val previous = tokenId(refreshToken)
        token = null
        refreshToken = null
        user = null
        // A removal is as durable as a write: a token left behind on disk is one
        // the next boot presents already burned.
        val landed = sessionSlot.commit(null)
        lifecycleLog?.invoke(
            "signOut: dropping token=$previous STORE=${if (landed) "ok" else "lost"}",
        )
    }

    /** Restore a persisted refresh session with no access token yet. */
    internal fun applyPersisted(refreshToken: String, user: AuthUser) {
        this.token = null
        this.refreshToken = refreshToken
        this.user = user
    }

    /**
     * Rotate to [result]'s refresh token and commit the rotation.
     *
     * The previous token is consumed on the server the moment this response is
     * sent, so a rotation that does not reach the container leaves the client
     * holding a token that is already burned — and once the chain has moved two
     * rotations past what the store holds, the server reads the replay as theft
     * and revokes the family. The line this emits is the verdict on that
     * question: `STORE=ok` is the container's answer, not a memory read-back.
     * The session stays in memory either way, because the store being one
     * rotation behind is exactly what the server's replay recovery forgives.
     */
    internal fun applyRefreshed(result: AuthLogin) {
        val previous = tokenId(refreshToken)
        val landed = adopt(result)
        val new = tokenId(refreshToken)
        lifecycleLog?.invoke(
            if (landed) "rotated: $previous -> $new STORE=ok"
            else "rotated: $previous -> $new STORE=lost",
        )
    }

    /**
     * Write the current session and make the write durable, reporting whether
     * it did.
     *
     * The verdict is the backend's: the store commits and reads the bytes back
     * from the container, so "lost" means the credential really is not on disk
     * (a seal that refused, a container that would not publish). A null
     * snapshot — signed out — is a durable removal.
     */
    private fun persist(): Boolean = sessionSlot.commit(snapshot())

    private fun snapshot(): AuthPersistedSession? {
        val refresh = refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val signed = user ?: return null
        return AuthPersistedSession(refreshToken = refresh, user = signed)
    }

    companion object {
        /**
         * Salt for [tokenId]: the preferences file the auth session used to live
         * in. Kept so ids in a log stay comparable across the move to `auth.session`.
         */
        private const val TOKEN_ID_SALT = "zj-game-auth"

        /**
         * Short opaque id for a refresh token: FNV-1a over `salt + token`,
         * 8 hex chars. Stable for one token, useless as a credential, and small
         * enough to sit in a console line so "was this the token we rotated away
         * from?" can be read off the log.
         *
         * Hand-rolled rather than [java.security.MessageDigest] because TeaVM's
         * runtime does not ship that class — the Wasm build fails at compile time
         * with "Class java.security.MessageDigest was not found".
         */
        fun tokenId(token: String?): String {
            if (token.isNullOrBlank()) return "none"
            var hash = -3750763034362895579L // 0xcbf29ce484222325
            for (byte in (TOKEN_ID_SALT + token).toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 1099511628211L
            }
            return (hash ushr 32).toString(16).padStart(8, '0')
        }
    }
}
