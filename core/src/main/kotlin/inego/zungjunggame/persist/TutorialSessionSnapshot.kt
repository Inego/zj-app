package inego.zungjunggame.persist

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialFocus

/**
 * In-progress (or just-finished, still hosted) tutorial unit that survives
 * app exit / process death — peer to free-play [SessionSnapshot].
 *
 * The lesson board is its own, independent of a free-play one: the mid-lesson
 * file is kept when starting a free-play game so reopening Tutorial restores
 * this snapshot, and a parked local game's `session.freeplay` save is kept in
 * turn when the tutorial opens. Campaign progress (completed unit ids) lives in
 * [inego.zungjunggame.tutorial.TutorialProgress] and also survives free-play.
 */
data class TutorialSessionSnapshot(
    val lessonId: String,
    val stepIndex: Int,
    val status: TutorialDirector.Status,
    val awaitingCoach: Boolean,
    val awaitingQuiz: Boolean,
    val quizFeedbackFail: Boolean,
    val lastCoachKey: String?,
    val lastFocus: TutorialFocus?,
    val lastDiagram: TutorialDiagram,
    val failureMessage: String?,
    val pendingLinkPageId: String?,
    /** Live board (always present; orientation units may use a dummy layout). */
    val game: GameState,
    /**
     * Wall-build deals parked by lesson id (last committed dice + map).
     * Survives leaving the wall unit so recover on a later lesson restores
     * the same hands. Empty when the current beat is pre-roll (ClearRoll)
     * or no deal has been committed this session.
     */
    val wallBuildDeals: Map<String, WallBuildDeal> = emptyMap(),
    /**
     * Live dynamic quiz hand (tile types in option order) while mid-quiz.
     * Null when not on a factory-backed tile quiz.
     */
    val quizHandTiles: IntArray? = null,
    /**
     * Correct answer confirmed; waiting for Next / Try again on
     * [inego.zungjunggame.tutorial.TutorialStep.Quiz.retryAfterCorrect] quizzes.
     * Prefer [quizInteraction].passed when that blob is present.
     */
    val quizAwaitingAdvance: Boolean = false,
    /**
     * Full mid-attempt quiz UI state (selection, Show answer, fail, pass).
     * Kind-agnostic — same shape for every quiz. Null when not mid-quiz or
     * when the host has not captured overlay state yet.
     */
    val quizInteraction: QuizInteractionState? = null,
)
