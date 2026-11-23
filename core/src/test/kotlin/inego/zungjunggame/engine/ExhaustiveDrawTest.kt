package inego.zungjunggame.engine

import inego.zungjunggame.session.table.drawnTileForDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End of hand on an exhausted wall.
 *
 * The wall running out is **not** the end of the hand: the seat that drew the
 * **seabed** (the last live wall tile) must still discard the **riverbed**, and the
 * other seats may win on it (Final Discard, 9.1.2) or claim it for pung/chow. The hand
 * ends only once a discard goes unclaimed with nothing left to draw.
 *
 * Regression guard for the bug where the seabed tile stayed parked in the drawer's
 * hand for the whole table to see, because the hand was declared over the moment the
 * wall emptied — which also made 9.1.1 and 9.1.2 unreachable in real play.
 */
class ExhaustiveDrawTest {

    // --- the seabed turn is a real turn ---------------------------------

    @Test
    fun seabedDrawDoesNotEndHand() {
        val state = seabedTurnState()

        assertEquals(0, state.liveWallSize)
        assertFalse(state.isTerminal(), "wall exhaustion alone must not end the hand")
        assertFalse(state.handEnded)
        assertEquals(0, state.seabedPlayer)
        assertEquals(0, state.lastDrawnPlayer)
    }

    @Test
    fun seabedPlayerIsOfferedSelfDrawAndDiscards() {
        val state = seabedTurnState()
        val choices = state.enumerateChoices().asList()

        assertTrue(
            choices.any { it.kind == ChoiceKind.Hu },
            "self-draw on the seabed must be offered, got $choices",
        )
        assertTrue(
            choices.any { it.kind == ChoiceKind.Discard },
            "the seabed player must still be able to discard, got $choices",
        )
    }

    @Test
    fun seabedTileIsTheOneParkedForDisplay() {
        val state = seabedTurnState()
        assertEquals(Tiles.DOT_9, drawnTileForDisplay(state, 0))
    }

    @Test
    fun selfDrawOnSeabedScoresFinalDraw() {
        val state = seabedTurnState()
        val hu = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu }

        state.applyChoice(hu)

        val ids = Scoring.computeHandScoreDetails(state).components.map { it.id }
        assertTrue(PatternIds.FINAL_DRAW in ids, "expected Final Draw, got $ids")
        assertTrue(state.isTerminal())
    }

    // --- the riverbed discard ------------------------------------------

    @Test
    fun discardingTheSeabedTileOpensTheRiverbed() {
        val state = seabedTurnState()
        state.applyChoice(Choice.discard(Tiles.DOT_9))

        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        assertEquals(0, state.liveWallSize)
        assertEquals(0, state.lastDiscarder)
        assertFalse(state.isTerminal(), "the riverbed must stay open for claims")
        assertEquals(
            emptyList(),
            state.discards[0],
            "the pending discard is not in the river until everyone passes",
        )
    }

    @Test
    fun winOnRiverbedScoresFinalDiscard() {
        val state = riverbedWindowState()
        val hu = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu }

        state.applyChoice(hu)
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertTrue(state.isTerminal())
        assertEquals(1, state.winnerSeat)
        val ids = Scoring.computeHandScoreDetails(state).components.map { it.id }
        assertTrue(PatternIds.FINAL_DISCARD in ids, "expected Final Discard, got $ids")
    }

    @Test
    fun unclaimedRiverbedEndsHandAsDraw() {
        val state = seabedTurnState()
        state.applyChoice(Choice.discard(Tiles.DOT_9))
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertTrue(state.isTerminal())
        assertTrue(state.handEnded)
        assertEquals(0, state.handScore, "an exhaustive draw scores nothing")
        assertEquals(Tiles.DOT_9, state.discards[0].last(), "riverbed is finalized into the river")
        assertEquals(-1, drawnTileForDisplay(state, 0))
    }

    /**
     * Only the seabed player's own discard is the riverbed. After a pung on the
     * riverbed the hand continues, and the claimer's later discard must not score
     * 9.1.2 a second time.
     */
    @Test
    fun discardAfterClaimingRiverbedIsNotFinalDiscard() {
        val state = seabedTurnState(witnessWaitsOn = Tiles.BAMBOO_9)
        state.applyChoice(Choice.discard(Tiles.DOT_9))

        // Seat 1 passes on the riverbed (it waits on 9b); seat 2 pungs it.
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pung(Tiles.DOT_9))
        state.applyChoice(Choice.pass())

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(2, state.currentPlayer)
        assertEquals(0, state.seabedPlayer, "the seabed seat does not move on a claim")

        // Seat 2 discards the tile seat 1 waits on: a win, but not on the riverbed.
        // Reaction order runs from the discarder clockwise (3, 0, 1).
        state.applyChoice(Choice.discard(Tiles.BAMBOO_9))
        assertEquals(3, state.currentPlayer)
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())
        val hu = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu }
        state.applyChoice(hu)

        assertTrue(state.isTerminal())
        assertEquals(1, state.winnerSeat)
        val ids = Scoring.computeHandScoreDetails(state).components.map { it.id }
        assertFalse(PatternIds.FINAL_DISCARD in ids, "only the riverbed scores 9.1.2, got $ids")
    }

    // --- fixtures -------------------------------------------------------

    /**
     * Seat 0 has just drawn the seabed: the wall held a single 9p, which completes
     * four triplets and a pair, and seat 3's discard went unclaimed.
     *
     * Seat 1 is the witness, waiting on [witnessWaitsOn] (a lone 9p or 9b). Seat 2
     * holds two 9p so it can pung the riverbed, and a spare 9b to discard after.
     */
    private fun seabedTurnState(witnessWaitsOn: Int = Tiles.DOT_9): GameState {
        val state = GameState()
        state.eastSeat = 0
        state.allowChickenHands = false
        state.liveWallSize = 1
        state.wall[LIVE_WALL_DRAWABLE_SIZE - 1] = Tiles.DOT_9

        tripletsAndSingle(state.players[0], Tiles.DOT_9)
        tripletsAndSingle(state.players[1], witnessWaitsOn)
        state.players[2].concealed[Tiles.DOT_9] = 2
        state.players[2].concealed[Tiles.BAMBOO_9] = 1
        for (player in state.players) player.activeConcealedTileCount = -1

        // Everything passes on seat 3's discard, so seat 0 draws the seabed.
        state.currentPlayer = 0
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 3
        state.lastDiscarded = Tiles.EAST
        state.applyResolution(ReactionResolution.AllPass)
        return state
    }

    /** As above, but seat 0 has taken its seabed turn and discarded the 9p riverbed. */
    private fun riverbedWindowState(): GameState {
        val state = seabedTurnState()
        state.applyChoice(Choice.discard(Tiles.DOT_9))
        return state
    }

    /** `111p 222p 111s 222s` plus a lone [single] — a one-tile wait on that tile. */
    private fun tripletsAndSingle(hand: PlayerState, single: Int) {
        hand.concealed[Tiles.DOT_1] = 3
        hand.concealed[Tiles.DOT_2] = 3
        hand.concealed[Tiles.BAMBOO_1] = 3
        hand.concealed[Tiles.BAMBOO_2] = 3
        hand.concealed[single] = 1
    }
}
