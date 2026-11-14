package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.table.HighlightGroup
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.render.tiles.TileFocusGlow
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimScenariosTest {

    @Test
    fun priorityScenarioDefDeclaresRevealedSeatsAndStepSequence() {
        val def = ClaimScenarios.priority()
        assertEquals("claims-priority", def.id)
        assertTrue(def.showsBoard)
        assertEquals(setOf(1), def.revealedSeats)
        val initialState = def.layout.materialize()
        assertEquals(2, initialState.players[1].concealed[Tiles.BAMBOO_3])
        assertEquals(0, initialState.players[2].concealed[Tiles.BAMBOO_3])

        // The coach text keys are asserted where they are produced, by the
        // interactive walk below; repeating the list here would be a second
        // hand-maintained copy of the scenario's step script.
        assertTrue(def.steps.any { it is TutorialStep.WaitChoice })
        assertTrue(def.steps.any { it is TutorialStep.AssertState })
        assertTrue(def.steps.last() is TutorialStep.Complete)
    }

    @Test
    fun priorityInteractiveWalkCompletesWithoutFailure() {
        val def = ClaimScenarios.priority()
        val runner = TutorialRunner.start(def, blockOnCoach = true)

        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals(setOf(1), runner.revealedSeats)
        assertEquals("tutorial.claims.priority.conflict", runner.lastCoachKey)

        // Step 0: acknowledge conflict coach -> now awaiting human choice
        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        assertTrue(runner.director.currentStep is TutorialStep.WaitChoice)

        // Human clicks Chow; engine resolves Pung > Chow -> tile goes to Right
        val allowed = runner.board.allowedChoices(runner.state!!)
        val chow = allowed.first { it.kind == inego.zungjunggame.engine.ChoiceKind.Chow }
        assertTrue(runner.submitChoice(chow))

        // State verified: Right formed open pung, turn jumped to Right
        assertEquals(PlayerState.PK_OPEN_PUNG, runner.state!!.players[1].pungsKongs[Tiles.BAMBOO_3])
        assertEquals(1, runner.state!!.currentPlayer)

        // Step 1: pung beats chow
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.claims.priority.pung_beats_chow", runner.lastCoachKey)
        assertEquals(setOf(1), runner.revealedSeats)

        // Step 2: hierarchy
        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.claims.priority.hierarchy", runner.lastCoachKey)
        assertEquals(setOf(1), runner.revealedSeats)

        // Step 3: multi win
        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.claims.priority.multi_win", runner.lastCoachKey)
        assertEquals(setOf(1), runner.revealedSeats)

        // Step 4: Complete
        assertTrue(runner.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun huInteractiveWalkCompletesWithoutFailure() {
        val def = ClaimScenarios.hu()
        val runner = TutorialRunner.start(def, blockOnCoach = true)

        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.claims.hu.offer", runner.lastCoachKey)

        // Step 0: acknowledge offer coach -> awaiting Hu choice
        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        assertTrue(runner.director.currentStep is TutorialStep.WaitChoice)

        // Human clicks Hu
        val allowed = runner.board.allowedChoices(runner.state!!)
        val hu = allowed.first { it.kind == inego.zungjunggame.engine.ChoiceKind.Hu }
        assertTrue(runner.submitChoice(hu))

        // Step 1: not_a_set coach
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.claims.hu.not_a_set", runner.lastCoachKey)

        // Step 2: breakdown panel drag/collapse coach
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.claims.hu.breakdown", runner.lastCoachKey)

        // Step 3: Complete
        assertTrue(runner.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun highlightWhereOpponentHandMatchesOpponentConcealedTiles() {
        val group = HighlightGroup(
            types = listOf(Tiles.BAMBOO_3),
            glow = TileFocusGlow.Prompt,
            where = HighlightWhere.OpponentHand,
        )

        // Opponent hand matches
        assertTrue(
            group.matches(
                human = false,
                region = PlayerAreaLayout.Region.Hand,
                tileType = Tiles.BAMBOO_3,
                pendingDiscard = false,
                open = true,
            ),
        )
        // Opponent drawn slot matches
        assertTrue(
            group.matches(
                human = false,
                region = PlayerAreaLayout.Region.Drawn,
                tileType = Tiles.BAMBOO_3,
                pendingDiscard = false,
                open = true,
            ),
        )
        // Human hand does NOT match
        assertFalse(
            group.matches(
                human = true,
                region = PlayerAreaLayout.Region.Hand,
                tileType = Tiles.BAMBOO_3,
                pendingDiscard = false,
                open = true,
            ),
        )
        // Opponent river does NOT match
        assertFalse(
            group.matches(
                human = false,
                region = PlayerAreaLayout.Region.River,
                tileType = Tiles.BAMBOO_3,
                pendingDiscard = false,
                open = true,
            ),
        )
        // Different tile does NOT match
        assertFalse(
            group.matches(
                human = false,
                region = PlayerAreaLayout.Region.Hand,
                tileType = Tiles.BAMBOO_1,
                pendingDiscard = false,
                open = true,
            ),
        )
    }
}
