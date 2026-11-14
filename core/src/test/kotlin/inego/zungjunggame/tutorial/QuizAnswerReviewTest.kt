package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuizAnswerReviewTest {

    @Test
    fun idSelectionMarksHitFalsePositiveAndMiss() {
        val review = QuizAnswerReviews.forIdSelection(
            optionIds = listOf("a", "b", "c", "d"),
            selectedIds = setOf("a", "c"),
            correctIds = setOf("a", "b"),
        )
        assertFalse(review.fullyCorrect)
        assertEquals(QuizOptionMark.Hit, review.markOf("a"))
        assertEquals(QuizOptionMark.Miss, review.markOf("b"))
        assertEquals(QuizOptionMark.FalsePositive, review.markOf("c"))
        assertEquals(QuizOptionMark.Idle, review.markOf("d"))
        assertEquals(setOf("a"), review.hitIds)
        assertEquals(setOf("c"), review.falsePositiveIds)
        assertEquals(setOf("b"), review.missIds)
    }

    @Test
    fun idSelectionFullyCorrectWhenSetsMatch() {
        val review = QuizAnswerReviews.forIdSelection(
            optionIds = listOf("1", "2", "3"),
            selectedIds = setOf("1", "3"),
            correctIds = setOf("3", "1"),
        )
        assertTrue(review.fullyCorrect)
        assertEquals(emptySet(), review.falsePositiveIds)
        assertEquals(emptySet(), review.missIds)
        assertEquals(setOf("1", "3"), review.hitIds)
    }

    @Test
    fun quizReviewHonorsFeedbackMode() {
        val quiz = TutorialStep.Quiz(
            promptKey = "quiz.prompt",
            kind = QuizKind.TileSelect,
            options = listOf(
                QuizOption("0", tileType = Tiles.CHAR_5),
                QuizOption("1", tileType = Tiles.EAST),
                QuizOption("2", tileType = Tiles.DOT_3),
                QuizOption("3", tileType = Tiles.RED),
            ),
            correctIds = setOf("1", "3"),
            feedbackMode = QuizFeedbackMode.SelectionReview,
        )
        val wrong = QuizAnswer(selectedIds = setOf("0", "1"))
        val review = assertNotNull(QuizAnswerReviews.review(quiz, wrong))
        assertEquals(QuizOptionMark.FalsePositive, review.markOf("0"))
        assertEquals(QuizOptionMark.Hit, review.markOf("1"))
        assertEquals(QuizOptionMark.Idle, review.markOf("2"))
        assertEquals(QuizOptionMark.Miss, review.markOf("3"))

        val messageOnly = quiz.copy(feedbackMode = QuizFeedbackMode.MessageOnly)
        assertNull(QuizAnswerReviews.review(messageOnly, wrong))
    }

    @Test
    fun multiChoiceDefaultsToSelectionReview() {
        val quiz = TutorialStep.Quiz(
            promptKey = "p",
            kind = QuizKind.MultiChoice,
            options = listOf(
                QuizOption("a", labelKey = "opt.a"),
                QuizOption("b", labelKey = "opt.b"),
            ),
            correctIds = setOf("b"),
        )
        assertEquals(QuizFeedbackMode.SelectionReview, quiz.feedbackMode)
        val review = assertNotNull(
            QuizAnswerReviews.review(quiz, QuizAnswer(selectedIds = setOf("a"))),
        )
        assertEquals(QuizOptionMark.FalsePositive, review.markOf("a"))
        assertEquals(QuizOptionMark.Miss, review.markOf("b"))
    }
}
