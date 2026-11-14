package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.render.wallbuild.WallBuildLessonState
import inego.zungjunggame.render.wallbuild.WallBuildToGameState
import inego.zungjunggame.tutorial.samples.OrientationScenarios
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slide-nav index math and Back resolution with synthetic steps — not curriculum.
 */
class TutorialSlideNavTest {

    @Test
    fun usesWallDealWhenLessonHasWallBuildDiagram() {
        assertTrue(TutorialSlideNav.usesWallDeal(OrientationScenarios.wallDead()))
        val noWall = TutorialTestFixtures.multiCoach(coachKeys = listOf("a", "b"))
        assertFalse(TutorialSlideNav.usesWallDeal(noWall))
    }

    @Test
    fun nextContinuousCoachSkipsAutoStepsAndStopsAtWait() {
        val steps = listOf(
            TutorialStep.Coach("a"),
            TutorialStep.Gate.kinds(inego.zungjunggame.engine.ChoiceKind.Discard),
            TutorialStep.Coach("b"),
            TutorialStep.WaitChoice(
                ChoiceMatch.discard(0),
            ),
            TutorialStep.Coach("c"),
            TutorialStep.Complete,
        )
        assertEquals(2, TutorialSlideNav.nextContinuousCoachIndex(steps, 0))
        assertNull(TutorialSlideNav.nextContinuousCoachIndex(steps, 2))
        assertEquals(4, TutorialSlideNav.nextUserSlideIndex(steps, 2))
    }

    @Test
    fun nextCoachAfterPreambleWaitSkipsOneWaitAndAutoSteps() {
        val steps = listOf(
            TutorialStep.Coach("click"),
            TutorialStep.WaitChoice(ChoiceMatch.discard(0)),
            TutorialStep.ScriptChoice(inego.zungjunggame.engine.Choice.pass()),
            TutorialStep.AssertState("ok") { true },
            TutorialStep.Coach("after"),
            TutorialStep.WaitChoice(ChoiceMatch.pung(1)),
            TutorialStep.Coach("later"),
            TutorialStep.Complete,
        )
        assertNull(TutorialSlideNav.nextContinuousCoachIndex(steps, 0))
        // One wait is auto-completed; a second wait still stops the peek.
        assertEquals(4, TutorialSlideNav.nextCoachAfterPreambleWait(steps, 0))
        assertEquals(6, TutorialSlideNav.nextCoachAfterPreambleWait(steps, 4))
    }

    @Test
    fun coachSceneBoardOverlaySharesContinuityKeyBothDirections() {
        val scenario = TutorialTestFixtures.multiCoach(
            id = "probe-board-pair",
            coachKeys = listOf("east_hu", "east_hu_odds"),
        ).copy(
            showsBoard = false,
            steps = listOf(
                TutorialStep.Coach("east_hu", showsBoard = true),
                TutorialStep.Coach("east_hu_odds", showsBoard = true),
                TutorialStep.Complete,
            ),
        )
        val a = TutorialSlideNav.coachSceneAt(scenario, 0)!!
        val b = TutorialSlideNav.coachSceneAt(scenario, 1)!!
        assertEquals(a.first.continuityKey, b.first.continuityKey)
        assertEquals("board:probe-board-pair", a.first.continuityKey)
        assertEquals(scenario.titleKey, a.first.titleKey)
        assertEquals(scenario.titleKey, b.first.titleKey)
        assertTrue(a.second && b.second)
        assertFalse(TutorialSlideNav.mutatesBoardBetween(scenario.steps, 0, 1))
    }

    @Test
    fun mutatesBoardBetweenDetectsWaitAndScript() {
        val scenario = TutorialTestFixtures.discardWaitScenario()
        val intro = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "coach.intro"
        }
        val after = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "coach.after"
        }
        assertTrue(TutorialSlideNav.mutatesBoardBetween(scenario.steps, intro, after))
        assertFalse(TutorialSlideNav.mutatesBoardBetween(scenario.steps, intro, intro + 1))
    }

    @Test
    fun previousUserSlideWalksCoachAndQuizOnly() {
        val steps = listOf(
            TutorialStep.Coach("a"),
            TutorialStep.Gate.kinds(inego.zungjunggame.engine.ChoiceKind.Discard),
            TutorialStep.Coach("b"),
            TutorialStep.Quiz(
                promptKey = "q",
                kind = QuizKind.MultiChoice,
                options = listOf(QuizOption("x", labelKey = "opt.x")),
                correctIds = setOf("x"),
            ),
            TutorialStep.Complete,
        )
        val quizIndex = steps.indexOfFirst { it is TutorialStep.Quiz }
        val prev = TutorialSlideNav.previousUserSlideIndex(steps, quizIndex)
        assertNotNull(prev)
        assertTrue(steps[prev] is TutorialStep.Coach)
        assertEquals("b", (steps[prev] as TutorialStep.Coach).textKey)

        val first = TutorialSlideNav.firstUserSlideIndex(steps)!!
        assertNull(TutorialSlideNav.previousUserSlideIndex(steps, first))
        assertEquals(quizIndex, TutorialSlideNav.lastUserSlideIndex(steps))
    }

    @Test
    fun resolveBackWithinLessonGoesToPreviousCoach() {
        val scenario = TutorialTestFixtures.multiChoiceQuiz()
        val quizIndex = scenario.steps.indexOfFirst { it is TutorialStep.Quiz }
        val runner = TutorialRunner.startAtStep(
            scenario = scenario,
            targetStepIndex = quizIndex,
            choiceRng = Random(1),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingQuiz)
        val target = TutorialSlideNav.resolveBack(runner)
        assertNotNull(target)
        assertEquals(scenario.id, target.lessonId)
        assertTrue(scenario.steps[target.stepIndex] is TutorialStep.Coach)
        assertEquals(
            TutorialSlideNav.previousUserSlideIndex(scenario.steps, quizIndex),
            target.stepIndex,
        )
    }

    @Test
    fun firstSlideOfUnknownLessonCannotGoBack() {
        // Id not in TutorialCatalog ⇒ no previous campaign lesson.
        val scenario = TutorialTestFixtures.coachThenComplete(id = "tool-orphan")
        val first = TutorialSlideNav.firstUserSlideIndex(scenario.steps)!!
        val runner = TutorialRunner.startAtStep(
            scenario = scenario,
            targetStepIndex = first,
            choiceRng = Random(3),
            blockOnCoach = true,
        )
        assertFalse(TutorialSlideNav.canGoBack(runner))
        assertNull(TutorialSlideNav.resolveBack(runner))
    }

    @Test
    fun startAtStepSeeksPastBoardWaitToAfterCoach() {
        val scenario = TutorialTestFixtures.discardWaitScenario()
        val afterIndex = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "coach.after"
        }
        assertTrue(afterIndex > 0)
        val runner = TutorialRunner.startAtStep(
            scenario = scenario,
            targetStepIndex = afterIndex,
            choiceRng = Random(4),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertEquals(afterIndex, runner.director.stepIndex)
        assertEquals("coach.after", runner.lastCoachKey)
        assertNotNull(runner.state!!.lastDiscarded)
    }

    @Test
    fun startAtStepLandsOnQuizWithFactoryHand() {
        val scenario = TutorialTestFixtures.dynamicTileSelectQuiz()
        val quizIndex = scenario.steps.indexOfFirst { it is TutorialStep.Quiz }
        val runner = TutorialRunner.startAtStep(
            scenario = scenario,
            targetStepIndex = quizIndex,
            choiceRng = Random(5),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingQuiz)
        val quiz = runner.currentQuiz!!
        assertEquals(13, quiz.options.size)
        assertTrue(quiz.correctIds.isNotEmpty())
    }

    @Test
    fun wallLiveCountBackLandsOnAppTableBoardOverlay() {
        val scenario = OrientationScenarios.wallDead()
        val liveCount = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.live_count"
        }
        val appTable = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.app_table"
        }
        assertTrue(liveCount > 0 && appTable >= 0)
        assertTrue(TutorialSlideNav.stepShowsBoard(scenario, liveCount))
        assertTrue(TutorialSlideNav.stepShowsBoard(scenario, appTable))

        val onLive = TutorialRunner.startAtStep(
            scenario = scenario,
            targetStepIndex = liveCount,
            choiceRng = Random(7),
            blockOnCoach = true,
        )
        assertEquals("tutorial.orient.wall.live_count", onLive.lastCoachKey)
        val target = TutorialSlideNav.resolveBack(onLive)
        assertNotNull(target)
        assertEquals(scenario.id, target.lessonId)
        assertEquals(appTable, target.stepIndex)
    }

    /**
     * Durable wall deal is the source of truth across Back: startAtStep alone
     * yields a dummy board; rematerializing from the same [WallBuildDeal] keeps
     * East's concealed multiset (app_table ↔ east_discard ↔ live_count).
     */
    @Test
    fun wallDealRematerializePreservesEastAcrossBackTargets() {
        val scenario = OrientationScenarios.wallDead()
        val eastDiscard = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.east_discard"
        }
        val appTable = scenario.steps.indexOfFirst {
            it is TutorialStep.Coach && it.textKey == "tutorial.orient.wall.app_table"
        }
        assertTrue(eastDiscard in 0..<appTable)

        val lesson = WallBuildLessonState()
        lesson.commit(3, 4)
        lesson.ensureShuffledWallMapping(Random(42))
        val deal = lesson.exportDeal()!!
        val fromDeal = WallBuildToGameState.openingState(
            breakStackCount = deal.stackCount,
            tileTypeAtSlot = { slot -> deal.slotToTile[slot] / 4 },
        )
        val east = fromDeal.players[0].concealed.copyOf()

        // Back target: board overlay (app_table) or wall diagram (east_discard).
        for (target in listOf(appTable, eastDiscard)) {
            val runner = TutorialRunner.startAtStep(
                scenario = scenario,
                targetStepIndex = target,
                choiceRng = Random(8),
                blockOnCoach = true,
            )
            if (TutorialSlideNav.stepShowsBoard(scenario, target)) {
                assertFalse(
                    east.contentEquals(runner.state!!.players[0].concealed),
                    "precondition: dummy layout ≠ wall deal at step $target",
                )
                runner.rematerializeState(fromDeal)
                assertContentEquals(
                    east,
                    runner.state!!.players[0].concealed,
                    "East must match wall deal after rematerialize at step $target",
                )
            } else {
                // Wall diagram step: deal install is view-side; engine may be dummy.
                assertEquals(eastDiscard, target)
                assertTrue(deal.stackCount in 2..12)
            }
        }
        assertEquals(NUM_TILE_TYPES, east.size)
    }
}
