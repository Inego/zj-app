package inego.zungjunggame.online

import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.SessionKind

/**
 * Stack-membership surface for the away-during-online-match overlay.
 *
 * Production backs each call with the `away` route (push/remove), and the stack's
 * paint range owns what is up. The coordinator owns the away state and only
 * announces it, so no overlay `.visible` flag drives stack membership.
 */
interface OnlineAwayPort {
    fun show()
    fun hide()
}

/**
 * The live online table's screen and board surfaces.
 *
 * The coordinator owns *whether* a table is live; this port is how the app
 * answers. It replaces the three `Hooks` callbacks (`onOnlineTableJoined`,
 * `onOnlineTableExited`, and the `setSessionKind` that kept the floor in sync
 * from inside the coordinator):
 *
 * - [tableJoined] — a table just became live: put the table layer above the
 *   Online hub it was entered from, recording [origin] as the tab the table was
 *   opened from, close the local chrome, and drop hints/recommendations (an
 *   online table never offers a hint). The board side of the change is the
 *   app's and waits for the attach's own answer: an attach that starts the match
 *   deals its opening hand out, one that joins a running table rematerializes
 *   the position it finds. Called on enter and on rejoin of the same host, so
 *   the implementation must be idempotent.
 * - [tableExited] — the coordinator is no longer hosting a table: announce the
 *   settlement surfaces **and take the table's layer down**, so a
 *   coordinator-initiated exit (a remotely closed table, an abort, a server
 *   shutdown) has somewhere to land — the hub base context, on the tab the table
 *   was entered from. The explicit exits land on the hub right after and find
 *   it already up.
 *
 * Nothing here announces a [SessionKind]: the kind is derived from the stack by
 * `SessionRegistry`, and a port that also set it would be a second authority.
 */
interface OnlineTablePort {
    fun tableJoined(origin: OnlineTab)
    fun tableExited()
}
