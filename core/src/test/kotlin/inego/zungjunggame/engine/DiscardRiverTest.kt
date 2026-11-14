package inego.zungjunggame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscardRiverTest {
    @Test
    fun discardEntersRiverWhenEveryonePasses() {
        val state = GameState.generateRandom(Random(1))
        val discardTile = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }.tile()
        val discarder = state.currentPlayer

        // Discard.
        state.applyChoice(Choice.discard(discardTile))
        assertEquals(0, state.discards[discarder].size, "pending discard is not yet in the river")

        // Three passes finalize the river tile.
        repeat(3) {
            assertEquals(DecisionType.ReactToDiscard, state.decisionType)
            state.applyChoice(Choice.pass())
        }

        assertEquals(listOf(discardTile), state.discards[discarder])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
    }

    @Test
    fun undoRemovesFinalizedRiverTile() {
        val state = GameState.generateRandom(Random(2))
        val d1 = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(d1)
        // Collect undos for the three passes — only the last pass appends the river.
        val undos = ArrayList<GameStateUndo>()
        repeat(3) {
            undos += state.applyChoiceWithUndo(Choice.pass())
        }
        val owner = (0 until NUM_PLAYERS).first { state.discards[it].isNotEmpty() }
        assertEquals(1, state.discards[owner].size)

        // Undo last pass (the one that finalized the river + drew).
        state.undoChoice(undos.last())
        assertTrue(state.discards[owner].isEmpty(), "undo should pop the river append")
    }
}

