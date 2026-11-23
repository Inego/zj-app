package inego.zungjunggame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameplayLoopTest {
    @Test
    fun generateRandomDealsThirteenPlusEastDraw() {
        val state = GameState.generateRandom(Random(42))
        assertEquals(0, state.eastSeat)
        assertEquals(0, state.currentPlayer)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(LIVE_WALL_DRAWABLE_SIZE - (NUM_PLAYERS * 13 + 1), state.liveWallSize)

        fun concealedCount(p: Int): Int = state.players[p].concealed.sum()
        assertEquals(14, concealedCount(0))
        assertEquals(13, concealedCount(1))
        assertEquals(13, concealedCount(2))
        assertEquals(13, concealedCount(3))
    }

    @Test
    fun generateRandomRotatesDealerAndSeatWinds() {
        val state = GameState.generateRandom(Random(42), eastSeat = 2)
        assertEquals(2, state.eastSeat)
        assertEquals(2, state.currentPlayer)

        fun concealedCount(p: Int): Int = state.players[p].concealed.sum()
        assertEquals(13, concealedCount(0))
        assertEquals(13, concealedCount(1))
        assertEquals(14, concealedCount(2))
        assertEquals(13, concealedCount(3))

        // Counter-clockwise from East at seat 2: 2=E, 3=S, 0=W, 1=N
        assertEquals(Tiles.EAST, state.seatWindTile(2))
        assertEquals(Tiles.SOUTH, state.seatWindTile(3))
        assertEquals(Tiles.WEST, state.seatWindTile(0))
        assertEquals(Tiles.NORTH, state.seatWindTile(1))
    }

    @Test
    fun enumerateChoicesAlwaysHasActOnHandMove() {
        val state = GameState.generateRandom(Random(7))
        val choices = state.enumerateChoices()
        assertTrue(choices.size > 0)
        // Either a sequential concealed-kong gate (declare/Keep) or discards.
        assertTrue(
            choices.asList().any {
                it.kind == ChoiceKind.Discard ||
                    it.kind == ChoiceKind.ConcealedKong ||
                    it.kind == ChoiceKind.Keep ||
                    it.kind == ChoiceKind.Hu
            },
        )
    }

    @Test
    fun applyChoiceWithUndoRestoresState() {
        val state = GameState.generateRandom(Random(99))
        // Clear any concealed-kong gate so a discard is legal.
        while (state.nextPendingConcealedKongTile() >= 0) {
            state.applyChoice(Choice.keep(state.nextPendingConcealedKongTile()))
        }
        val choices = state.enumerateChoices()
        val discard = choices.asList().first { it.kind == ChoiceKind.Discard }
        val snapshotLive = state.liveWallSize
        val snapshotPlayer = state.currentPlayer
        val snapshotConcealed = state.players[0].concealed.copyOf()
        val snapshotDeclined = state.declinedConcealedKong.copyOf()

        val undo = state.applyChoiceWithUndo(discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        state.undoChoice(undo)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(snapshotLive, state.liveWallSize)
        assertEquals(snapshotPlayer, state.currentPlayer)
        assertTrue(snapshotConcealed.contentEquals(state.players[0].concealed))
        assertTrue(snapshotDeclined.contentEquals(state.declinedConcealedKong))
    }
}
