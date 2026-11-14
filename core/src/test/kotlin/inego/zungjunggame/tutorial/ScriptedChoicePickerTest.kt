package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.tutorial.samples.SampleScenarios
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptedChoicePickerTest {

    @Test
    fun appliesQueuedDiscardInOrder() {
        val state = GameState.fromWallOrder(
            SampleScenarios.canonicalWall(Random(9)),
            eastSeat = 0,
        )
        val discards = state.enumerateChoices().asList()
            .filter { it.kind == ChoiceKind.Discard }
        assertTrue(discards.isNotEmpty())
        val target = discards.first()
        val picker = ScriptedChoicePicker()
        picker.enqueue(Choice.discard(target.tile()))
        assertEquals(1, picker.pending)
        assertTrue(picker.applyOne(state))
        assertEquals(0, picker.pending)
        assertEquals(target.tile(), state.lastDiscarded)
    }

    @Test
    fun emptyQueueReturnsFalse() {
        val state = GameState.fromWallOrder(
            SampleScenarios.canonicalWall(Random(2)),
            eastSeat = 0,
        )
        val picker = ScriptedChoicePicker()
        assertFalse(picker.applyOne(state))
    }
}
