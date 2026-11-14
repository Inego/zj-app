package inego.zungjunggame.session.snapshots

import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.session.online.ConnectionFailure

/** Immutable point-in-time projection of a [inego.zungjunggame.session.hosts.PlayHost] table for features and render. */
data class TableSnapshot(
    val tableView: TableViewState?,
    val decision: ActionableDecision?,
    val decisionTimeLeftMs: Long?,
    val connectionStatus: ConnectionStatus,
    val humanSeat: Int,
    val currentHandNumber: Int,
    val maxHands: Int?,
    val matchPhase: MatchPhase,
    val allowChickenHands: Boolean = false,
    val shutdownPhase: String? = null,
    val shutdownRemainingMs: Long? = null,
    val shutdownMessage: String? = null,
    /** Online seat display names; empty for local hosts. */
    val playerNames: List<String> = emptyList(),
    /** Online bot flags aligned with [playerNames]; empty for local hosts. */
    val isBot: List<Boolean> = emptyList(),
    /** Active online settlement, or null while no hand-end card is showing. */
    val settlement: SettlementSnapshot? = null,
    /**
     * The Score Breakdown of a finished Shadow Ranked attempt
     * once the server sends it, or null for an ordinary table / an attempt that has
     * not finished.
     */
    val shadowRankedBreakdown: ServerMessageWire.ChallengeScoreBreakdown? = null,
    /** Terminal online connection failure, or null when connected / recoverable. */
    val connectionFailure: ConnectionFailure? = null,
    /** True when automatic recovery paused and the player must act. */
    val reconnectRequired: Boolean = false,
) {
    companion object {
        /** Adapts a live host to an immutable snapshot. */
        fun from(host: PlayHost): TableSnapshot = TableSnapshot(
            tableView = host.tableView,
            decision = host.myDecision,
            decisionTimeLeftMs = host.decisionTimeLeftMs,
            connectionStatus = host.connectionStatus,
            humanSeat = host.humanSeat,
            currentHandNumber = host.currentHandNumber,
            maxHands = host.maxHands,
            matchPhase = host.matchPhase,
            allowChickenHands = host.allowChickenHands,
            shutdownPhase = host.shutdownPhase,
            shutdownRemainingMs = host.shutdownRemainingMs,
            shutdownMessage = host.shutdownMessage,
        )
    }
}
