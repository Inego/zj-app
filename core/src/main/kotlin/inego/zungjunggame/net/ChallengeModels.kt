package inego.zungjunggame.net

/**
 * The lifecycle state of one of the player's Shadow Ranked attempts.
 *
 * Wire name retained: the client's UI calls an attempt Shadow Ranked.
 */
enum class ChallengeStatus {
    Started,
    Finished,
    Abandoned,
    Voided,
}

/**
 * One row of a finished Shadow Ranked attempt's score list: the pinned
 * bot, or a human who finished before this attempt's listing player.
 *
 * [name] is null for the bot; [isYou] marks the listing player's own row.
 */
data class ChallengeScoreRow(
    val name: String?,
    val score: Int,
    val isBot: Boolean,
    val isYou: Boolean,
)

/**
 * One of the player's Shadow Ranked attempts, for the hub's attempts list.
 * Wire name retained.
 *
 * [createdAt] is when the *challenge* (the pinned deal) was created — the first
 * player's play time — not this attempt's. [startedAt] is when this player
 * started, and is what "My attempts" shows.
 *
 * [players] is the running distinct-player count. [ratingDelta] is the net rating this
 * account has moved on the attempt so far. [scores] is present only once
 * this attempt finishes, because the bot total and the other players' totals stay hidden
 * until then.
 */
data class ChallengeEntry(
    val publicId: String,
    val rounds: String,
    val allowChicken: Boolean,
    val createdAt: String,
    val status: ChallengeStatus,
    val totalScore: Int?,
    val startedAt: String,
    val finishedAt: String?,
    val players: Int,
    val ratingDelta: Double,
    val scores: List<ChallengeScoreRow>,
)

/** The player's Shadow Ranked attempts, newest first, from `GET /challenges`. */
data class ChallengeList(
    val challenges: List<ChallengeEntry>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * One challenge's hand tables for every finished attempt, from
 * `GET /challenges/{publicId}/hands`. Order is the navigation order (the bot,
 * then humans by finish time) and is never re-sorted on the client.
 */
data class ChallengeHands(
    val publicId: String,
    val rounds: String,
    val attempts: List<AttemptHands>,
)

/** One finished attempt's Who row and its per-hand settlement table. */
data class AttemptHands(
    val seq: Int,
    val isBot: Boolean,
    val isYou: Boolean,
    /** Null for the bot; the display name for a human, including the viewer. */
    val name: String?,
    /** The rating snapshot, or null when the server had none. */
    val rating: Double?,
    /** Seat 0's match total. */
    val score: Int,
    /** Null when the server could not read this attempt's corpus. */
    val hands: List<HandScoreRowWire>?,
)

