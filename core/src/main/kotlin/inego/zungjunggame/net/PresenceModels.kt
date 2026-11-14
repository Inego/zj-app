package inego.zungjunggame.net

enum class PresenceActivity {
    Idle,
    Waiting,
    Playing,
}

enum class QueueRounds {
    One,
    Two,
    Either,
}

enum class QueueChicken {
    Allowed,
    Disallowed,
    Either,
}

data class PresenceQueue(
    val minOtherHumans: Int,
    val rounds: QueueRounds,
    val chicken: QueueChicken,
)

data class PresenceTable(
    val id: String,
    val rounds: QueueRounds, // One or Two on a committed table, never Either
    val allowChicken: Boolean,
    val humans: Int,
    val bots: Int,
)

/**
 * The server is keeping this account out of matchmaking for a while.
 *
 * Reported so a refused **Find game** is explained rather than silently ignored. The server does
 * not enqueue the search, so without this the control would either sit on "Cancel search" waiting
 * for a match that cannot come, or look like a dead button.
 */
data class MatchmakingCooldown(
    /** Whole seconds left, server-side and already rounded up. */
    val secondsRemaining: Int,
    /** What earned it: `forfeit`, `reservation_ghost` or `presence_ghost`. */
    val reason: String,
)

data class PresenceSelf(
    val userId: String,
    val displayName: String,
    val activity: PresenceActivity,
    val queue: PresenceQueue? = null,
    val table: PresenceTable? = null,
    /** Latest finished match, including after forfeit. Same shape as GET /matches. */
    val lastMatch: MatchSummary? = null,
    /** Set while the table-integrity cooldown is delaying a search. */
    val matchmakingCooldown: MatchmakingCooldown? = null,
)

/**
 * Graceful server restart warning and countdown.
 */
data class PresenceServerShutdown(
    val phase: String,
    val remainingMs: Long,
    val message: String? = null,
)

data class PresenceSnapshot(
    val online: Int,
    val playing: Int,
    val waiting: Int,
    val self: PresenceSelf?,
    /** True when a waiting heartbeat was downgraded because starting level is unset. */
    val needsStartingLevel: Boolean = false,
    /** Server restart warning, countdown, and explanation. */
    val serverShutdown: PresenceServerShutdown? = null,
)
