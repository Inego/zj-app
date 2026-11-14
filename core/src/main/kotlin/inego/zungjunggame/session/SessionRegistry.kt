package inego.zungjunggame.session

import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.snapshots.FrameSnapshot
import inego.zungjunggame.session.snapshots.HandSnapshot
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.ui.stack.Stack

/**
 * Single derived-[SessionKind] authority.
 *
 * The context comes from the router stack: the topmost layer that declares one
 * (a board, a lesson, an online table) is what the app is doing, and a stack with
 * none above the floor is the menu. [kind] is always derived; it is never set.
 * An attached online host still wins, because a live table is an online session
 * whatever the stack is in the middle of restoring.
 *
 * Hosts and the live hand are read through lazy providers, so a host change is
 * visible on the same frame it happens and nothing has to notify the registry.
 */
class SessionRegistry(
    private val stack: () -> Stack,
    private val localHostSource: () -> PlayHost?,
    private val tutorialHostSource: () -> PlayHost?,
    private val onlineHostSource: () -> PlayHost?,
    private val handSource: () -> LiveHand?,
    private val onlineTableIdSource: () -> String? = { null },
) {
    val onlineHost: PlayHost? get() = onlineHostSource()

    /** Online host when present, else tutorial, else the local fallback host. */
    val activeHost: PlayHost? get() = onlineHostSource() ?: tutorialHostSource() ?: localHostSource()

    /** The host that currently owns the board, falling back to the local host. */
    val playHost: PlayHost
        get() = activeHost ?: error("No active session host is attached")

    val liveHand: LiveHand
        get() = handSource() ?: error("No live hand is attached")

    val boardHosted: Boolean
        get() = when (kind) {
            SessionKind.Online -> onlineHostSource() != null
            SessionKind.Tutorial -> handSource()?.active == true
            SessionKind.FreePlay -> handSource()?.let { it.active && it.state != null } == true
            SessionKind.Menu -> false
        }

    /** Derived session kind: the topmost context layer, or [SessionKind.Menu]. */
    val kind: SessionKind
        get() {
            if (onlineHostSource() != null) return SessionKind.Online
            return stack().entries.asReversed()
                .firstNotNullOfOrNull { it.spec.contextKind }
                ?: SessionKind.Menu
        }

    /**
     * True when a context owns the display — a board, a lesson, an online table —
     * so an account surface must not cover it. Derived from the same [kind] the
     * rest of the app reads, so the boot's ordering rule and the account flow
     * cannot disagree.
     */
    val sessionOwnsDisplay: Boolean get() = kind != SessionKind.Menu

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        kind = kind,
        boardHosted = boardHosted,
        table = activeHost?.snapshot,
        hand = handSource()?.let { HandSnapshot.from(it) },
        onlineTableId = onlineTableIdSource(),
    )

    fun frame(): FrameSnapshot {
        val host = activeHost ?: error("No active session host is attached")
        val live = handSource() ?: error("No live hand is attached")
        return FrameSnapshot(
            kind = kind,
            playHost = host,
            onlineHost = onlineHostSource() as? OnlinePlayHost,
            liveHand = live,
            boardHosted = boardHosted,
        )
    }
}
