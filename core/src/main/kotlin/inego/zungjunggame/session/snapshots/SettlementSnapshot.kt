package inego.zungjunggame.session.snapshots

import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.net.PlayerRatingUpdateWire

/**
 * Immutable settlement inputs for the online hand-end score card.
 *
 * A pure value type (engine / net data only); the renderer maps it to its own
 * presentation types without touching [inego.zungjunggame.session.online.OnlinePlayHost].
 */
data class SettlementSnapshot(
    val winnerSeat: Int?,
    val details: HandScoreDetails,
    val scoreRows: List<HandScoreRow>,
    val runningTotals: List<Int>,
    val ratingUpdates: List<PlayerRatingUpdateWire>,
    /** RFC3339 UTC match start from `GameFinished`; null while the match is live. */
    val startedAt: String? = null,
    /** RFC3339 UTC finish from `GameFinished`. */
    val finishedAt: String? = null,
    val readyForNextHand: Boolean,
    val nextHandTimeLeftMs: Long?,
    /** True when the presentation sequencer has caught up and the card may show. */
    val sequencerCaughtUp: Boolean,
)
