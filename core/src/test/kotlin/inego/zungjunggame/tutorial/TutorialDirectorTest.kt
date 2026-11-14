package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.session.board.ChoiceGate
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.tutorial.samples.SampleScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TutorialRunner / Director machinery with synthetic scenarios — not curriculum.
 */
class TutorialDirectorTest {

    @Test
    fun discardWaitWalkCompletes() {
        val scenario = TutorialTestFixtures.discardWaitScenario()
        val runner = TutorialRunner.start(scenario)
        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.currentWaitIsDiscard())

        val target = (runner.director.currentStep as TutorialStep.WaitChoice).match.payload
        assertNotNull(target)

        val other = runner.board.legalChoices(runner.state!!)
            .firstOrNull { it.kind == ChoiceKind.Discard && it.tile() != target }
        if (other != null) {
            assertFalse(runner.submitDiscard(other.tile()))
            assertEquals(TutorialDirector.Status.Running, runner.status)
        }

        assertTrue(runner.submitDiscard(target))
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        assertFalse(runner.active)
        assertEquals(target, runner.state!!.lastDiscarded)
    }

    @Test
    fun linkRefSetsPendingPageIdBeforeComplete() {
        val base = TutorialTestFixtures.discardWaitScenario()
        val steps = base.steps.toMutableList()
        val completeAt = steps.indexOfLast { it is TutorialStep.Complete }
        steps.add(completeAt, TutorialStep.LinkRef("rules-sequence"))
        val scenario = base.copy(steps = steps)
        val runner = TutorialRunner.start(scenario)
        val target = (runner.director.currentStep as TutorialStep.WaitChoice).match.payload!!
        assertTrue(runner.submitDiscard(target))
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        assertEquals("rules-sequence", runner.director.pendingLinkPageId)
        assertEquals("rules-sequence", runner.director.consumePendingLinkRef())
        assertEquals(null, runner.director.pendingLinkPageId)
    }

    @Test
    fun pungWaitWalkCompletesAndRecordsOpenPung() {
        val built = SampleScenarios.buildPungPosition(0)
        val scenario = ScenarioDef(
            id = "tool-pung-assert",
            titleKey = "tool.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.Factory { built.state.copy() },
            steps = listOf(
                TutorialStep.Gate.kinds(ChoiceKind.Pung),
                TutorialStep.WaitChoice(ChoiceMatch.pung(built.tile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("open pung") { s ->
                    s.players[0].pungsKongs[built.tile] == PlayerState.PK_OPEN_PUNG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == 0
                },
                TutorialStep.Complete,
            ),
        )
        val runner = TutorialRunner.start(scenario)
        assertEquals(TutorialDirector.Status.Running, runner.status)

        val wait = runner.director.currentStep as TutorialStep.WaitChoice
        assertEquals(ChoiceKind.Pung, wait.match.kind)
        val tile = wait.match.payload!!

        assertFalse(runner.submitChoice(Choice.pass()))
        assertEquals(TutorialDirector.Status.Running, runner.status)

        assertTrue(runner.submitChoice(Choice.pung(tile)))
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        val s = runner.state!!
        assertEquals(PlayerState.PK_OPEN_PUNG, s.players[0].pungsKongs[tile])
        assertEquals(DecisionType.ActOnHand, s.decisionType)
        assertEquals(0, s.currentPlayer)
    }

    @Test
    fun gateFiltersLegalToAllowedOnly() {
        val scenario = TutorialTestFixtures.pungWaitScenario()
        val runner = TutorialRunner.start(scenario)
        val state = runner.state!!
        val legal = runner.board.legalChoices(state)
        assertTrue(legal.any { it.kind == ChoiceKind.Pass })
        val allowed = runner.board.allowedChoices(state)
        assertTrue(allowed.isNotEmpty())
        assertTrue(allowed.all { it.kind == ChoiceKind.Pung })
    }

    @Test
    fun assertFailureMarksFailed() {
        val state = GameState.fromWallOrder(
            SampleScenarios.canonicalWall(kotlin.random.Random(1)),
            eastSeat = 0,
        )
        val scenario = ScenarioDef(
            id = "assert-fail",
            titleKey = "t",
            layout = ScenarioLayoutSpec.Factory { state.copy() },
            steps = listOf(
                TutorialStep.AssertState("always false") { false },
                TutorialStep.Complete,
            ),
        )
        val runner = TutorialRunner.start(scenario)
        assertEquals(TutorialDirector.Status.Failed, runner.status)
        assertEquals("always false", runner.director.failureMessage)
    }

    @Test
    fun seekBackBetweenCoachesRewindsChromeWithoutReplayingBoard() {
        val scenario = TutorialTestFixtures.multiCoach(
            coachKeys = listOf("coach.a", "coach.b"),
        ).copy(
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach("coach.a", showsBoard = true),
                TutorialStep.Coach("coach.b", showsBoard = true),
                TutorialStep.Complete,
            ),
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertEquals("coach.a", runner.lastCoachKey)
        val discardedBefore = runner.state!!.lastDiscarded
        assertTrue(runner.acknowledgeCoach())
        assertEquals("coach.b", runner.lastCoachKey)

        val result = runner.seekToUserSlide(0)
        assertEquals(TutorialRunner.SeekResult.Rewound, result)
        assertTrue(runner.awaitingCoach)
        assertEquals("coach.a", runner.lastCoachKey)
        assertEquals(0, runner.director.stepIndex)
        assertEquals(discardedBefore, runner.state!!.lastDiscarded)
    }

    @Test
    fun seekBackAcrossDiscardWaitRebuildsBoard() {
        val scenario = TutorialTestFixtures.discardWaitScenario()
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.acknowledgeCoach())
        val target = (runner.director.currentStep as TutorialStep.WaitChoice).match.payload!!
        assertTrue(runner.submitDiscard(target))
        assertEquals("coach.after", runner.lastCoachKey)
        assertEquals(target, runner.state!!.lastDiscarded)

        val intro = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "coach.intro"
        }
        val result = runner.seekToUserSlide(intro)
        assertEquals(TutorialRunner.SeekResult.Rebuilt, result)
        assertEquals("coach.intro", runner.lastCoachKey)
        assertEquals(-1, runner.state!!.lastDiscarded)
    }

    @Test
    fun seekBackFromLaterLayoutKeepsWaitMutatedBoard() {
        val built = SampleScenarios.buildPungPosition(0)
        val otherLayout = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(kotlin.random.Random(99)),
                eastSeat = 0,
            )
        }
        val scenario = ScenarioDef(
            id = "probe-back-after-wait-layout",
            titleKey = "probe.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.Factory { built.state.copy() },
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach("probe.offer", showsBoard = true),
                TutorialStep.WaitChoice(ChoiceMatch.pung(built.tile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.Coach("probe.after", showsBoard = true),
                TutorialStep.Coach(
                    "probe.other",
                    showsBoard = true,
                    layout = otherLayout,
                ),
                TutorialStep.Complete,
            ),
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.submitChoice(Choice.pung(built.tile)))
        assertEquals("probe.after", runner.lastCoachKey)
        assertEquals(PlayerState.PK_OPEN_PUNG, runner.state!!.players[0].pungsKongs[built.tile])
        val afterIdx = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "probe.after"
        }

        assertTrue(runner.acknowledgeCoach())
        assertEquals("probe.other", runner.lastCoachKey)
        assertEquals(0, runner.state!!.players[0].pungsKongs[built.tile])

        val result = runner.seekToUserSlide(afterIdx)
        assertEquals(TutorialRunner.SeekResult.Rebuilt, result)
        assertEquals("probe.after", runner.lastCoachKey)
        assertEquals(PlayerState.PK_OPEN_PUNG, runner.state!!.players[0].pungsKongs[built.tile])
    }

    @Test
    fun seekBackFromCompleteRewindsToLastCoach() {
        val scenario = TutorialTestFixtures.multiCoach(coachKeys = listOf("coach.a", "coach.b"))
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        val last = TutorialSlideNav.lastUserSlideIndex(scenario.steps)!!
        val result = runner.seekToUserSlide(last)
        assertEquals(TutorialRunner.SeekResult.Rewound, result)
        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertEquals("coach.b", runner.lastCoachKey)
    }

    @Test
    fun blockOnCoachWaitsForAcknowledgeThenCompletes() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.discardWaitScenario(),
            blockOnCoach = true,
        )
        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("coach.intro", runner.lastCoachKey)
        assertFalse(runner.submitDiscard(0))

        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        assertTrue(runner.currentWaitIsDiscard())

        val target = (runner.director.currentStep as TutorialStep.WaitChoice).match.payload!!
        assertTrue(runner.submitDiscard(target))
        assertEquals(TutorialDirector.Status.Running, runner.status)
        assertTrue(runner.awaitingCoach)
        assertEquals("coach.after", runner.lastCoachKey)

        assertTrue(runner.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        assertFalse(runner.awaitingCoach)
    }

    @Test
    fun sharedBoardReceivesWaitChoiceMatchGate() {
        val shared = BoardController()
        val runner = TutorialRunner.start(
            TutorialTestFixtures.discardWaitScenario(),
            board = shared,
        )
        assertSame(shared, runner.board)
        assertEquals(TutorialDirector.Status.Running, runner.status)

        val wait = runner.director.currentStep as TutorialStep.WaitChoice
        val target = wait.match.payload!!
        val allowed = shared.allowedChoices(runner.state!!)
        assertTrue(allowed.isNotEmpty())
        assertTrue(allowed.all { wait.match.matches(it) })
        assertTrue(allowed.any { it.kind == ChoiceKind.Discard && it.tile() == target })

        val other = shared.legalChoices(runner.state!!)
            .firstOrNull { it.kind == ChoiceKind.Discard && it.tile() != target }
        if (other != null) {
            assertFalse(shared.tryApplyDiscard(runner.state!!, other.tile()))
            assertFalse(runner.submitDiscard(other.tile()))
            assertEquals(TutorialDirector.Status.Running, runner.status)
        }

        assertTrue(runner.submitDiscard(target))
        assertEquals(TutorialDirector.Status.Completed, runner.status)
        assertEquals(ChoiceGate.Identity, shared.gate)
    }

    @Test
    fun overlayCoachBeforeDiscardWaitIsPreamble() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.overlayDiscardPreambleScenario(),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.currentCoachShowsBoard())
        assertTrue(runner.isPreambleToHumanWait())
        assertTrue(runner.isPreambleToHumanDiscard())
        assertEquals(ChoiceKind.Discard, runner.peekNextWaitChoice()?.match?.kind)

        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        assertFalse(runner.isPreambleToHumanWait())
        assertFalse(runner.isPreambleToHumanDiscard())
        assertTrue(runner.currentWaitIsDiscard())
    }

    @Test
    fun nextOnDiscardPreambleCompletesTheWaitAndLandsOnTheNextCoach() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.overlayDiscardPreambleScenario(),
            blockOnCoach = true,
        )
        val target = runner.peekNextWaitChoice()?.match?.payload
        assertNotNull(target)
        assertTrue(runner.completePreambleWait())
        assertTrue(runner.awaitingCoach)
        assertEquals("coach.after", runner.lastCoachKey)
        assertEquals(target, runner.state!!.lastDiscarded)
        assertFalse(runner.currentWaitIsDiscard())
        assertFalse(runner.isPreambleToHumanWait())
        assertFalse(runner.isPreambleToHumanDiscard())
    }

    @Test
    fun overlayCoachBeforePungWaitIsPreamble() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.overlayPungPreambleScenario(),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.currentCoachShowsBoard())
        assertTrue(runner.isPreambleToHumanWait())
        assertFalse(runner.isPreambleToHumanDiscard())
        assertEquals(ChoiceKind.Pung, runner.peekNextWaitChoice()?.match?.kind)

        assertTrue(runner.acknowledgeCoach())
        assertFalse(runner.awaitingCoach)
        assertFalse(runner.isPreambleToHumanWait())
        val wait = runner.director.currentStep as TutorialStep.WaitChoice
        assertEquals(ChoiceKind.Pung, wait.match.kind)
    }

    @Test
    fun nextOnPungPreambleCompletesTheWaitAndLandsOnTheNextCoach() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.overlayPungPreambleScenario(),
            blockOnCoach = true,
        )
        val tile = runner.peekNextWaitChoice()?.match?.payload
        assertNotNull(tile)
        assertTrue(runner.completePreambleWait())
        assertTrue(runner.awaitingCoach)
        assertEquals("coach.after", runner.lastCoachKey)
        assertEquals(PlayerState.PK_OPEN_PUNG, runner.state!!.players[0].pungsKongs[tile])
        assertEquals(DecisionType.ActOnHand, runner.state!!.decisionType)
        assertFalse(runner.isPreambleToHumanWait())
    }

    @Test
    fun overlayLectureCoachIsNotWaitPreamble() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.overlayLectureScenario(),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.currentCoachShowsBoard())
        assertFalse(runner.isPreambleToHumanWait())
        assertFalse(runner.isPreambleToHumanDiscard())
        assertEquals(null, runner.peekNextWaitChoice())

        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingCoach)
        assertFalse(runner.isPreambleToHumanWait())
        assertFalse(runner.isPreambleToHumanDiscard())
    }

    private fun TutorialRunner.currentWaitIsDiscard(): Boolean {
        val step = director.currentStep as? TutorialStep.WaitChoice ?: return false
        return step.match.kind == ChoiceKind.Discard
    }
}
