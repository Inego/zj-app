package inego.zungjunggame.features.tutorial

import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialSlideNav
import inego.zungjunggame.tutorial.TutorialStep

/**
 * One frame's orientation-quiz scene: the beat [inego.zungjunggame.tutorial.TutorialHost] used to
 * push through `TutorialQuizOverlay.show`, plus the attempt that used to live only on the widget.
 *
 * Assembled by [inego.zungjunggame.tutorial.TutorialHost.snapshot] from the runner / director /
 * `heldQuizInteraction` and put on the [TutorialSnapshot] the frame carries. The widget applies it;
 * it keeps no authoritative copy.
 */
data class TutorialQuizSnapshot(
    val visible: Boolean = false,
    val titleKey: String = "",
    val quiz: TutorialStep.Quiz? = null,
    val offersBack: Boolean = false,
    /** Director [inego.zungjunggame.tutorial.TutorialDirector.quizFeedbackFail]. */
    val failVisible: Boolean = false,
    /** Director [inego.zungjunggame.tutorial.TutorialDirector.quizAwaitingAdvance]. */
    val passed: Boolean = false,
    /** Persistable mid-attempt state; [QuizInteractionState.Fresh] before any edit. */
    val attempt: QuizInteractionState = QuizInteractionState.Fresh,
) {
    /**
     * Identity of the quiz content, so a no-op frame does not restart the enter
     * fade and a new hand (Try again) resets the attempt. Director flags are not
     * part of it: a Confirm on the same content keeps the attempt.
     */
    val contentKey: String
        get() {
            val q = quiz ?: return "none"
            return q.promptKey + "|" + quizOptionsFingerprint(q)
        }

    companion object {
        val Absent: TutorialQuizSnapshot = TutorialQuizSnapshot()
    }
}

/** Stable fingerprint of a quiz's live options (Try again redraws a new set). */
fun quizOptionsFingerprint(quiz: TutorialStep.Quiz): String =
    quiz.options.joinToString("|") { "${it.id}:${it.tileType}:${it.labelKey}" }

/**
 * The runner's current quiz beat as a snapshot, or [TutorialQuizSnapshot.Absent]
 * when no quiz is current.
 *
 * Pure over the runner's director facts and held attempt; the host announces the
 * route and persists the emitted attempt, but reads nothing off the widget.
 */
fun tutorialQuizContent(active: TutorialRunner): TutorialQuizSnapshot {
    val quiz = active.currentQuiz
    if (!active.awaitingQuiz || quiz == null) return TutorialQuizSnapshot.Absent
    val passed = active.quizAwaitingAdvance
    val attempt = (active.heldQuizInteractionOrNull() ?: QuizInteractionState()).normalized(
        quiz = quiz,
        directorAwaitingAdvance = passed,
        directorFail = active.quizFeedbackFail,
    )
    return TutorialQuizSnapshot(
        visible = true,
        titleKey = active.scenario.titleKey,
        quiz = quiz,
        offersBack = TutorialSlideNav.canGoBack(active),
        failVisible = active.quizFeedbackFail && !passed,
        passed = passed,
        attempt = attempt,
    )
}
