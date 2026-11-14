package inego.zungjunggame.features.online.hub

import inego.zungjunggame.net.PresenceQueue
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.net.PresenceTable
import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.online.ShadowRankedStartState

/**
 * The Live tab's frame input: everything the tab body paints that is not the
 * player-set [OnlineHubState].
 *
 * Plain data with no closures, so a host builds it from the model snapshot and
 * the panel renders from it. [storedTableId] is the persisted `online.table`
 * reservation the body reads to decide whether an offline player can still
 * Rejoin the table they left (`resolvePrimaryAction`).
 */
data class OnlineHubView(
    /** The persisted `online.table` reservation; null when the player has none. */
    val storedTableId: String? = null,
    val signedInLabel: String? = null,
    val eloRating: Double? = null,
    val searching: Boolean = false,
    val searchElapsedSec: Int? = null,
    val searchInterrupted: Boolean = false,
    val seatedAt: PresenceTable? = null,
    val reserved: Boolean = false,
    val queue: PresenceQueue? = null,
    val minOtherHumans: Int = 1,
    val rounds: QueueRounds = QueueRounds.Either,
    val chicken: QueueChicken = QueueChicken.Either,
    val snapshot: PresenceSnapshot? = null,
    val stale: Boolean = false,
    val latencyMs: Int? = null,
    val prefsFrozen: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
    val notice: String? = null,
) {
    /**
     * Whether the shell offers **Rejoin** above the tab strip.
     *
     * Rejoin is a hub-level affordance, not a per-tab one: a table belongs to
     * neither tab, so the way back to it cannot live inside either body where a
     * single primary button has to choose between Find game, Cancel search and
     * Rejoin. The shell shows it above both tabs instead, which is also the only
     * place a Shadow Ranked attempt — whose whole home is the Shadow Ranked tab —
     * can be picked up again from.
     *
     * The offered cases are exactly the ones the Live primary used to resolve to
     * Rejoin, in the same order:
     *  - a search or a reserved table owns the primary (Cancel search), so no
     *    rejoin is offered alongside it;
     *  - a seat presence report **or** the persisted `online.table` id is a table
     *    to return to. The stored id is what covers an offline player, whose
     *    presence has not answered yet;
     *  - a signed-out body offers nothing: rejoin needs the account that owns the
     *    seat. The gate only matters in the cold-start window where a persisted
     *    hub is restored before the session — the hub itself is signed-in only.
     *
     * A server shutdown is deliberately *not* a veto: Rejoin returns to a table
     * the player is already sitting at, whereas the primary's Disabled state
     * stopped the player queueing for a *new* game on a dying server. If the table
     * is really gone the rejoin surfaces the existing shutdown notice.
     */
    val rejoinAvailable: Boolean
        get() = signedInLabel != null &&
            !searching &&
            !reserved &&
            (seatedAt != null || !storedTableId.isNullOrEmpty())
}

/**
 * The Shadow Ranked tab's frame input: the start lifecycle [start] for the
 * signed-in account.
 *
 * The attempts list is not here: it lives in its own card
 * ([inego.zungjunggame.features.challenges.ShadowRankedAttemptsOverlay]) whose
 * view is built from [ShadowRankedAttemptsClient][inego.zungjunggame.net.ShadowRankedAttemptsClient].
 *
 * Plain data: [ShadowRankedStarter][inego.zungjunggame.online.ShadowRankedStarter]
 * owns the lifecycle; the panel only paints what it is handed.
 */
data class ShadowRankedView(
    val signedIn: Boolean = false,
    val start: ShadowRankedStartState = ShadowRankedStartState.Idle,
)
