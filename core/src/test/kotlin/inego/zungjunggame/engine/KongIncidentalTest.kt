package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Win on Kong (9.2) and Robbing a Kong (9.3): legal choices, scoring, settlement.
 */
class KongIncidentalTest {

    @Test
    fun concealedKongSupplementHuIncludesWinOnKong() {
        val state = concealedKongReadyForWinOnSupplement()
        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.concealedKong(Tiles.EAST))

        assertTrue(state.lastDrawWasKongSupplement)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertEquals(liveBefore - 1, state.liveWallSize)

        val choices = state.enumerateChoices().asList()
        val hu = choices.firstOrNull { it.kind == ChoiceKind.Hu }
        assertTrue(hu != null, "expected Hu on kong supplement")
        assertTrue(hu.huScore() >= 10)
        assertTrue(choices.any { it.kind == ChoiceKind.Discard }, "Hu is optional; discards stay legal")

        state.applyChoice(hu)
        val details = Scoring.computeHandScoreDetails(state)
        assertTrue(details.components.any { it.id == PatternIds.WIN_ON_KONG && it.points == 10 })
        val deltas = state.computeScoreDeltas()
        // Self-draw: each of the other three pays full hand value.
        assertEquals(details.totalScore * 3, deltas[0])
        assertEquals(-details.totalScore, deltas[1])
    }

    @Test
    fun discardAfterKongClearsWinOnKongFlag() {
        val state = concealedKongReadyForWinOnSupplement()
        state.applyChoice(Choice.concealedKong(Tiles.EAST))
        assertTrue(state.lastDrawWasKongSupplement)
        val discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(discard)
        assertFalse(state.lastDrawWasKongSupplement)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
    }

    @Test
    fun winOnKongStacksWithFinalDrawWhenSupplementIsSeabed() {
        val state = concealedKongReadyForWinOnSupplement()
        state.liveWallSize = 1
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - 1
        state.wall[drawIndex] = Tiles.BAMBOO_3
        state.applyChoice(Choice.concealedKong(Tiles.EAST))
        assertEquals(0, state.liveWallSize)
        assertTrue(state.lastDrawWasKongSupplement)
        state.applyChoice(state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu })
        val ids = Scoring.computeHandScoreDetails(state).components.map { it.id }
        assertTrue(PatternIds.WIN_ON_KONG in ids)
        assertTrue(PatternIds.FINAL_DRAW in ids)
    }

    @Test
    fun promoteKongSkippedRobWindowWhenNobodyCanHu() {
        val state = promoterReadyToPromote(robberCanHu = false)
        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))
        assertFalse(state.kongRobWindow)
        assertEquals(PlayerState.PK_OPEN_KONG, state.players[0].pungsKongs[Tiles.DOT_5])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertTrue(state.lastDrawWasKongSupplement)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun promoteKongOpensRobWindowWhenAnotherSeatCanHu() {
        val state = promoterReadyToPromote(robberCanHu = true)
        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))

        assertTrue(state.kongRobWindow)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        assertEquals(PlayerState.PK_OPEN_PUNG, state.players[0].pungsKongs[Tiles.DOT_5])
        assertEquals(Tiles.DOT_5, state.lastDiscarded)
        assertEquals(0, state.lastDiscarder)
        assertEquals(1, state.currentPlayer)

        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertEquals(setOf(ChoiceKind.Pass, ChoiceKind.Hu), kinds)
        assertFalse(ChoiceKind.Pung in kinds)
        assertFalse(ChoiceKind.Chow in kinds)
    }

    @Test
    fun robKongHuScoresNinePointThreeAndPaysAsDiscardWin() {
        val state = promoterReadyToPromote(robberCanHu = true)
        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))
        val hu = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu }
        // Seats 2 and 3 have not bid yet; apply Hu then they must still bid... 
        // Rank is collected after all three have reacted. Seat 1 Hu, 2 pass, 3 pass.
        state.applyChoice(hu)
        assertEquals(2, state.currentPlayer)
        state.applyChoice(Choice.pass())
        assertEquals(3, state.currentPlayer)
        state.applyChoice(Choice.pass())

        assertTrue(state.isTerminal())
        assertEquals(1, state.currentPlayer)
        assertTrue(state.kongRobWindow)
        assertEquals(PlayerState.PK_OPEN_PUNG, state.players[0].pungsKongs[Tiles.DOT_5])

        val details = Scoring.computeHandScoreDetails(state)
        assertTrue(details.components.any { it.id == PatternIds.ROBBING_A_KONG && it.points == 10 })
        assertTrue(details.components.none { it.id == PatternIds.FINAL_DISCARD })
        assertTrue(details.components.none { it.id == PatternIds.CHICKEN_HAND })

        val deltas = state.computeScoreDeltas()
        assertEquals(details.totalScore * 3, deltas[1])
        // Win on discard, value typically > 25 with all triplets — discarder (seat 0) pays extra.
        assertTrue(deltas[0] < 0)
        assertEquals(0, deltas[1] + deltas[0] + deltas[2] + deltas[3])
    }

    @Test
    fun robWindowAllPassCompletesKongAndDrawsSupplement() {
        val state = promoterReadyToPromote(robberCanHu = true)
        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))
        assertTrue(state.kongRobWindow)
        // Seat 1 can Hu but passes; 2 and 3 pass.
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertFalse(state.kongRobWindow)
        assertEquals(PlayerState.PK_OPEN_KONG, state.players[0].pungsKongs[Tiles.DOT_5])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertTrue(state.lastDrawWasKongSupplement)
        assertEquals(liveBefore - 1, state.liveWallSize)
        assertTrue(state.discards[0].isEmpty(), "robbed-or-passed promote tile is not a river discard")
    }

    @Test
    fun concealedKongIsNotRobbed() {
        val state = concealedKongReadyForWinOnSupplement()
        state.applyChoice(Choice.concealedKong(Tiles.EAST))
        assertFalse(state.kongRobWindow)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(PlayerState.PK_CONCEALED_KONG, state.players[0].pungsKongs[Tiles.EAST])
    }

    @Test
    fun claimedDiscardKongIsNotRobbed() {
        val state = GameState()
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40
        state.currentPlayer = 1
        state.wall[LIVE_WALL_DRAWABLE_SIZE - 40] = Tiles.CHAR_1
        state.players[1].concealed[Tiles.EAST] = 3
        state.players[1].activeConcealedTileCount = -1

        state.applyChoice(Choice.kong(Tiles.EAST))
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertFalse(state.kongRobWindow)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(1, state.currentPlayer)
        assertEquals(PlayerState.PK_OPEN_KONG, state.players[1].pungsKongs[Tiles.EAST])
        assertTrue(state.lastDrawWasKongSupplement)
    }

    /**
     * Seat 0: 4× East + 111p 222p 33s 99s. Next wall tile is 3-bamboo (completes
     * the third triplet after the concealed kong).
     */
    private fun concealedKongReadyForWinOnSupplement(): GameState {
        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        state.allowChickenHands = false
        state.liveWallSize = 40
        state.lastDrawnPlayer = 0
        state.lastDrawnTile = Tiles.EAST
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        state.wall[drawIndex] = Tiles.BAMBOO_3
        val hand = state.players[0]
        hand.concealed[Tiles.EAST] = 4
        hand.concealed[Tiles.DOT_1] = 3
        hand.concealed[Tiles.DOT_2] = 3
        hand.concealed[Tiles.BAMBOO_3] = 2
        hand.concealed[Tiles.BAMBOO_9] = 2
        hand.activeConcealedTileCount = -1
        return state
    }

    /**
     * Seat 0 promotes an open pung of 5-dot. When [robberCanHu], seat 1 is waiting
     * on that tile; otherwise seat 1 has a junk hand.
     */
    private fun promoterReadyToPromote(robberCanHu: Boolean): GameState {
        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        state.allowChickenHands = false
        state.liveWallSize = 40
        state.lastDrawnPlayer = 0
        state.lastDrawnTile = Tiles.DOT_5
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        state.wall[drawIndex] = Tiles.CHAR_9

        val you = state.players[0]
        you.pungsKongs[Tiles.DOT_5] = PlayerState.PK_OPEN_PUNG
        you.pungKongCalledFrom[Tiles.DOT_5] = 2
        you.concealed[Tiles.DOT_5] = 1
        you.concealed[Tiles.CHAR_1] = 2
        you.concealed[Tiles.CHAR_2] = 2
        you.concealed[Tiles.CHAR_3] = 2
        you.concealed[Tiles.BAMBOO_8] = 2
        you.concealed[Tiles.WHITE] = 2
        you.activeConcealedTileCount = -1

        val robber = state.players[1]
        if (robberCanHu) {
            // 111p 222p 333s 444s pair wait 5p — all triplets on the 5p.
            robber.concealed[Tiles.DOT_1] = 3
            robber.concealed[Tiles.DOT_2] = 3
            robber.concealed[Tiles.BAMBOO_3] = 3
            robber.concealed[Tiles.BAMBOO_4] = 3
            robber.concealed[Tiles.DOT_5] = 1
        } else {
            robber.concealed[Tiles.CHAR_1] = 1
            robber.concealed[Tiles.CHAR_9] = 1
            robber.concealed[Tiles.DOT_1] = 1
            robber.concealed[Tiles.DOT_9] = 1
            robber.concealed[Tiles.BAMBOO_1] = 1
            robber.concealed[Tiles.BAMBOO_9] = 1
            robber.concealed[Tiles.EAST] = 1
            robber.concealed[Tiles.SOUTH] = 1
            robber.concealed[Tiles.WEST] = 1
            robber.concealed[Tiles.NORTH] = 1
            robber.concealed[Tiles.RED] = 1
            robber.concealed[Tiles.GREEN] = 1
            robber.concealed[Tiles.WHITE] = 1
        }
        robber.activeConcealedTileCount = -1
        return state
    }
}
