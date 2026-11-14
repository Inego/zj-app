package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.platform.newRandom
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.ChoiceGate
import kotlin.random.Random

/**
 * Scenario FSM: walks [ScenarioDef.steps], installs gates, waits for human
 * choices / quiz answers, applies scripted AI, asserts state, completes or fails.
 *
 * **Headless** ([blockOnCoach] = false): coach auto-advance; quizzes auto-solve.
 * **UI** ([blockOnCoach] = true): coach / quiz block until acknowledged / answered.
 */
class TutorialDirector(
    val scenario: ScenarioDef,
    val state: GameState,
    val board: BoardController,
    /**
     * When true, Coach and Quiz steps pause until UI acknowledges / answers.
     * Headless tests leave this false.
     */
    var blockOnCoach: Boolean = false,
    /** Used for dynamic quiz hands ([TutorialStep.Quiz.contentFactory]). */
    var quizRng: Random = newRandom(),
) {
    enum class Status {
        Running,
        Completed,
        Failed,
    }

    var status: Status = Status.Running
        private set

    var stepIndex: Int = 0
        private set

    var failureMessage: String? = null
        private set

    var lastCoachKey: String? = null
        private set

    /** Active UI focus (diagram tile / board target + optional callout). */
    var lastFocus: TutorialFocus? = null
        private set

    /** Target id only — board caption chrome and older call sites. */
    val lastSpotlight: String?
        get() = lastFocus?.targetId

    var lastDiagram: TutorialDiagram = TutorialDiagram.None
        private set

    var awaitingCoach: Boolean = false
        private set

    var awaitingQuiz: Boolean = false
        private set

    /**
     * Live quiz instance while [awaitingQuiz] (static step or materialized factory).
     * Prefer this over [currentStep] for UI / answer checks.
     */
    var resolvedQuiz: TutorialStep.Quiz? = null
        private set

    /**
     * Correct answer confirmed on a [TutorialStep.Quiz.retryAfterCorrect] quiz;
     * waiting for Next or Try again.
     */
    var quizAwaitingAdvance: Boolean = false
        private set

    /** Current quiz step while [awaitingQuiz], else null. */
    val currentQuiz: TutorialStep.Quiz?
        get() = if (awaitingQuiz) resolvedQuiz ?: currentStep as? TutorialStep.Quiz else null

    /** Set after a wrong quiz submit; UI shows generic Not quite status. */
    var quizFeedbackFail: Boolean = false
        private set

    var pendingLinkPageId: String? = null
        private set

    fun consumePendingLinkRef(): String? {
        val id = pendingLinkPageId
        pendingLinkPageId = null
        return id
    }

    val currentStep: TutorialStep?
        get() = scenario.steps.getOrNull(stepIndex)

    val isFinished: Boolean
        get() = status == Status.Completed || status == Status.Failed

    /**
     * True when human board commits must not run (coach, quiz, finished, no-board wait).
     * Mid-lesson [TutorialStep.Coach.showsBoard] still blocks input while the coach is up.
     */
    val blocksBoardInput: Boolean
        get() = awaitingCoach || awaitingQuiz || isFinished ||
            !(scenario.showsBoard || (currentStep as? TutorialStep.Coach)?.showsBoard == true)

    /**
     * Tile types of the live hand for mid-lesson save (dynamic tile quizzes only).
     */
    fun quizHandTiles(): IntArray? {
        val template = currentStep as? TutorialStep.Quiz ?: return null
        if (template.contentFactory == null) return null
        val live = resolvedQuiz ?: return null
        val types = live.options.mapNotNull { it.tileType }
        if (types.isEmpty() || types.size != live.options.size) return null
        return types.toIntArray()
    }

    fun pumpAuto() {
        if (isFinished) return
        if (awaitingCoach || awaitingQuiz) return
        while (!isFinished) {
            val step = currentStep
            if (step == null) {
                fail("ran past end of steps without Complete")
                return
            }
            when (step) {
                is TutorialStep.Coach -> {
                    lastCoachKey = step.textKey
                    lastFocus = step.resolvedFocus()
                    lastDiagram = step.diagram
                    if (step.layout != null) {
                        state.copyFrom(step.layout.materialize())
                    }
                    if (blockOnCoach) {
                        awaitingCoach = true
                        return
                    }
                    advance()
                }
                is TutorialStep.Quiz -> {
                    lastFocus = step.spotlight?.takeIf { it.isNotBlank() }?.let { TutorialFocus(it) }
                    lastDiagram = step.diagram
                    quizFeedbackFail = false
                    quizAwaitingAdvance = false
                    val live = materializeQuiz(step)
                    resolvedQuiz = live
                    if (blockOnCoach) {
                        awaitingQuiz = true
                        return
                    }
                    // Headless: auto-pass with a correct answer (skip retry UX).
                    if (!live.isCorrect(autoAnswer(live))) {
                        fail("quiz auto-answer invalid for ${step.promptKey}")
                        return
                    }
                    clearQuizUiState()
                    advance()
                }
                is TutorialStep.Gate -> {
                    board.gate = if (step.allowKinds.isEmpty()) {
                        ChoiceGate.Identity
                    } else {
                        KindChoiceGate(step.allowKinds)
                    }
                    advance()
                }
                is TutorialStep.WaitChoice -> {
                    board.gate = ChoiceMatchGate(step.match)
                    return
                }
                is TutorialStep.ScriptChoice -> {
                    if (!applyScripted(step.choice)) {
                        fail("scripted choice not legal: ${step.choice}")
                        return
                    }
                    advance()
                }
                is TutorialStep.AssertState -> {
                    if (!step.predicate(state)) {
                        fail(step.message)
                        return
                    }
                    advance()
                }
                is TutorialStep.LinkRef -> {
                    pendingLinkPageId = step.pageId
                    advance()
                }
                TutorialStep.Complete -> {
                    board.gate = ChoiceGate.Identity
                    status = Status.Completed
                    return
                }
            }
        }
    }

    fun acknowledgeCoach(): Boolean {
        if (status != Status.Running || !awaitingCoach) return false
        awaitingCoach = false
        lastDiagram = TutorialDiagram.None
        lastFocus = null
        advance()
        pumpAuto()
        return true
    }

    /** Clear progress so [TutorialRunner] can replay from step 0 on the same instance. */
    fun resetToStart() {
        status = Status.Running
        stepIndex = 0
        failureMessage = null
        lastCoachKey = null
        lastFocus = null
        lastDiagram = TutorialDiagram.None
        awaitingCoach = false
        awaitingQuiz = false
        resolvedQuiz = null
        quizAwaitingAdvance = false
        quizFeedbackFail = false
        pendingLinkPageId = null
        board.gate = ChoiceGate.Identity
    }

    /**
     * Land on a Coach / Quiz without replaying the board: restore chrome + gates
     * from that step. Caller must ensure no [TutorialStep.WaitChoice] /
     * [TutorialStep.ScriptChoice] ran between here and [targetStepIndex].
     */
    fun rewindToUserSlide(targetStepIndex: Int): Boolean {
        val step = scenario.steps.getOrNull(targetStepIndex) ?: return false
        return TutorialSlideNav.isUserSlide(step) && applyRestoredProgress(
            restoredStepIndex = targetStepIndex,
            restoredStatus = Status.Running,
            restoredAwaitingCoach = step is TutorialStep.Coach,
            restoredAwaitingQuiz = step is TutorialStep.Quiz,
            restoredQuizFeedbackFail = false,
            restoredLastCoachKey = (step as? TutorialStep.Coach)?.textKey,
            restoredLastFocus = when (step) {
                is TutorialStep.Coach -> step.resolvedFocus()
                is TutorialStep.Quiz ->
                    step.spotlight?.takeIf { it.isNotBlank() }?.let { TutorialFocus(it) }

                else -> null
            },
            restoredLastDiagram = when (step) {
                is TutorialStep.Coach -> step.diagram
                is TutorialStep.Quiz -> step.diagram
                else -> TutorialDiagram.None
            },
            restoredFailureMessage = null,
            restoredPendingLinkPageId = null,
            restoredQuizHandTiles = null,
            restoredQuizAwaitingAdvance = false,
        )
    }

    /**
     * Submit a quiz answer.
     *
     * - [TutorialStep.Quiz.retryAfterCorrect]: always enters [quizAwaitingAdvance]
     *   (Next / Try again) whether right or wrong; [quizFeedbackFail] records
     *   correctness for UI review. Selection review is host-side.
     * - Otherwise: wrong leaves the step active with [quizFeedbackFail]; correct
     *   advances immediately.
     */
    fun submitQuiz(answer: QuizAnswer): Boolean {
        if (status != Status.Running || !awaitingQuiz || quizAwaitingAdvance) return false
        val template = currentStep as? TutorialStep.Quiz ?: return false
        val live = resolvedQuiz ?: materializeQuiz(template).also { resolvedQuiz = it }
        val correct = live.isCorrect(answer)
        if (template.retryAfterCorrect) {
            quizFeedbackFail = !correct
            quizAwaitingAdvance = true
            return true
        }
        if (!correct) {
            quizFeedbackFail = true
            return false
        }
        quizFeedbackFail = false
        finishQuizAndAdvance()
        return true
    }

    /** Next after a [retryAfterCorrect] quiz (any confirm). */
    fun advanceQuiz(): Boolean {
        if (status != Status.Running || !awaitingQuiz || !quizAwaitingAdvance) return false
        finishQuizAndAdvance()
        return true
    }

    /**
     * Try again: new hand (when factory present) or same static content, clear
     * post-confirm state.
     *
     * Dynamic factories always draw a fresh hand. After a cold restore the RNG
     * is often re-seeded while the saved hand is replayed from tiles — a single
     * [QuizContentFactory.generate] can then redraw the same first hand. We
     * reject identical tile multisets so Try again never leaves the learner on
     * the hand they just confirmed.
     */
    fun retryQuiz(): Boolean {
        if (status != Status.Running || !awaitingQuiz || !quizAwaitingAdvance) return false
        val template = currentStep as? TutorialStep.Quiz ?: return false
        if (!template.retryAfterCorrect) return false
        quizAwaitingAdvance = false
        quizFeedbackFail = false
        val previousTiles = resolvedQuiz?.let { quizTileFingerprint(it) }
        var next = materializeQuiz(template)
        if (template.contentFactory != null && previousTiles != null) {
            // Bound redraws so a pathological factory cannot spin forever.
            var attempts = 0
            while (quizTileFingerprint(next) == previousTiles && attempts < 24) {
                next = materializeQuiz(template)
                attempts++
            }
        }
        resolvedQuiz = next
        return true
    }

    /** Stable compare key for "is this the same physical hand?" on tile quizzes. */
    private fun quizTileFingerprint(quiz: TutorialStep.Quiz): List<Int>? {
        val types = quiz.options.map { it.tileType ?: return null }
        return types
    }

    /**
     * Rebuild mid-lesson progress after cold-start restore (no step re-run).
     * Reinstalls [board] gate from scenario history + current step.
     *
     * @return false if [restoredStepIndex] is out of range for this scenario
     */
    fun applyRestoredProgress(
        restoredStepIndex: Int,
        restoredStatus: Status,
        restoredAwaitingCoach: Boolean,
        restoredAwaitingQuiz: Boolean,
        restoredQuizFeedbackFail: Boolean,
        restoredLastCoachKey: String?,
        restoredLastFocus: TutorialFocus?,
        restoredLastDiagram: TutorialDiagram,
        restoredFailureMessage: String?,
        restoredPendingLinkPageId: String?,
        restoredQuizHandTiles: IntArray? = null,
        restoredQuizAwaitingAdvance: Boolean = false,
    ): Boolean {
        if (restoredStepIndex < 0 || restoredStepIndex > scenario.steps.size) return false
        if (restoredStatus == Status.Running && restoredStepIndex >= scenario.steps.size) {
            return false
        }
        stepIndex = restoredStepIndex
        status = restoredStatus
        awaitingCoach = restoredAwaitingCoach && restoredStatus == Status.Running
        awaitingQuiz = restoredAwaitingQuiz && restoredStatus == Status.Running
        quizFeedbackFail = restoredQuizFeedbackFail && awaitingQuiz && !restoredQuizAwaitingAdvance
        lastCoachKey = restoredLastCoachKey
        lastFocus = restoredLastFocus
        lastDiagram = restoredLastDiagram
        failureMessage = restoredFailureMessage
        pendingLinkPageId = restoredPendingLinkPageId
        quizAwaitingAdvance = false
        resolvedQuiz = null
        if (awaitingQuiz) {
            val template = currentStep as? TutorialStep.Quiz
            if (template == null) {
                awaitingQuiz = false
            } else {
                resolvedQuiz = materializeQuiz(template, restoredQuizHandTiles)
                quizAwaitingAdvance =
                    restoredQuizAwaitingAdvance && template.retryAfterCorrect
                if (quizAwaitingAdvance) quizFeedbackFail = false
            }
        }
        reinstallGateFromHistory()
        return true
    }

    private fun materializeQuiz(
        template: TutorialStep.Quiz,
        handTiles: IntArray? = null,
    ): TutorialStep.Quiz {
        val factory = template.contentFactory ?: return template
        val content = if (handTiles != null && handTiles.isNotEmpty()) {
            factory.rematerialize(handTiles)
        } else {
            factory.generate(quizRng)
        }
        return template.withContent(content)
    }

    private fun finishQuizAndAdvance() {
        clearQuizUiState()
        advance()
        pumpAuto()
    }

    private fun clearQuizUiState() {
        awaitingQuiz = false
        quizAwaitingAdvance = false
        quizFeedbackFail = false
        resolvedQuiz = null
        lastDiagram = TutorialDiagram.None
        lastFocus = null
    }

    /**
     * Re-apply sticky [Gate] / current [WaitChoice] effects without replaying
     * board mutations (those come from the saved [GameState]).
     */
    fun reinstallGateFromHistory() {
        board.gate = ChoiceGate.Identity
        if (isFinished) return
        val end = stepIndex.coerceAtMost(scenario.steps.lastIndex)
        for (i in 0..end) {
            when (val step = scenario.steps[i]) {
                is TutorialStep.Gate -> {
                    board.gate = if (step.allowKinds.isEmpty()) {
                        ChoiceGate.Identity
                    } else {
                        KindChoiceGate(step.allowKinds)
                    }
                }
                is TutorialStep.WaitChoice -> {
                    // Live director leaves the match gate after the wait completes;
                    // while still on the wait step, the same gate is required.
                    if (i < stepIndex || (i == stepIndex && status == Status.Running)) {
                        board.gate = ChoiceMatchGate(step.match)
                    }
                }
                TutorialStep.Complete -> board.gate = ChoiceGate.Identity
                else -> Unit
            }
        }
        if (isFinished) board.gate = ChoiceGate.Identity
    }

    fun submitChoice(requested: Choice): Boolean {
        if (status != Status.Running || awaitingCoach || awaitingQuiz) return false
        val step = currentStep as? TutorialStep.WaitChoice ?: return false
        if (!step.match.matches(requested)) return false
        if (!board.tryApply(state, requested)) return false
        advance()
        pumpAuto()
        return true
    }

    fun submitDiscard(tileType: Int): Boolean {
        if (status != Status.Running || awaitingCoach || awaitingQuiz) return false
        val step = currentStep as? TutorialStep.WaitChoice ?: return false
        if (step.match.kind != inego.zungjunggame.engine.ChoiceKind.Discard) return false
        if (step.match.payload != null && step.match.payload != tileType) return false
        if (!board.tryApplyDiscard(state, tileType)) return false
        advance()
        pumpAuto()
        return true
    }

    private fun autoAnswer(step: TutorialStep.Quiz): QuizAnswer = step.correctAnswer()

    private fun applyScripted(requested: Choice): Boolean {
        val previous = board.gate
        board.gate = ChoiceGate.Identity
        try {
            return board.tryApply(state, requested)
        } finally {
            board.gate = previous
        }
    }

    private fun advance() {
        stepIndex++
    }

    private fun fail(message: String) {
        status = Status.Failed
        failureMessage = message
        awaitingCoach = false
        awaitingQuiz = false
        quizAwaitingAdvance = false
        quizFeedbackFail = false
        resolvedQuiz = null
        board.gate = ChoiceGate.Identity
    }
}
