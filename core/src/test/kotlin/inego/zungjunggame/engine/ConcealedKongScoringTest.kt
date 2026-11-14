package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Concealed Hand (1.2) must survive a **concealed kong**: the kong tiles are covered
 * face-down and are never exposed to the other players, so no open meld exists before
 * the win. An **open** kong does break it.
 *
 * Regression guard: `analyzeHand` used to count concealed kongs as melded sets, which
 * made a hand whose only meld was a concealed kong score without Concealed Hand.
 */
class ConcealedKongScoringTest {

    /** 123m 456m 789m 55p — eleven concealed tiles once the 5p is claimed. */
    private fun concealedBody(ps: PlayerState) {
        for (t in Tiles.CHAR_1..Tiles.CHAR_9) ps.concealed[t] = 1
        ps.concealed[Tiles.DOT_5] = 2
    }

    private fun points(details: HandScoreDetails): List<Pair<String, Int>> =
        details.components.map { it.id to it.points }

    @Test
    fun concealedKongIsTheOnlyMeldAndStillScoresConcealedHand() {
        val ps = PlayerState()
        concealedBody(ps)
        ps.pungsKongs[Tiles.EAST] = PlayerState.PK_CONCEALED_KONG

        val details = Scoring.computeHandScoreDetails(
            ps,
            playerIndex = 0,
            eastSeat = 0,
            allowChickenHands = false,
        )

        assertTrue(
            details.components.any { it.id == PatternIds.CONCEALED_HAND },
            "concealed kong must keep Concealed Hand; got ${points(details)}",
        )
        // Value Honor Sets 10 (East is the seat wind) + One Kong 5 + Nine-Tile Straight 40
        // + Concealed Hand 5 = 60.
        assertEquals(60, details.totalScore, "unexpected total: ${points(details)}")
        assertEquals(60, details.uncappedTotal)
    }

    @Test
    fun openKongBreaksConcealedHand() {
        val ps = PlayerState()
        concealedBody(ps)
        ps.pungsKongs[Tiles.EAST] = PlayerState.PK_OPEN_KONG

        val details = Scoring.computeHandScoreDetails(
            ps,
            playerIndex = 0,
            eastSeat = 0,
            allowChickenHands = false,
        )

        assertFalse(
            details.components.any { it.id == PatternIds.CONCEALED_HAND },
            "an exposed kong must break Concealed Hand; got ${points(details)}",
        )
        assertEquals(55, details.totalScore, "unexpected total: ${points(details)}")
    }

    @Test
    fun concealedKongAndOpenKongDifferOnlyByConcealedHand() {
        val concealed = PlayerState().also {
            concealedBody(it)
            it.pungsKongs[Tiles.EAST] = PlayerState.PK_CONCEALED_KONG
        }
        val open = PlayerState().also {
            concealedBody(it)
            it.pungsKongs[Tiles.EAST] = PlayerState.PK_OPEN_KONG
        }

        val closedScore = Scoring.computeHandScoreDetails(concealed, 0, 0, false)
        val openScore = Scoring.computeHandScoreDetails(open, 0, 0, false)

        assertEquals(
            PatternIds.CONCEALED_HAND,
            closedScore.components.first().id,
            "Concealed Hand should lead the component list for a concealed kong",
        )
        assertEquals(
            closedScore.totalScore,
            openScore.totalScore + 5,
            "difference must be exactly the 5-pt Concealed Hand",
        )
    }

    /**
     * The reported flow: win on a discarded tile that completes the final set, with an
     * earlier concealed kong and no open melds.
     */
    @Test
    fun winOnDiscardCompletingSetWithConcealedKongScoresConcealedHand() {
        val state = GameState()
        state.liveWallSize = 20 // not the final discard; avoids Final Discard / Blessing of Earth
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.DOT_5

        val winner = 1
        val ps = state.players[winner]
        ps.pungsKongs[Tiles.EAST] = PlayerState.PK_CONCEALED_KONG
        // 123m 456m 789m + 5p, claiming 5p to complete the pair.
        for (t in Tiles.CHAR_1..Tiles.CHAR_9) ps.concealed[t] = 1
        ps.concealed[Tiles.DOT_5] = 1

        val details = Scoring.computeHandScoreDetails(state, winner, includeClaimedDiscard = true)

        assertTrue(
            details.components.any { it.id == PatternIds.CONCEALED_HAND },
            "win on discard with a concealed kong must score Concealed Hand; " +
                "got ${points(details)}",
        )
        // Seat 1's seat wind is South, so the East kong is not a value honor set:
        // Concealed Hand 5 + One Kong 5 + Nine-Tile Straight 40 = 50.
        assertEquals(50, details.totalScore, "unexpected total: ${points(details)}")
    }
}
