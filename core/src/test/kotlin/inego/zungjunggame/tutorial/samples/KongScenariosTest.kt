package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.*
import org.junit.Assert.*
import org.junit.Test

class KongScenariosTest {

    @Test
    fun meldedKongScenarioOffersKongAndDrawsSupplement() {
        val def = KongScenarios.meldedKong()
        assertEquals("kongs-melded", def.id)
        assertTrue(def.showsBoard)
        val state = def.layout.materialize()

        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        val choices = state.enumerateChoices().asList()
        val kinds = choices.map { it.kind }.toSet()
        assertTrue(ChoiceKind.Kong in kinds)
        assertTrue(ChoiceKind.Pung in kinds)
        assertTrue(ChoiceKind.Pass in kinds)

        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.kong(state.lastDiscarded))
        while (state.decisionType == DecisionType.ReactToDiscard) {
            state.applyChoice(Choice.pass())
        }

        val you = state.players[0]
        assertEquals(PlayerState.PK_OPEN_KONG, you.pungsKongs[Tiles.BAMBOO_8])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertEquals(0, state.lastDrawnPlayer)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun concealedKongScenarioOffersConcealedKongAndKeepsHandConcealed() {
        val def = KongScenarios.concealedKong()
        assertEquals("kongs-concealed", def.id)
        assertTrue(def.showsBoard)
        val state = def.layout.materialize()

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        val choices = state.enumerateChoices().asList()
        val kinds = choices.map { it.kind }.toSet()
        assertEquals(setOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep), kinds)

        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.concealedKong(Tiles.BAMBOO_7))

        val you = state.players[0]
        assertEquals(PlayerState.PK_CONCEALED_KONG, you.pungsKongs[Tiles.BAMBOO_7])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertEquals(0, state.lastDrawnPlayer)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun promotedKongScenarioOffersPromoteKongAndUpgradesMeld() {
        val def = KongScenarios.promotedKong()
        assertEquals("kongs-promoted", def.id)
        assertTrue(def.showsBoard)
        val state = def.layout.materialize()

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        val choices = state.enumerateChoices().asList()
        val kinds = choices.map { it.kind }.toSet()
        assertTrue(ChoiceKind.PromoteKong in kinds)
        assertTrue(ChoiceKind.Discard in kinds)

        val liveBefore = state.liveWallSize
        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))

        val you = state.players[0]
        assertEquals(PlayerState.PK_OPEN_KONG, you.pungsKongs[Tiles.DOT_5])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertEquals(0, state.lastDrawnPlayer)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun concealedKongInteractiveWalkCompletesWithoutFailure() {
        val def = KongScenarios.concealedKong()
        val runner = inego.zungjunggame.tutorial.TutorialRunner.start(def, blockOnCoach = true)

        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.kongs.concealed.offer", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)

        val allowed = runner.board.allowedChoices(runner.state!!)
        val kongChoice = allowed.first { it.kind == ChoiceKind.ConcealedKong }
        assertTrue(runner.submitChoice(kongChoice))

        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.kongs.concealed.arrangement", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.kongs.concealed.scoring", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.kongs.concealed.compensation", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun promotedKongInteractiveWalkCompletesWithoutFailure() {
        val def = KongScenarios.promotedKong()
        val runner = inego.zungjunggame.tutorial.TutorialRunner.start(def, blockOnCoach = true)

        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.kongs.promoted.offer", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)

        val allowed = runner.board.allowedChoices(runner.state!!)
        val kongChoice = allowed.first { it.kind == ChoiceKind.PromoteKong }
        assertTrue(runner.submitChoice(kongChoice))

        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("tutorial.kongs.promoted.upgraded", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.kongs.promoted.robbing", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals("tutorial.kongs.promoted.compensation", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals(inego.zungjunggame.tutorial.TutorialDirector.Status.Completed, runner.status)
    }

}
