package inego.zungjunggame.net

data class PlayerRatingUpdateWire(
    val seat: Int,
    val userId: String,
    val oldRating: Double,
    val newRating: Double,
    val delta: Double,
)

/**
 * One row of a finished Shadow Ranked attempt's Score Breakdown:
 * an opponent, both match totals, the opponent's rating snapshot, and what the
 * comparison did to the finisher's rating. [opponentName] is null for the pinned bot.
 *
 * Wire name kept: the client's UI type is
 * [inego.zungjunggame.features.score.ShadowRankedComparisonRow].
 */
data class ChallengeComparisonWire(
    val opponentName: String?,
    val playerScore: Int,
    val opponentScore: Int,
    val opponentRating: Double,
    /** The finisher's actual score against this opponent: 1 win, 0.5 tie, 0 loss. */
    val s: Double,
    /** This comparison's contribution to the finisher's rating, `(K/3)(S−E)`. */
    val delta: Double,
)

data class ChoiceWire(
    val kind: String,
    val payload: Int = 0,
)

data class MeldTileWire(
    val tileType: Int,
    val open: Boolean,
    val rotated: Boolean,
)

data class MeldWire(
    val tiles: List<MeldTileWire>,
)

/** Table-scoped public player. [name] is [PublicPlayerProfile.displayName]. */
data class TablePlayerWire(
    val seat: Int,
    val name: String,
    val isBot: Boolean,
) {
    fun publicProfile(): PublicPlayerProfile = PublicPlayerProfile(name)
}

data class ScoreComponentWire(
    val id: String,
    val points: Int,
    val formatArgs: List<Int> = emptyList(),
)

data class HandScoreRowWire(
    val handNumber: Int,
    val handValue: Int,
    val winnerSeat: Int? = null,
    val discarderSeat: Int? = null,
    val deltas: List<Int>,
    val runningTotals: List<Int>,
    val patterns: List<ScoreComponentWire> = emptyList(),
)

data class SeatSnapshotViewWire(
    val seat: Int,
    /** Public alias, not an account identifier. */
    val name: String = "",
    val isBot: Boolean = false,
    val concealedCount: Int,
    val concealedTiles: List<Int>?,
    val drawnTile: Int,
    val hasDrawnTile: Boolean,
    val melds: List<MeldWire>,
    val river: List<Int>,
    val seatWindTile: Int,
)

/**
 * One seat's bid on a resolved reaction window. Claims are public by the rules of
 * play, so the list is table state rather than a leak — every seat that bid is
 * named, not only the one that won.
 */
data class ClaimBidderWire(val seat: Int, val kind: String)

sealed class PresentationEventWire {
    data class Draw(val seat: Int, val tile: Int? = null) : PresentationEventWire()
    data class Discard(
        val seat: Int,
        val tile: Int,
        val slotIndex: Int? = null,
        val isDrawn: Boolean = false,
    ) : PresentationEventWire()
    data class Claim(
        val seat: Int,
        val sourceSeat: Int,
        val kind: String,
        val tiles: List<Int>,
        /**
         * Every seat that bid on the resolved window, omitted when none did
         * (an uncontested claim) or when the server predates it.
         */
        val bidders: List<ClaimBidderWire> = emptyList(),
    ) : PresentationEventWire()

    /**
     * A reaction window resolved to a Hu — a window outcome, not a hand end.
     * Settlement's [HandEnd] is the only producer of that, and it carries the
     * scores, so the two never announce the same instant.
     */
    data class ReactionHu(
        val seat: Int,
        val sourceSeat: Int,
        val tiles: List<Int>,
        val bidders: List<ClaimBidderWire> = emptyList(),
    ) : PresentationEventWire()
    data class Kong(val seat: Int, val kind: String, val tiles: List<Int>) : PresentationEventWire()
    data class HandEnd(val winnerSeat: Int? = null, val winSource: Int? = null) : PresentationEventWire()
}

data class SeatSnapshotWire(
    val handNumber: Int,
    val mySeat: Int,
    val eastSeat: Int,
    val liveWallSize: Int,
    val currentPlayer: Int,
    val decisionType: String,
    val lastDiscarder: Int?,
    val lastDiscarded: Int?,
    val isPendingDiscard: Boolean,
    val isKongRobWindow: Boolean,
    val handScore: Int,
    val winnerSeat: Int?,
    val winSource: Int?,
    val isTerminal: Boolean,
    val scoreDeltas: List<Int>?,
    val seats: List<SeatSnapshotViewWire>,
    val events: List<PresentationEventWire> = emptyList(),
)

sealed class ServerMessageWire {
    data class TableJoined(
        val tableId: String,
        val seat: Int,
        val rounds: String,
        val allowChicken: Boolean,
        val players: List<TablePlayerWire> = emptyList(),
        val gameId: String = "",
        val scoreRows: List<HandScoreRowWire> = emptyList(),
        /**
         * The table was already running (a hand dealt) when this connection
         * attached: the snapshot that follows is its position, not the opening
         * deal. False on a server that predates the field, which reads as a fresh
         * entry — the client then plays the deal as it always did.
         */
        val running: Boolean = false,
    ) : ServerMessageWire()

    data class SeatSnapshotMsg(
        val snapshot: SeatSnapshotWire,
    ) : ServerMessageWire()

    data class DecisionPrompt(
        val decisionId: Long,
        val decisionType: String,
        val choices: List<ChoiceWire>,
        val deadlineMs: Long?,
        val slackMs: Long? = null,
        val baseMs: Long? = null,
        val msUntilThinkStarts: Long? = null,
        val msUntilTimeout: Long? = null,
    ) : ServerMessageWire()

    data class DecisionTimeLeft(
        val decisionId: Long,
        val decisionType: String,
        val remainingMs: Long,
    ) : ServerMessageWire()

    data class ChoiceAcknowledged(
        val requestId: String,
    ) : ServerMessageWire()

    data class ChoiceRejected(
        val requestId: String,
        val reason: String,
    ) : ServerMessageWire()

    data class HandSettlement(
        val handNumber: Int,
        val handScore: Int,
        val winnerSeat: Int?,
        val deltas: List<Int>,
        val runningTotals: List<Int>,
        val patterns: List<ScoreComponentWire> = emptyList(),
    ) : ServerMessageWire()

    data class NextHandPrompt(
        val handNumber: Int,
        val deadlineMs: Long?,
        val ready: Boolean = false,
    ) : ServerMessageWire()

    data class NextHandTimeLeft(
        val remainingMs: Long,
    ) : ServerMessageWire()

    data class GameFinished(
        val runningTotals: List<Int>,
        val ratingUpdates: List<PlayerRatingUpdateWire> = emptyList(),
        /** RFC3339 UTC instant the match began; null on a server that predates the stamp. */
        val startedAt: String? = null,
        /** RFC3339 UTC instant the last hand settled. */
        val finishedAt: String? = null,
    ) : ServerMessageWire()

    /**
     * The Score Breakdown of a finished Shadow Ranked attempt.
     * [publicId] is the display form (`7K3H-M9PQ`); the bot is the comparison with no
     * name. It opens the hand-end card on finish and is the only place the bot's stored
     * match total is revealed.
     *
     * Wire name kept: the client's UI type is
     * [inego.zungjunggame.features.score.ShadowRankedComparison].
     */
    data class ChallengeScoreBreakdown(
        val publicId: String,
        val totalScore: Int,
        val ratingBefore: Double,
        val ratingAfter: Double,
        /** This finish's effect on the finisher. The You row's Δ. */
        val ratingDelta: Double,
        val comparisons: List<ChallengeComparisonWire>,
    ) : ServerMessageWire()

    data class TableAborted(
        val reason: String,
    ) : ServerMessageWire()

    data class ServerShutdown(
        val phase: String,
        val remainingMs: Long,
        val message: String? = null,
    ) : ServerMessageWire()

    object ServerShutdownWithdrawn : ServerMessageWire()

    object Pong : ServerMessageWire()
}

sealed class ClientMessageWire {
    data class SubmitChoice(
        val tableId: String,
        val handId: Int,
        val decisionId: Long,
        val requestId: String,
        val choice: ChoiceWire,
        val slotIndex: Int? = null,
        val isDrawn: Boolean? = null,
    ) : ClientMessageWire()

    data class ReadyForNextHand(
        val tableId: String,
    ) : ClientMessageWire()

    data class LeaveGame(
        val tableId: String,
    ) : ClientMessageWire()

    data class SetAway(
        val away: Boolean,
    ) : ClientMessageWire()

    object Ping : ClientMessageWire()
}

