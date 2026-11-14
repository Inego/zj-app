package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Tiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quiz runner / overlay / content-factory **tools** — synthetic probes only.
 * Does not lock lesson copy, step order, or campaign structure.
 */
class OrientationQuizTest {

    @Test
    fun uiModeBlocksOnQuizUntilCorrectAnswer() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.multiChoiceQuiz(correctId = "right", wrongId = "left"),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingQuiz)
        val quiz = runner.currentQuiz!!
        assertFalse(runner.submitQuiz(QuizAnswer(selectedIds = setOf("left"))))
        assertTrue(runner.quizFeedbackFail)
        assertTrue(runner.awaitingQuiz)
        assertTrue(runner.submitQuiz(QuizAnswer(selectedIds = quiz.correctIds)))
        while (runner.awaitingCoach) {
            assertTrue(runner.acknowledgeCoach())
        }
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun wrongBinAssignDoesNotAdvance() {
        val scenario = ScenarioDef(
            id = "bin-assign-probe",
            titleKey = "tool.title",
            layout = TutorialTestFixtures.dummyLayout(),
            steps = listOf(
                TutorialStep.Quiz(
                    promptKey = "quiz.prompt",
                    kind = QuizKind.BinAssign,
                    options = listOf(
                        QuizOption("t1", tileType = Tiles.CHAR_2),
                        QuizOption("t2", tileType = Tiles.DOT_8),
                    ),
                    bins = listOf(
                        QuizBin("char", "bin.char"),
                        QuizBin("dot", "bin.dot"),
                    ),
                    correctBins = mapOf("t1" to "char", "t2" to "dot"),
                ),
                TutorialStep.Complete,
            ),
            humanSeat = 0,
            showsBoard = false,
        )
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.awaitingQuiz)
        val quiz = runner.currentQuiz!!
        val wrong = quiz.correctBins.mapValues { (_, _) -> "char" }
        assertFalse(runner.submitQuiz(QuizAnswer(binByOptionId = wrong)))
        assertTrue(runner.awaitingQuiz)
        assertTrue(runner.submitQuiz(QuizAnswer(binByOptionId = quiz.correctBins)))
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun correctAnswerMatchesIsCorrectForEachKind() {
        val multi = TutorialStep.Quiz(
            promptKey = "q",
            kind = QuizKind.MultiChoice,
            options = listOf(
                QuizOption("a", labelKey = "opt.a"),
                QuizOption("b", labelKey = "opt.b"),
            ),
            correctIds = setOf("b"),
        )
        assertTrue(multi.isCorrect(multi.correctAnswer()))

        val honorsContent = QuizContentFactories.honorsHand.generate(Random(7))
        val tiles = TutorialStep.Quiz(
            promptKey = "q",
            kind = QuizKind.TileSelect,
            options = honorsContent.options,
            correctIds = honorsContent.correctIds,
        )
        assertTrue(tiles.isCorrect(tiles.correctAnswer()))

        val bin = TutorialStep.Quiz(
            promptKey = "q",
            kind = QuizKind.BinAssign,
            options = listOf(
                QuizOption("t1", tileType = Tiles.CHAR_2),
                QuizOption("t2", tileType = Tiles.DOT_8),
            ),
            bins = listOf(
                QuizBin("char", "bin.char"),
                QuizBin("dot", "bin.dot"),
            ),
            correctBins = mapOf("t1" to "char", "t2" to "dot"),
        )
        assertTrue(bin.isCorrect(bin.correctAnswer()))
    }

    @Test
    fun honorsQuizDrawsThirteenTilesWithAtLeastThreeHonors() {
        for (seed in 0..40) {
            val content = QuizContentFactories.honorsHand.generate(Random(seed))
            assertEquals(13, content.options.size, "seed=$seed")
            val honorCount = content.options.count {
                QuizContentFactories.isHonor(it.tileType!!)
            }
            assertTrue(honorCount >= 3, "seed=$seed honors=$honorCount")
            assertEquals(honorCount, content.correctIds.size, "seed=$seed")
            assertEquals(13, content.options.map { it.id }.toSet().size)
        }
    }

    @Test
    fun oneSequenceQuizDrawsThirteenTilesWithExactlyOneSequence() {
        for (seed in 0..40) {
            val content = QuizContentFactories.oneSequenceHand.generate(Random(seed))
            assertEquals(13, content.options.size, "seed=$seed")
            assertEquals(3, content.correctIds.size, "seed=$seed")
            assertTrue(
                content.options.none { QuizContentFactories.isHonor(it.tileType!!) },
                "seed=$seed",
            )
            val types = content.options.map { it.tileType!! }.toIntArray()
            val counts = IntArray(34)
            for (t in types) counts[t]++
            val shapes = QuizContentFactories.sequenceShapesPresent(counts)
            assertEquals(1, shapes.size, "seed=$seed shapes=$shapes")
            val shape = shapes[0]
            val base = (shape / 7) * 9
            val start = shape % 7
            for (d in 0..2) {
                assertEquals(1, counts[base + start + d], "seed=$seed rank ${base + start + d}")
            }
            val correctTypes = content.correctIds.map { id ->
                content.options.first { it.id == id }.tileType!!
            }.sorted()
            assertEquals(
                listOf(base + start, base + start + 1, base + start + 2),
                correctTypes,
                "seed=$seed",
            )
            assertEquals(13, content.options.map { it.id }.toSet().size)
        }
    }

    @Test
    fun dynamicQuizRetryAfterCorrectAndNewHandOnTryAgain() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.dynamicTileSelectQuiz(),
            choiceRng = Random(11),
            blockOnCoach = true,
        )
        assertTrue(runner.awaitingCoach)
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.awaitingQuiz)
        val first = runner.currentQuiz!!
        assertEquals(13, first.options.size)
        assertTrue(first.correctIds.isNotEmpty())
        val firstTiles = first.options.map { it.tileType }
        assertTrue(runner.submitQuiz(first.correctAnswer()))
        assertTrue(runner.awaitingQuiz)
        assertTrue(runner.quizAwaitingAdvance)
        assertTrue(runner.retryQuiz())
        assertFalse(runner.quizAwaitingAdvance)
        assertTrue(runner.awaitingQuiz)
        val second = runner.currentQuiz!!
        assertEquals(13, second.options.size)
        assertTrue(
            firstTiles != second.options.map { it.tileType },
            "Try again must draw a new hand, not replay the confirmed one",
        )
        assertTrue(runner.submitQuiz(second.correctAnswer()))
        assertTrue(runner.advanceQuiz())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun dynamicTryAgainAfterColdRestoreDoesNotReplaySavedHand() {
        val scenario = TutorialTestFixtures.dynamicTileSelectQuiz()
        val runner = TutorialRunner.start(
            scenario,
            choiceRng = Random(0),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        val live = runner.currentQuiz!!
        val savedTiles = live.options.map { it.tileType!! }
        assertTrue(runner.submitQuiz(live.correctAnswer()))
        val snapshot = runner.captureSnapshot(
            quizInteraction = QuizInteractionState(
                answer = QuizAnswer(selectedIds = live.correctIds),
                answerRevealed = true,
                passed = true,
            ),
        )
        val restored = TutorialRunner.restore(
            scenario,
            snapshot,
            choiceRng = Random(0),
            blockOnCoach = true,
        )!!
        assertTrue(restored.quizAwaitingAdvance)
        assertEquals(savedTiles, restored.currentQuiz!!.options.map { it.tileType!! })
        assertTrue(restored.retryQuiz())
        val retried = restored.currentQuiz!!.options.map { it.tileType!! }
        assertTrue(
            savedTiles != retried,
            "after restore+Try again expected new hand, got saved hand again: $savedTiles",
        )
    }

    @Test
    fun dynamicQuizHandPersistsAcrossMidLessonRestore() {
        val scenario = TutorialTestFixtures.dynamicTileSelectQuiz()
        val runner = TutorialRunner.start(
            scenario,
            choiceRng = Random(99),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        val live = runner.currentQuiz!!
        val tiles = live.options.map { it.tileType!! }
        val snapshot = runner.captureSnapshot()
        assertNotNull(snapshot.quizHandTiles)
        assertTrue(snapshot.quizHandTiles.contentEquals(tiles.toIntArray()))
        val restored = TutorialRunner.restore(
            scenario,
            snapshot,
            choiceRng = Random(0),
            blockOnCoach = true,
        )!!
        assertTrue(restored.awaitingQuiz)
        val restoredQuiz = restored.currentQuiz!!
        assertEquals(tiles, restoredQuiz.options.map { it.tileType!! })
        assertEquals(live.correctIds, restoredQuiz.correctIds)
    }

    @Test
    fun quizInteractionSurvivesMidLessonRestore() {
        val scenario = TutorialTestFixtures.dynamicTileSelectQuiz()
        val runner = TutorialRunner.start(
            scenario,
            choiceRng = Random(42),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        val live = runner.currentQuiz!!
        val interaction = QuizInteractionState(
            answer = QuizAnswer(selectedIds = live.correctIds),
            answerRevealed = true,
            failVisible = false,
            passed = true,
        )
        val snapshot = runner.captureSnapshot(quizInteraction = interaction)
        assertEquals(true, snapshot.quizInteraction?.passed)
        val restored = TutorialRunner.restore(
            scenario,
            snapshot,
            choiceRng = Random(0),
            blockOnCoach = true,
        )!!
        assertTrue(restored.awaitingQuiz)
        assertTrue(restored.quizAwaitingAdvance)
        assertEquals(interaction, restored.captureSnapshot().quizInteraction)
    }

    @Test
    fun confirmWrongAnswerStillOffersNextAndTryAgain() {
        val runner = TutorialRunner.start(
            TutorialTestFixtures.dynamicTileSelectQuiz(),
            choiceRng = Random(7),
            blockOnCoach = true,
        )
        assertTrue(runner.acknowledgeCoach())
        assertTrue(runner.currentQuiz!!.options.isNotEmpty())
        val wrong = QuizAnswer(selectedIds = emptySet())
        assertTrue(runner.submitQuiz(wrong))
        assertTrue(runner.quizAwaitingAdvance)
        assertTrue(runner.quizFeedbackFail)
        assertTrue(runner.retryQuiz())
        assertFalse(runner.quizAwaitingAdvance)
        assertFalse(runner.quizFeedbackFail)
        assertTrue(runner.submitQuiz(runner.currentQuiz!!.correctAnswer()))
        assertTrue(runner.advanceQuiz())
        assertEquals(TutorialDirector.Status.Completed, runner.status)
    }

    @Test
    fun legacyRevealedWithoutPassedRestoresToConfirmDockNotGreenPlusConfirm() {
        val quiz = TutorialTestFixtures.staticTileSelectQuiz()
        val legacy = QuizInteractionState(
            answer = QuizAnswer(selectedIds = setOf("1", "2")),
            answerRevealed = true,
            showAnswerConsumed = true,
            passed = false,
        )
        val normalized = legacy.normalized(quiz)
        assertTrue(normalized.passed)
        assertTrue(normalized.answerRevealed)
        assertFalse(normalized.failVisible)

        // Post-restore review marks come from the runner-owned attempt, not the widget.
        assertTrue(assertNotNull(quiz.reviewFor(normalized)).fullyCorrect)
        // A fresh attempt (Try again) shows no review marks.
        assertNull(quiz.reviewFor(QuizInteractionState.Fresh))
    }

    @Test
    fun midSelectionRestoreDoesNotShowReviewMarks() {
        val quiz = TutorialTestFixtures.staticTileSelectQuiz()
        val attempt = QuizInteractionState(
            answer = QuizAnswer(selectedIds = setOf("1", "2")),
        )

        assertNull(quiz.reviewFor(attempt))
        assertEquals(setOf("1", "2"), attempt.answer.selectedIds)
    }

    @Test
    fun confirmKeepsSelectionAndShowsHitMissReview() {
        val quiz = TutorialTestFixtures.staticTileSelectQuiz()
        val confirmed = QuizInteractionState(
            answer = QuizAnswer(selectedIds = setOf("0", "1")),
            answerRevealed = true,
            passed = true,
        )

        val review = assertNotNull(quiz.reviewFor(confirmed))
        assertFalse(review.fullyCorrect)
        assertEquals(setOf("1"), review.hitIds)
        assertEquals(setOf("0"), review.falsePositiveIds)
        assertEquals(setOf("2"), review.missIds)

        assertNull(quiz.reviewFor(QuizInteractionState.Fresh))
    }

    @Test
    fun confirmCorrectShowsFullyCorrectReview() {
        val content = QuizContentFactories.honorsHand.generate(Random(3))
        val quiz = TutorialStep.Quiz(
            promptKey = "quiz.prompt",
            kind = QuizKind.TileSelect,
            options = content.options,
            correctIds = content.correctIds,
            retryAfterCorrect = true,
        )
        val confirmed = QuizInteractionState(
            answer = QuizAnswer(selectedIds = content.correctIds),
            answerRevealed = true,
            passed = true,
        )

        val review = assertNotNull(quiz.reviewFor(confirmed))
        assertTrue(review.fullyCorrect)
        assertEquals(content.correctIds, review.hitIds)
        assertTrue(review.falsePositiveIds.isEmpty())
        assertTrue(review.missIds.isEmpty())
    }
}
