package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sequential first-class declare/Keep for each four-of-a-kind before discards.
 */
class ConcealedKongGateTest {

    @Test
    fun singleFourOfAKindOffersDeclareAndKeepOnly() {
        val state = actOnHandWithFours(Tiles.EAST)
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertEquals(setOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep), kinds)
        assertEquals(Tiles.EAST, state.nextPendingConcealedKongTile())
        val kong = state.enumerateChoices().asList().first { it.kind == ChoiceKind.ConcealedKong }
        val keep = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Keep }
        assertEquals(Tiles.EAST, kong.tile())
        assertEquals(Tiles.EAST, keep.tile())
    }

    @Test
    fun keepThenOffersDiscards() {
        val state = actOnHandWithFours(Tiles.EAST)
        state.applyChoice(Choice.keep(Tiles.EAST))
        assertTrue(state.declinedConcealedKong[Tiles.EAST])
        assertEquals(-1, state.nextPendingConcealedKongTile())
        val choices = state.enumerateChoices().asList()
        assertTrue(choices.any { it.kind == ChoiceKind.Discard })
        assertFalse(choices.any { it.kind == ChoiceKind.ConcealedKong })
        assertFalse(choices.any { it.kind == ChoiceKind.Keep })
    }

    @Test
    fun multipleFoursSequentialLowestTileFirst() {
        val state = actOnHandWithFours(Tiles.WEST, Tiles.EAST)
        // EAST < WEST
        assertEquals(Tiles.EAST, state.nextPendingConcealedKongTile())
        state.applyChoice(Choice.keep(Tiles.EAST))
        assertEquals(Tiles.WEST, state.nextPendingConcealedKongTile())
        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertEquals(setOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep), kinds)
        assertEquals(
            Tiles.WEST,
            state.enumerateChoices().asList().first { it.kind == ChoiceKind.Keep }.tile(),
        )
        state.applyChoice(Choice.keep(Tiles.WEST))
        assertTrue(state.enumerateChoices().asList().any { it.kind == ChoiceKind.Discard })
        assertEquals(-1, state.nextPendingConcealedKongTile())
    }

    @Test
    fun declareClearsDeclinedAndDrawsSupplement() {
        val state = actOnHandWithFours(Tiles.EAST)
        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.concealedKong(Tiles.EAST))
        assertEquals(0, state.players[0].concealed[Tiles.EAST])
        assertEquals(PlayerState.PK_CONCEALED_KONG, state.players[0].pungsKongs[Tiles.EAST])
        assertEquals(liveBefore - 1, state.liveWallSize)
        assertFalse(state.declinedConcealedKong.any { it })
        assertEquals(DecisionType.ActOnHand, state.decisionType)
    }

    /**
     * Concealed kong → supplement completes a different three-of-a-kind to four →
     * first-class gate again (declare/Keep) before any discard. Native flow,
     * same as the initial four-of-a-kind gate (AI / MC / UI all use enumerateChoices).
     */
    @Test
    fun declareSupplementCompletingAnotherTripletReopensKongGate() {
        val state = GameState()
        state.decisionType = DecisionType.ActOnHand
        state.currentPlayer = 0
        state.liveWallSize = 40
        // Next wall draw is SOUTH — the 4th of a triplet already in hand.
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        for (i in 0 until WALL_SIZE) state.wall[i] = Tiles.CHAR_1
        state.wall[drawIndex] = Tiles.SOUTH

        val ps = state.players[0]
        for (t in ps.concealed.indices) ps.concealed[t] = 0
        ps.concealed[Tiles.EAST] = 4   // declare this first
        ps.concealed[Tiles.SOUTH] = 3  // + supplement → 4
        // 7 more tiles to reach 14
        ps.concealed[Tiles.CHAR_2] = 3
        ps.concealed[Tiles.CHAR_3] = 3
        ps.concealed[Tiles.CHAR_4] = 1
        ps.activeConcealedTileCount = -1
        ps.ensureActiveConcealedTiles()

        assertEquals(Tiles.EAST, state.nextPendingConcealedKongTile())
        state.applyChoice(Choice.concealedKong(Tiles.EAST))

        assertEquals(4, state.players[0].concealed[Tiles.SOUTH])
        assertEquals(Tiles.SOUTH, state.lastDrawnTile)
        assertEquals(0, state.lastDrawnPlayer)
        assertEquals(Tiles.SOUTH, state.nextPendingConcealedKongTile())

        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertEquals(setOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep), kinds)
        assertEquals(
            Tiles.SOUTH,
            state.enumerateChoices().asList().first { it.kind == ChoiceKind.ConcealedKong }.tile(),
        )
        assertFalse(state.enumerateChoices().asList().any { it.kind == ChoiceKind.Discard })

        // Can chain: declare the new concealed kong, stay ActOnHand for yet another gate or discard.
        state.applyChoice(Choice.concealedKong(Tiles.SOUTH))
        assertEquals(PlayerState.PK_CONCEALED_KONG, state.players[0].pungsKongs[Tiles.SOUTH])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
    }

    @Test
    fun keepUndoRestoresDeclinedFlag() {
        val state = actOnHandWithFours(Tiles.EAST)
        val undo = state.applyChoiceWithUndo(Choice.keep(Tiles.EAST))
        assertTrue(state.declinedConcealedKong[Tiles.EAST])
        state.undoChoice(undo)
        assertFalse(state.declinedConcealedKong[Tiles.EAST])
        assertEquals(Tiles.EAST, state.nextPendingConcealedKongTile())
    }

    @Test
    fun discardClearsDeclinedFlags() {
        val state = actOnHandWithFours(Tiles.EAST)
        state.applyChoice(Choice.keep(Tiles.EAST))
        val discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(discard)
        assertFalse(state.declinedConcealedKong.any { it })
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
    }

    @Test
    fun noGateWhenWallEmpty() {
        val state = actOnHandWithFours(Tiles.EAST)
        state.liveWallSize = 0
        assertEquals(-1, state.nextPendingConcealedKongTile())
        assertTrue(state.enumerateChoices().asList().any { it.kind == ChoiceKind.Discard })
        assertFalse(state.enumerateChoices().asList().any { it.kind == ChoiceKind.ConcealedKong })
    }

    private fun actOnHandWithFours(vararg fourTiles: Int): GameState {
        val state = GameState()
        state.decisionType = DecisionType.ActOnHand
        state.currentPlayer = 0
        state.liveWallSize = 40
        // Minimal wall so kong draw works.
        for (i in 0 until WALL_SIZE) state.wall[i] = Tiles.CHAR_1
        val ps = state.players[0]
        for (t in ps.concealed.indices) ps.concealed[t] = 0
        var remaining = 14
        for (t in fourTiles) {
            ps.concealed[t] = 4
            remaining -= 4
        }
        check(remaining >= 0)
        // Pad with singles so the hand has 14 tiles.
        var pad = Tiles.CHAR_1
        while (remaining > 0) {
            while (pad in fourTiles.toSet() || ps.concealed[pad] > 0) {
                pad++
                check(pad < NUM_TILE_TYPES)
            }
            val take = minOf(3, remaining) // avoid creating another four accidentally
            ps.concealed[pad] = take
            remaining -= take
            pad++
        }
        ps.activeConcealedTileCount = -1
        ps.ensureActiveConcealedTiles()
        return state
    }
}
