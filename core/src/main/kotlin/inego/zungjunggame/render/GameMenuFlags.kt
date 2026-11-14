package inego.zungjunggame.render

import inego.zungjunggame.session.hosts.MatchPhase

/**
 * Which optional rows the in-game menu may offer for the current frame.
 *
 * The menu is a pure function of the session ([MenuContext]) plus these flags, so the
 * flags themselves live here as a pure function too — one that a plain JVM test can
 * call, unlike the GL render path that consumes it.
 *
 * One rule, read off the session rather than re-derived per call site: a control only
 * makes sense while the match is live. A finished or aborted match must not offer
 * Forfeit, because it would do nothing (see [MatchPhase]). The score table is the
 * exception — after the match ends it is *more* useful, since it holds the final
 * totals.
 */
internal data class GameMenuFlags(
    val showScoreTable: Boolean,
    val showForfeit: Boolean,
)

/**
 * @param boardUp a local board is hosting a game (free play mid-game)
 * @param onlineTableUp an online table is hosted, live or finished
 * @param phase phase of the match behind that table
 */
internal fun gameMenuFlags(
    boardUp: Boolean,
    onlineTableUp: Boolean,
    phase: MatchPhase,
): GameMenuFlags {
    val matchRow = boardUp || onlineTableUp
    val live = !phase.isOver
    return GameMenuFlags(
        showScoreTable = matchRow,
        showForfeit = onlineTableUp && live,
    )
}
