package inego.zungjunggame.engine

import inego.zungjunggame.render.seats.PlayerAreaLayout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrawnTileDisplayTest {
    @Test
    fun dealerOpeningHasNoParkedDraw() {
        val state = GameState.generateRandom(Random(42), eastSeat = 0)
        assertEquals(14, state.players[0].concealed.sum())
        // Opening 14 are all dealt together — no right-side drawn slot / glow.
        assertEquals(-1, state.lastDrawnTile)
        assertEquals(-1, state.lastDrawnPlayer)

        val (sorted, parked) =
            PlayerAreaLayout.expandHandWithDrawn(state.players[0], drawnTile = -1)
        assertEquals(-1, parked)
        assertEquals(14, sorted.size)
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i] >= sorted[i - 1])
        }
    }

    @Test
    fun wallDrawParksAndDiscardClearsLastDrawn() {
        val state = GameState.generateRandom(Random(7), eastSeat = 0)
        assertEquals(-1, state.lastDrawnTile)

        // Dealer discards; others pass → next seat draws from the wall.
        val openingDiscard =
            state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(openingDiscard)
        while (state.decisionType == DecisionType.ReactToDiscard) {
            state.applyChoice(Choice.pass())
        }

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        val drawer = state.currentPlayer
        assertEquals(drawer, state.lastDrawnPlayer)
        val drawn = state.lastDrawnTile
        assertTrue(drawn in 0 until NUM_TILE_TYPES)
        assertTrue(state.players[drawer].concealed[drawn] > 0)

        // Discard a non-drawn tile if possible so the parked draw merges into sort.
        val choices =
            state.enumerateChoices().asList().filter { it.kind == ChoiceKind.Discard }
        val nonDrawn = choices.firstOrNull { it.tile() != drawn } ?: choices.first()
        state.applyChoice(nonDrawn)

        assertEquals(-1, state.lastDrawnTile)
        assertEquals(-1, state.lastDrawnPlayer)

        val (sorted, parked) =
            PlayerAreaLayout.expandHandWithDrawn(state.players[drawer], drawnTile = -1)
        assertEquals(-1, parked)
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i] >= sorted[i - 1])
        }
    }

    @Test
    fun expandHandParksDrawnOnRight() {
        val ps = PlayerState()
        // 1m,2m,2m,3m and drawn 9p
        ps.concealed[0] = 1
        ps.concealed[1] = 2
        ps.concealed[2] = 1
        ps.concealed[Tiles.DOT_9] = 1

        val (sorted, parked) = PlayerAreaLayout.expandHandWithDrawn(ps, drawnTile = Tiles.DOT_9)
        assertEquals(Tiles.DOT_9, parked)
        assertEquals(4, sorted.size)
        assertEquals(listOf(0, 1, 1, 2), sorted.toList())
    }

    @Test
    fun expandHandWithoutDrawnIsFullySorted() {
        val ps = PlayerState()
        ps.concealed[5] = 2
        ps.concealed[1] = 1
        val (sorted, parked) = PlayerAreaLayout.expandHandWithDrawn(ps, drawnTile = -1)
        assertEquals(-1, parked)
        assertEquals(listOf(1, 5, 5), sorted.toList())
    }
}
