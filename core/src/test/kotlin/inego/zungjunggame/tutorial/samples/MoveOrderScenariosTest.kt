package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveOrderScenariosTest {

    @Test
    fun counterclockwiseWalkPassesTurnToOppositeWithDrawnTile() {
        val def = MoveOrderScenarios.counterclockwise()
        val runner = TutorialRunner.start(def, blockOnCoach = true)

        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.moves.counterclockwise.this_tile", runner.lastCoachKey)

        // Step 0: acknowledge this_tile -> awaiting human discard
        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        val wait = runner.director.currentStep as TutorialStep.WaitChoice

        // Human discards prompted tile -> script choices pass -> Right draws (seat 1 has 14 tiles)
        val discardTile = wait.match.payload!!
        assertTrue(runner.submitDiscard(discardTile))

        assertEquals("tutorial.moves.counterclockwise.if_nobody", runner.lastCoachKey)
        assertEquals(1, runner.state!!.currentPlayer)
        assertEquals(1, runner.state!!.lastDrawnPlayer)
        assertEquals(14, runner.state!!.players[1].concealed.sum())

        // Acknowledge if_nobody, the_river, to_the_right, right_next
        assertTrue(runner.acknowledgeCoach()) // -> the_river
        assertEquals("tutorial.moves.counterclockwise.the_river", runner.lastCoachKey)
        assertTrue(runner.acknowledgeCoach()) // -> to_the_right
        assertEquals("tutorial.moves.counterclockwise.to_the_right", runner.lastCoachKey)
        assertTrue(runner.acknowledgeCoach()) // -> right_next
        assertEquals("tutorial.moves.counterclockwise.right_next", runner.lastCoachKey)

        // Acknowledge right_next -> Right discards -> passes run -> Opposite draws from wall!
        assertTrue(runner.acknowledgeCoach()) // -> right_discards
        assertEquals("tutorial.moves.counterclockwise.right_discards", runner.lastCoachKey)

        val finalState = runner.state!!
        assertEquals(1, finalState.lastDiscarder)
        assertEquals(2, finalState.currentPlayer)
        assertEquals(DecisionType.ActOnHand, finalState.decisionType)
        assertEquals(2, finalState.lastDrawnPlayer)
        assertTrue(finalState.lastDrawnTile >= 0)
        assertEquals(14, finalState.players[2].concealed.sum())
        assertEquals(13, finalState.players[1].concealed.sum())

        // Acknowledge right_discards -> Complete
        assertTrue(runner.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }
}
