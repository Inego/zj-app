package inego.zungjunggame.runtime

import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.stack.Stack

/** Immutable pairing of the navigation stack and the active session view. */
data class AppSnapshot(val stack: Stack, val session: SessionSnapshot)
