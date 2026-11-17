package inego.zungjunggame.platform

/**
 * Platform coordination for the one stored session several clients may share.
 *
 * The browser is the only such platform: every tab of the origin has its own
 * in-memory client over one `localStorage` session, so a rotation one tab
 * performs is invisible to the others — and presenting a token another tab has
 * already rotated is what the server reads as theft. A tab therefore takes the
 * origin's refresh lock around **read stored token → exchange it → write the
 * result**, and listens for the writes the others make.
 *
 * The rotation itself cannot be made quiet: the server consumes the presented
 * token, so every other tab's access JWT names a row that is now spent and its
 * next request is refused. The tab that won the rotation therefore offers the
 * JWT it received to its siblings ([publishLiveSession]), and a sibling that
 * takes it ([watchLiveSessions]) keeps using the session instead of exchanging
 * the token it just adopted — which would consume that row in turn and make two
 * visible tabs rotate at heartbeat cadence.
 *
 * Desktop and Android run one client per store, so the default implementation
 * calls straight through and listens to nothing.
 */
interface SessionCoordination {
    /**
     * Run [critical] while holding the origin's exclusive refresh lock, or
     * immediately when the platform has no second client.
     *
     * [critical] is handed the release and **must** call it exactly once when
     * its work is done: the platform cannot know when the HTTP exchange inside
     * it ends. The lock is held across that exchange on purpose — the stored
     * token must not change between reading it and writing its successor.
     */
    fun withRefreshLock(critical: (release: () -> Unit) -> Unit)

    /**
     * Register [onChange] to run on the application thread when another client
     * rewrites or clears the stored session, or when this client wakes in a
     * state that may have missed that (a frozen tab, a bfcache restore).
     */
    fun watchForeignSessionChanges(onChange: () -> Unit)

    /**
     * Offer [session] to the origin's other clients.
     *
     * Called by the client that just wrote a rotation, so a sibling can keep
     * the access JWT that belongs to the token it adopted instead of
     * exchanging that token again. The access JWT is memory-only: this is the
     * only way it crosses a tab boundary, and it is never written to a store.
     */
    fun publishLiveSession(session: LiveSession) = Unit

    /**
     * Register [onSession] to run on the application thread when another
     * client publishes a session. A platform with one client per store never
     * calls it.
     */
    fun watchLiveSessions(onSession: (LiveSession) -> Unit) = Unit

    companion object {
        /** Installed by the platform launcher before the first client is built. */
        var factory: () -> SessionCoordination = { NoSessionCoordination }

        fun create(): SessionCoordination = factory()
    }
}

/**
 * A rotation one client performed, with the access JWT it received.
 *
 * The refresh token identifies which rotation this is: a sibling adopts the
 * JWT only while that token is what the store holds. The user id keeps a
 * stale announcement from another account out of this client's session.
 */
data class LiveSession(
    val refreshToken: String,
    val accessToken: String,
    val userId: String,
)

/** One client per store: the critical section runs directly, nothing to watch. */
object NoSessionCoordination : SessionCoordination {
    override fun withRefreshLock(critical: (release: () -> Unit) -> Unit) = critical { }

    override fun watchForeignSessionChanges(onChange: () -> Unit) = Unit
}
