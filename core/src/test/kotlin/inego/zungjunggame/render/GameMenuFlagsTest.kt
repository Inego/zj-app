package inego.zungjunggame.render

import inego.zungjunggame.session.hosts.MatchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-game menu must never offer a control the match can no longer accept.
 *
 * Regression: with an online match finished, Esc still offered **Forfeit game**.
 * Forfeit was a plain member of the online row list, so it survived the match.
 */
class GameMenuFlagsTest {

    @Test
    fun liveOnlineMatchOffersForfeit() {
        val flags = gameMenuFlags(boardUp = false, onlineTableUp = true, phase = MatchPhase.Live)
        assertEquals(
            GameMenuFlags(
                showScoreTable = true,
                showForfeit = true,
            ),
            flags,
        )
    }

    @Test
    fun finishedOnlineMatchOffersNoForfeit() {
        val flags = gameMenuFlags(boardUp = false, onlineTableUp = true, phase = MatchPhase.Finished)
        assertFalse(flags.showForfeit, "a finished match has nothing left to forfeit")
        assertTrue(flags.showScoreTable, "the score table is the final result — it stays")
    }

    @Test
    fun abortedOnlineMatchOffersNoForfeit() {
        val flags = gameMenuFlags(boardUp = false, onlineTableUp = true, phase = MatchPhase.Aborted)
        assertEquals(
            GameMenuFlags(
                showScoreTable = true,
                showForfeit = false,
            ),
            flags,
        )
    }

    @Test
    fun localGameOffersScoreTableButNeverForfeit() {
        val live = gameMenuFlags(boardUp = true, onlineTableUp = false, phase = MatchPhase.Live)
        assertFalse(live.showForfeit, "only an online table can be forfeited")
        assertTrue(live.showScoreTable)

        val complete = gameMenuFlags(boardUp = true, onlineTableUp = false, phase = MatchPhase.Finished)
        assertTrue(complete.showScoreTable)
    }

    @Test
    fun noMatchMeansNoMatchRows() {
        val flags = gameMenuFlags(boardUp = false, onlineTableUp = false, phase = MatchPhase.Live)
        assertEquals(
            GameMenuFlags(
                showScoreTable = false,
                showForfeit = false,
            ),
            flags,
        )
    }

    @Test
    fun matchPhaseIsOverForEveryTerminalPhase() {
        assertFalse(MatchPhase.Live.isOver)
        assertTrue(MatchPhase.Finished.isOver)
        assertTrue(MatchPhase.Aborted.isOver)
    }
}
