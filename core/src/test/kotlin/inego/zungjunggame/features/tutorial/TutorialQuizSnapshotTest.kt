package inego.zungjunggame.features.tutorial

import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialTestFixtures
import inego.zungjunggame.tutorial.correctAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TutorialQuizSnapshotTest {

    private fun awaitingQuiz(): TutorialRunner {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.multiChoiceQuiz(correctId = "right", wrongId = "left"),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingQuiz)
        return runner
    }

    private fun awaitingRetryableQuiz(): TutorialRunner {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.dynamicTileSelectQuiz(),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingQuiz)
        return runner
    }

    @Test
    fun quizContentProjectsTheFreshAttempt() {
        val runner = awaitingQuiz()

        val content = tutorialQuizContent(runner)

        assertTrue(content.visible)
        assertEquals(runner.scenario.titleKey, content.titleKey)
        assertNotNull(content.quiz)
        assertFalse(content.passed)
        assertFalse(content.failVisible)
        assertTrue(content.attempt.answer.selectedIds.isEmpty())
    }

    @Test
    fun quizContentIsAbsentOffTheQuizBeat() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.multiCoach(),
            blockOnCoach = true,
        )

        assertFalse(tutorialQuizContent(runner).visible)
    }

    @Test
    fun quizContentPublishesTheLatchedFailure() {
        val runner = awaitingQuiz()

        assertFalse(runner.submitQuiz(QuizAnswer(selectedIds = setOf("left"))))
        val content = tutorialQuizContent(runner)

        assertTrue(content.visible)
        assertFalse(content.passed)
        assertTrue(content.failVisible)
    }

    @Test
    fun quizContentPublishesThePostConfirmDock() {
        val runner = awaitingRetryableQuiz()

        assertTrue(runner.submitQuiz(runner.currentQuiz!!.correctAnswer()))
        val content = tutorialQuizContent(runner)

        assertTrue(content.passed)
        assertFalse(content.failVisible)
        assertEquals(runner.currentQuiz!!.correctIds, content.attempt.answer.selectedIds)
    }
}
