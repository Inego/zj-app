package inego.zungjunggame.session.snapshots

import inego.zungjunggame.ui.stack.SessionKind

/** Immutable view of the active session, derived from route and attached sources. */
data class SessionSnapshot(
    val kind: SessionKind,
    val boardHosted: Boolean,
    val table: TableSnapshot?,
    val hand: HandSnapshot?,
    val onlineTableId: String? = null,
)
