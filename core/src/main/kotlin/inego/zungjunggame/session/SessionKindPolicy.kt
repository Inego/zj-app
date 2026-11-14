package inego.zungjunggame.session

import inego.zungjunggame.ui.stack.SessionKind

/**
 * What each [SessionKind] permits the session domain to do.
 *
 * The kind itself is navigation vocabulary — the stack's own answer to "what
 * context are we in" (`ui/stack/SessionKind.kt`). These are the policy questions
 * the session domain hangs off it.
 *
 * **Free-play save:** only [SessionKind.FreePlay] may write the `session.freeplay`
 * key; the composition root derives the `GameSession.persist` gate from
 * [allowsFreePlayPersist]. [SessionKind.Tutorial] persists mid-lesson via the
 * `session.tutorial` key and must not overwrite free-play mid-hand.
 */

/** True when the `session.freeplay` key may be written. */
fun SessionKind.allowsFreePlayPersist(): Boolean = this == SessionKind.FreePlay

/** Score table overlay: live free-play board, or any online table. */
fun SessionKind.allowsScoreTable(liveHandActive: Boolean): Boolean = when (this) {
    SessionKind.FreePlay -> liveHandActive
    SessionKind.Online -> true
    else -> false
}
