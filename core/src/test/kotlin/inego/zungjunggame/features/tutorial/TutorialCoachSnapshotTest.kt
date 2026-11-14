package inego.zungjunggame.features.tutorial

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.features.board.BoardAnchors
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialTestFixtures
import inego.zungjunggame.ui.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorialCoachSnapshotTest {

    @Test
    fun coachContentProjectsTheAwaitingCoachBeat() {
        val scenario = TutorialTestFixtures.overlayLectureScenario()
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)

        val content = tutorialCoachContent(runner)

        assertTrue(content.visible)
        assertEquals(TutorialCoachSnapshot.Mode.Coach, content.mode)
        assertEquals(scenario.titleKey, content.titleKey)
        assertEquals("coach.lecture_a", content.bodyKey)
        assertEquals("tutorial.next", content.primaryKey)
        assertTrue(content.awaitingCoach)
        assertTrue(content.boardOverlay)
        // A board-overlay beat leaves the center to the live table: no diagram.
        assertEquals(TutorialDiagram.None, content.diagram)
        assertEquals(scenario.id, content.lessonId)
        assertTrue(content.scene != null)
    }

    @Test
    fun coachContentIsAbsentOnAPlayBeat() {
        val runner = TutorialRunner.start(TutorialTestFixtures.discardWaitScenario())

        val content = tutorialCoachContent(runner)

        assertFalse(content.visible)
    }

    @Test
    fun completePanelAfterTheLastLesson() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.coachThenComplete(id = "tool-coach"),
            blockOnCoach = true,
        )

        // The fixture is the campaign's only lesson: completion is a finished panel.
        assertTrue(runner.acknowledgeCoach())
        val content = tutorialCoachContent(runner)

        assertTrue(content.visible)
        assertEquals(TutorialCoachSnapshot.Mode.Complete, content.mode)
        assertTrue(content.offersExit)
        assertFalse(content.offersNext)
    }

    @Test
    fun liveFocusParksOnThePublishedTargetRegion() {
        val target = Rect(10f, 20f, 30f, 40f)
        val values = coach(boardOverlay = true).copy(
            focus = TutorialFocus(targetId = "river.last", calloutKey = "coach.callout"),
        )

        val park = tutorialCoachLiveFocus(values, mapOf(BoardAnchors.target("river.last") to target))

        assertEquals(target, park)
    }

    @Test
    fun liveFocusParksOnTheClaimButtonForAPreambleWait() {
        val claim = Rect(5f, 6f, 7f, 8f)
        val values = coach(boardOverlay = true).copy(
            isPreambleToHumanWait = true,
            waitKind = ChoiceKind.Pung,
            waitPayload = 7,
        )

        val park = tutorialCoachLiveFocus(values, mapOf(BoardAnchors.claim(ChoiceKind.Pung, 7) to claim))

        assertEquals(claim, park)
    }

    @Test
    fun liveFocusFallsBackToThePromptedHandAndClearsOffBoard() {
        val prompt = Rect(1f, 2f, 3f, 4f)
        val regions = mapOf(BoardAnchors.HAND_PROMPT to prompt)

        val parked = tutorialCoachLiveFocus(coach(boardOverlay = true), regions)
        assertEquals(prompt, parked)

        // A lecture coach (not a board overlay) publishes no park.
        assertNull(tutorialCoachLiveFocus(coach(boardOverlay = false), regions))
    }

    private fun coach(boardOverlay: Boolean): TutorialCoachSnapshot = TutorialCoachSnapshot(
        visible = true,
        mode = TutorialCoachSnapshot.Mode.Coach,
        awaitingCoach = true,
        boardOverlay = boardOverlay,
    )
}
