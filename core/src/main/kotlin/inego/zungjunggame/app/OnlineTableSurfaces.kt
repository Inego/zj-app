package inego.zungjunggame.app

import inego.zungjunggame.online.OnlineTablePort
import inego.zungjunggame.ui.stack.OnlineTab

/**
 * Where a live online table goes, and where leaving it returns.
 *
 * The coordinator owns *that* a table is live ([OnlineTablePort]); this answers
 * *where*. [tableJoined] records [origin] on the `ONLINE_TABLE` route — the stack
 * is the origin tab's record — and puts the table above the `ONLINE_HUB` base
 * context. [tableExited] removes the table and returns the hub to the tab the
 * route carries, so a live table lands on Live and a Shadow Ranked table on
 * Shadow Ranked.
 *
 * The hub's tab is its own player-set state, so selecting it is delegated to
 * [selectHubTab] — the online effect that owns the hub route's state. The
 * composition root wraps this with the board-side effects of a table change.
 */
internal class OnlineTableSurfaces(
    /** Resolved at call time: the screen authority is built after this port. */
    private val transitions: () -> ScreenTransitions,
    private val selectHubTab: (OnlineTab) -> Unit,
) : OnlineTablePort {

    override fun tableJoined(origin: OnlineTab) {
        transitions().onlineTable(origin)
    }

    override fun tableExited() {
        // Read the origin before the route leaves: the args are its record.
        val transitions = transitions()
        val origin = transitions.tableOrigin()
        transitions.dismissTable()
        if (origin != null) selectHubTab(origin)
    }
}
