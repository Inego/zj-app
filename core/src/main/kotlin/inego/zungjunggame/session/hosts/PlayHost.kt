package inego.zungjunggame.session.hosts

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.session.snapshots.TableSnapshot
import inego.zungjunggame.session.table.TableViewState

/**
 * Status of the host connection (local free play / tutorial vs online table).
 */
enum class ConnectionStatus {
    Local,
    Connecting,
    Connected,
    Reconnecting,
    /** Automatic retries are exhausted; the player must choose what to do. */
    ReconnectRequired,
    Disconnected,
}

/**
 * Open actionable choice window for the human seat.
 */
data class ActionableDecision(
    val decisionId: String,
    val isDiscard: Boolean,
    val isClaim: Boolean,
    val isActOnHandPanel: Boolean,
    val choices: ChoiceList,
    val deadlineMs: Long? = null,
)

/**
 * Client interface to a game host (local free play, tutorial, or online server).
 *
 * Provides a privacy-preserving [tableView], tracks open [myDecision] prompts,
 * and allows submitting decisions without directly mutating a local board engine.
 */
interface PlayHost {
    /** Immutable read model for this frame. Hosts may override to enrich it. */
    val snapshot: TableSnapshot get() = TableSnapshot.from(this)

    val tableView: TableViewState?
    val myDecision: ActionableDecision?
    val decisionTimeLeftMs: Long?
    /** Remaining per-hand slack bank at prompt time; null when the seat is untimed. */
    val decisionSlackMs: Long? get() = null
    /** Free think time for the open decision; null when the seat is untimed. */
    val decisionBaseMs: Long? get() = null
    /** Active server shutdown phase ("warning" or "final"); null when no restart is pending. */
    val shutdownPhase: String? get() = null
    /** Remaining milliseconds until announced restart deadline; null when not draining. */
    val shutdownRemainingMs: Long? get() = null
    /** Optional operator message attached to the shutdown notice. */
    val shutdownMessage: String? get() = null
    val connectionStatus: ConnectionStatus
    val humanSeat: Int

    /** 1-based hand number of the active or just-completed hand. */
    val currentHandNumber: Int get() = 1

    /** Total hands in a finite match (e.g. 4, 8, 16), or null when uncapped. */
    val maxHands: Int? get() = null

    /** True when patternless hands (chicken hands) are permitted to declare Hu. */
    val allowChickenHands: Boolean get() = false

    /**
     * Phase of the match this host is running. Hosts that cannot outlive a single
     * match (tutorial) leave it [MatchPhase.Live]; see [MatchPhase] for the rule.
     */
    val matchPhase: MatchPhase get() = MatchPhase.Live

    /** Advance online decision / reconnect / next-hand clocks. Not presentation. */
    fun advance(deltaSec: Float) {}

    /**
     * Submit an engine [Choice] for an open decision.
     * @return true if the choice was valid and accepted by the host
     */
    fun submitChoice(choice: Choice): Boolean

    /**
     * Submit a discard action for [tileType].
     * @return true if the discard was valid and accepted by the host
     */
    fun submitDiscard(tileType: Int, slotIndex: Int? = null, isDrawn: Boolean = false): Boolean

    /**
     * Request dealing the next hand in the session.
     */
    fun requestNextHand()

    /**
     * Leave the table / current game session.
     */
    fun leave()
}
