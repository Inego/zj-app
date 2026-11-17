package inego.zungjunggame.net

import inego.zungjunggame.persist.AuthPersistedSession
import inego.zungjunggame.platform.LiveSession
import inego.zungjunggame.state.StateSlot

/** What another tab's write to `auth.session` means for this client. */
enum class StoredSessionChange {
    /** The store still holds this client's token (the profile may have moved). */
    Unchanged,
    /** Same account, newer refresh token: adopt it, and take the announced JWT. */
    Rotated,
    /** The store now belongs to a different account: this client leaves the session. */
    Replaced,
    /** The store holds no session: another tab signed out. */
    Removed,
}

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
 * Because the key is shared, the browser serializes the rotation across tabs
 * ([inego.zungjunggame.platform.SessionCoordination]) and every tab adopts what
 * the others write: [reloadStored] takes the stored token before one is
 * presented, and [applyRefreshed] refuses to overwrite a store another tab has
 * moved on. The winner also announces the access JWT minted with its rotation,
 * so a sibling keeps working instead of exchanging the token it just adopted
 * ([adoptLiveAccess]). A tab that finds the key belongs to a different account
 * [forget]s its session without deleting that account's login.
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
     * True while this client may write `auth.session`.
     *
     * Cleared by [signOut] and [forget]: a client that has ended its session
     * has no claim on the key — a later sign-out here must not delete another
     * account's login, and a sign-in another tab performs must not sign this
     * one back in. Set again by anything that adopts a stored session ([load])
     * or establishes one ([signIn]).
     */
    private var ownsStore: Boolean = true

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
        ownsStore = true
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
        ownsStore = true
        return persist()
    }

    fun updateUser(user: AuthUser) {
        this.user = user
        val landed = persist()
        lifecycleLog?.invoke(
            "updateUser: token=${tokenId(refreshToken)} STORE=${if (landed) "ok" else "lost"}",
        )
    }

    /**
     * End this client's session and remove it from the shared key — unless the
     * key now holds a session this client never adopted.
     *
     * Signing out also **relinquishes the store**: this client has no session to
     * follow, so a sign-in another tab performs later must not sign this tab
     * back in as that account. A reload ([load]) or a sign-in here adopts the
     * store again.
     *
     * A sign-out that lands after another account took the key (a slow expiry
     * racing a sign-in) leaves that login alone: removing it would delete a
     * session this client never held.
     */
    fun signOut() {
        val previous = tokenId(refreshToken)
        val stored = sessionSlot.get()
        val foreign = stored != null && stored.user.id != user?.id
        token = null
        refreshToken = null
        user = null
        // A removal is as durable as a write: a token left behind on disk is one
        // the next boot presents already burned.
        val landed = if (foreign) false else persist()
        // Nothing to follow once signed out: a later write another tab makes is
        // not this client's session.
        ownsStore = false
        lifecycleLog?.invoke(
            if (foreign) {
                "signOut: dropping token=$previous (store belongs to ${stored.user.id})"
            } else {
                "signOut: dropping token=$previous STORE=${if (landed) "ok" else "lost"}"
            },
        )
    }

    /**
     * Drop this client's session **without touching the shared store**.
     *
     * Used when `auth.session` was found to belong to a different account: this
     * tab leaves the session, but a later sign-out here must not delete the
     * other tab's login. A new sign-in adopts the store again.
     */
    internal fun forget() {
        val previous = tokenId(refreshToken)
        token = null
        refreshToken = null
        user = null
        ownsStore = false
        lifecycleLog?.invoke("forget: dropping token=$previous (store belongs to another tab)")
    }

    /**
     * Take the access JWT a sibling client minted for [live]'s refresh token.
     *
     * Only the client that wrote a rotation receives a JWT for the session row
     * it created; every sibling's JWT names a row the rotation just consumed,
     * so its next request is refused. The winner hands its JWT over and this
     * client keeps it in memory — the store already holds that refresh token,
     * so there is nothing to exchange and nothing to write.
     *
     * Refused when this client does not own the store, when the announcement is
     * for a token the store has already moved past, or when it belongs to
     * another account: in each case the JWT is not this session's.
     *
     * @return true when the in-memory access token is now [live]'s.
     */
    internal fun adoptLiveAccess(live: LiveSession): Boolean {
        if (!ownsStore) return false
        if (live.refreshToken != refreshToken) return false
        if (live.accessToken.isBlank()) return false
        if (user?.id != live.userId) return false
        token = live.accessToken
        lifecycleLog?.invoke("adopted live access token for ${tokenId(refreshToken)}")
        return true
    }

    /**
     * Re-read `auth.session` and classify what another client did to it.
     *
     * The store, not this client's memory, is the truth once a tab is shared:
     * a tab that waited out another tab's rotation must present the token that
     * tab wrote, never the one it remembers. A same-account rotation is adopted
     * as the refresh token, but the access JWT this client holds is not carried
     * over: the rotation consumed the session row it names, so the winner
     * announces the JWT that goes with the new token instead
     * ([adoptLiveAccess]).
     */
    fun reloadStored(): StoredSessionChange {
        if (!ownsStore) return StoredSessionChange.Unchanged
        val stored = sessionSlot.get()
        if (stored == null) {
            if (refreshToken == null && user == null) return StoredSessionChange.Unchanged
            lifecycleLog?.invoke("stored session removed")
            return StoredSessionChange.Removed
        }
        if (stored.refreshToken == refreshToken) {
            if (stored.user != user) {
                user = stored.user
                lifecycleLog?.invoke("stored profile updated user=${stored.user.id}")
            }
            return StoredSessionChange.Unchanged
        }
        val currentUser = user
        if (currentUser != null && stored.user.id != currentUser.id) {
            lifecycleLog?.invoke("stored session replaced user=${stored.user.id}")
            return StoredSessionChange.Replaced
        }
        val previous = tokenId(refreshToken)
        refreshToken = stored.refreshToken
        user = stored.user
        lifecycleLog?.invoke("adopted stored token: $previous -> ${tokenId(stored.refreshToken)}")
        return StoredSessionChange.Rotated
    }

    /** Restore a persisted refresh session with no access token yet. */
    internal fun applyPersisted(refreshToken: String, user: AuthUser) {
        this.token = null
        this.refreshToken = refreshToken
        this.user = user
    }

    /**
     * Commit the rotation of [presented] to [result], if the store still holds
     * [presented].
     *
     * The compare-and-swap is what keeps two writers from fighting over one
     * key. Inside the browser's refresh lock no other tab rotates, but a
     * **sign-in or sign-out** bypasses the lock (it is a local user action, not
     * a rotation): if either landed during the round trip, the store no longer
     * holds [presented], and writing this result would resurrect a session the
     * player just ended or overwrite the account they just signed in as.
     * Returning false leaves convergence to the caller, which re-reads the
     * store and follows it. A rotation that loses this race is simply dropped —
     * never revoked — because the only other writer is a legitimate sibling
     * rotation, and revoking its family would sign the player out for good.
     *
     * The previous token is consumed on the server the moment this response is
     * sent, so a rotation that does not reach the container leaves the client
     * holding a token that is already burned — and once the chain has moved two
     * rotations past what the store holds, the server reads the replay as theft
     * and revokes the family. The line this emits is the verdict on that
     * question: `STORE=ok` is the container's answer, not a memory read-back.
     */
    internal fun applyRefreshed(result: AuthLogin, presented: String): Boolean {
        if (!ownsStore) {
            lifecycleLog?.invoke("rotated: dropped, this client no longer owns the session")
            return false
        }
        val stored = sessionSlot.get()
        if (stored == null) {
            lifecycleLog?.invoke("rotated: dropped, session removed while rotating")
            return false
        }
        if (stored.refreshToken != presented) {
            lifecycleLog?.invoke(
                "rotated: dropped, store advanced to ${tokenId(stored.refreshToken)} " +
                    "while presenting ${tokenId(presented)}",
            )
            return false
        }
        val previous = tokenId(refreshToken)
        val landed = adopt(result)
        val new = tokenId(refreshToken)
        lifecycleLog?.invoke(
            if (landed) "rotated: $previous -> $new STORE=ok"
            else "rotated: $previous -> $new STORE=lost",
        )
        return true
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
    private fun persist(): Boolean {
        // A client that left the shared session has no standing to write it: in
        // particular a late sign-out must not delete the account that replaced
        // it. Nothing landed here, so the verdict is false, not "already fine".
        if (!ownsStore) return false
        return sessionSlot.commit(snapshot())
    }

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
