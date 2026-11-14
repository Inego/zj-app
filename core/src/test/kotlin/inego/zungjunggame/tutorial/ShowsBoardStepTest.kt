package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.tutorial.samples.SampleScenarios
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tool test: mid-lesson [TutorialStep.Coach.showsBoard] flips board visibility
 * without scenario-wide [ScenarioDef.showsBoard].
 */
class ShowsBoardStepTest {

    @Test
    fun coachShowsBoardOverridesScenarioFalse() {
        val scenario = ScenarioDef(
            id = "probe-board-handoff",
            titleKey = "probe.title",
            layout = ScenarioLayoutSpec.Factory {
                GameState.fromWallOrder(
                    SampleScenarios.canonicalWall(Random(0)),
                    eastSeat = 0,
                )
            },
            steps = listOf(
                TutorialStep.Coach("probe.a", diagram = TutorialDiagram.None),
                TutorialStep.Coach(
                    "probe.b",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.awaitingCoach)
        assertFalse(runner.showsBoard)
        assertFalse(runner.currentCoachShowsBoard())

        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.currentCoachShowsBoard())
        assertTrue(runner.showsBoard)
        assertTrue(runner.state != null)
    }

    @Test
    fun consecutiveCoachShowsBoardStaysOn() {
        val scenario = ScenarioDef(
            id = "probe-board-streak",
            titleKey = "probe.title",
            layout = ScenarioLayoutSpec.Factory {
                GameState.fromWallOrder(
                    SampleScenarios.canonicalWall(Random(0)),
                    eastSeat = 0,
                )
            },
            steps = listOf(
                TutorialStep.Coach(
                    "probe.a",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "probe.b",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.showsBoard)
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.showsBoard)
        assertTrue(runner.state != null)
    }

    @Test
    fun coachStepLayoutOverridesScenarioLayoutAndRestoresOnBack() {
        val layoutA = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(Random(10)),
                eastSeat = 0,
            )
        }
        val layoutB = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(Random(20)),
                eastSeat = 0,
            )
        }
        val scenario = ScenarioDef(
            id = "probe-step-layout",
            titleKey = "probe.title",
            layout = layoutA,
            steps = listOf(
                TutorialStep.Coach(
                    "probe.a",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "probe.b",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                    layout = layoutB,
                ),
                TutorialStep.Coach(
                    "probe.c",
                    diagram = TutorialDiagram.None,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        val handA = runner.state!!.players[0].concealed.copyOf()

        assertTrue(runner.acknowledgeCoach())
        val handB = runner.state!!.players[0].concealed.copyOf()
        assertFalse(handA.contentEquals(handB), "layoutB hand must differ from layoutA")

        assertTrue(runner.acknowledgeCoach())
        val handC = runner.state!!.players[0].concealed.copyOf()
        assertTrue(handB.contentEquals(handC), "probe.c must keep layoutB hand")

        val seekResult1 = runner.seekToUserSlide(1)
        assertEquals(TutorialRunner.SeekResult.Rewound, seekResult1)
        assertTrue(handB.contentEquals(runner.state!!.players[0].concealed))

        val seekResult0 = runner.seekToUserSlide(0)
        assertEquals(TutorialRunner.SeekResult.Rewound, seekResult0)
        assertTrue(handA.contentEquals(runner.state!!.players[0].concealed))
    }

    @Test
    fun restoreBindsActiveStepLayoutAcrossBackAndNext() {
        val layoutA = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(Random(10)),
                eastSeat = 0,
            )
        }
        val layoutB = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(Random(20)),
                eastSeat = 0,
            )
        }
        val scenario1 = ScenarioDef(
            id = "probe-restore-layout",
            titleKey = "probe.title",
            layout = layoutA,
            steps = listOf(
                TutorialStep.Coach("probe.a", showsBoard = true),
                TutorialStep.Coach("probe.b", showsBoard = true, layout = layoutB),
                TutorialStep.Coach("probe.c", showsBoard = true),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )
        val runner1 = TutorialRunner.start(scenario1, blockOnCoach = true)
        val handA = runner1.state!!.players[0].concealed.copyOf()
        assertTrue(runner1.acknowledgeCoach())
        val handB = runner1.state!!.players[0].concealed.copyOf()

        val snap = runner1.captureSnapshot()

        // Fresh scenario definition simulating a cold restart where step 1's layout
        // was generated with a different RNG shuffle (layoutC).
        val layoutC = ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                SampleScenarios.canonicalWall(Random(99)),
                eastSeat = 0,
            )
        }
        val scenario2 = ScenarioDef(
            id = "probe-restore-layout",
            titleKey = "probe.title",
            layout = layoutA,
            steps = listOf(
                TutorialStep.Coach("probe.a", showsBoard = true),
                TutorialStep.Coach("probe.b", showsBoard = true, layout = layoutC),
                TutorialStep.Coach("probe.c", showsBoard = true),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )

        val restored = TutorialRunner.restore(scenario2, snap, blockOnCoach = true)!!
        assertTrue(handB.contentEquals(restored.state!!.players[0].concealed))

        // Back to step 0 restores layoutA
        val seekBack = restored.seekToUserSlide(0)
        assertEquals(TutorialRunner.SeekResult.Rewound, seekBack)
        assertTrue(handA.contentEquals(restored.state!!.players[0].concealed))

        // Next back to step 1 must restore handB (from snapshot), NOT layoutC
        assertTrue(restored.acknowledgeCoach())
        assertTrue(handB.contentEquals(restored.state!!.players[0].concealed))

        // Next to step 2 preserves handB
        assertTrue(restored.acknowledgeCoach())
        assertTrue(handB.contentEquals(restored.state!!.players[0].concealed))
    }
}
