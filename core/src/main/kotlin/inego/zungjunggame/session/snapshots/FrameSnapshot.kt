package inego.zungjunggame.session.snapshots

import inego.zungjunggame.session.LiveHand
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.ui.stack.SessionKind

/**
 * One coherent post-command view of the active session for settlement, update,
 * and render. Built by [inego.zungjunggame.session.SessionRegistry.frame].
 */
data class FrameSnapshot(
    val kind: SessionKind,
    val playHost: PlayHost,
    val onlineHost: OnlinePlayHost?,
    val liveHand: LiveHand,
    val boardHosted: Boolean,
)
