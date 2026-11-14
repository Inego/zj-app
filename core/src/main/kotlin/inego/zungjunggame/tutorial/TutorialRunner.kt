package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.persist.TutorialSessionSnapshot
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.ChoiceGate
import inego.zungjunggame.session.LiveHand
import kotlin.random.Random

/**
 * Lesson host: materializes layout, drives [TutorialDirector] on a
 * [BoardController], implements [LiveHand] for Main wiring.
 *
 * Pass Main's shared [BoardController] as [board] so director gates apply to the
 * same commit path as free play. Headless tests may omit [board] and
 * get a private controller.
 *
 * [ScenarioDef.showsBoard] false = orientation: table chrome skipped in Main.
 *
 * Mid-lesson recovery: [captureSnapshot] / [restore] via [TutorialSessionSnapshot]
 * (separate file from free-play the `session.freeplay` key).
 */
class TutorialRunner private constructor(
    override val scenario: ScenarioDef,
    private val gameState: GameState,
    override val humanSeat: Int,
    val board: BoardController,
    val director: TutorialDirector,
    override val choiceRng: Random,
) : LiveHand, SlideCursor {

    override val stepIndex: Int
        get() = director.stepIndex

    /**
     * Last full quiz attempt state from overlay capture or cold restore.
     * Lets headless [captureSnapshot] keep selection / confirm flags without
     * an overlay. Cleared when the quiz step is left or retried.
     */
    private var heldQuizInteraction: QuizInteractionState? = null

    /** Peek held mid-attempt state (cold restore / host re-show). */
    fun heldQuizInteractionOrNull(): QuizInteractionState? = heldQuizInteraction

    /**
     * Record a mid-attempt edit the widget emitted (selection / bin / staged bin).
     * The next [captureSnapshot] and the frame's quiz snapshot read it; the
     * director's own [submitQuiz] / [retryQuiz] / [advanceQuiz] overwrite it.
     */
    fun holdQuizInteraction(state: QuizInteractionState?) {
        heldQuizInteraction = state
    }

    /**
     * Whether Main should draw the live table: scenario-wide flag **or** the
     * current coach step opts in ([TutorialStep.Coach.showsBoard]).
     */
    val showsBoard: Boolean
        get() = scenario.showsBoard || currentCoachShowsBoard()

    /** Absolute seat indices whose hands should be revealed face-up for the current step. */
    val revealedSeats: Set<Int>
        get() = (director.currentStep as? TutorialStep.Coach)?.revealedSeats ?: scenario.revealedSeats

    /** True when the live coach step requests a board under overlay chrome. */
    fun currentCoachShowsBoard(): Boolean =
        (director.currentStep as? TutorialStep.Coach)?.showsBoard == true

    override val state: GameState?
        get() = if (showsBoard) gameState else null

    /**
     * Overwrite the shared engine state (runner + director hold the same
     * instance). Used for wall-build → live table handoff mid-lesson.
     */
    fun rematerializeState(source: GameState) {
        gameState.copyFrom(source)
    }

    /**
     * True while the lesson is still accepting director-driven play.
     * After Completed / Failed, false — Main may keep the runner until exit.
     */
    override val active: Boolean
        get() = !director.isFinished

    val status: TutorialDirector.Status
        get() = director.status

    val awaitingCoach: Boolean
        get() = director.awaitingCoach

    val awaitingQuiz: Boolean
        get() = director.awaitingQuiz

    /**
     * Current overlay coach is the preamble to a human [TutorialStep.WaitChoice].
     * Clicking the expected action or pressing Next both complete that wait.
     * Other board overlays are lectures sitting on the same engine turn — they
     * are not play.
     */
    fun isPreambleToHumanWait(): Boolean =
        awaitingCoach && peekNextWaitChoice() != null

    /**
     * Discard specialization of [isPreambleToHumanWait]: the host keeps a
     * hand-tile leader balloon instead of a prompt outline.
     */
    fun isPreambleToHumanDiscard(): Boolean =
        isPreambleToHumanWait() && peekNextWaitChoice()?.match?.kind == ChoiceKind.Discard

    /** Next non-Gate step if it is a [TutorialStep.WaitChoice], else null. */
    fun peekNextWaitChoice(): TutorialStep.WaitChoice? {
        val steps = scenario.steps
        var i = director.stepIndex + 1
        while (i < steps.size) {
            when (val step = steps[i]) {
                is TutorialStep.Gate -> i++
                is TutorialStep.WaitChoice -> return step
                else -> return null
            }
        }
        return null
    }

    /**
     * Next on an action-preamble overlay: ack the coach and apply the teaching
     * choice so the learner lands on the beat after the wait, not a play wait
     * with overlay chrome down.
     */
    fun completePreambleWait(): Boolean {
        val wait = peekNextWaitChoice() ?: return false
        if (!isPreambleToHumanWait()) return false
        val match = wait.match
        if (!acknowledgeCoach()) return false
        if (match.kind == ChoiceKind.Discard) {
            val tile = match.payload ?: return false
            return submitDiscard(tile)
        }
        val choice = board.allowedChoices(gameState).firstOrNull { match.matches(it) }
            ?: return false
        return submitChoice(choice)
    }

    val lastCoachKey: String?
        get() = director.lastCoachKey

    val lastSpotlight: String?
        get() = director.lastSpotlight

    val lastFocus: TutorialFocus?
        get() = director.lastFocus

    val lastDiagram: TutorialDiagram
        get() = director.lastDiagram

    val currentQuiz: TutorialStep.Quiz?
        get() = director.currentQuiz

    val quizFeedbackFail: Boolean
        get() = director.quizFeedbackFail

    val quizAwaitingAdvance: Boolean
        get() = director.quizAwaitingAdvance

    fun pumpAuto() = director.pumpAuto()

    fun submitChoice(choice: Choice): Boolean = director.submitChoice(choice)

    fun submitDiscard(tileType: Int): Boolean = director.submitDiscard(tileType)

    fun acknowledgeCoach(): Boolean = director.acknowledgeCoach()

    /**
     * Land this runner on a Coach / Quiz [targetStepIndex] without allocating a
     * new host. Next and Back both use this (or [acknowledgeCoach] for a single
     * forward step).
     *
     * When no wait / script sits between here and the target, only director
     * chrome is rewound — [GameState] is left alone so the live table does not
     * snap. Otherwise the layout is rematerialized and steps are replayed.
     *
     * A later [TutorialStep.Coach.layout] is a new snapshot. If the target's
     * sticky layout is *before* a wait/script that built the live board, Back
     * must replay rather than rematerialize that earlier snapshot (kong claim
     * then an already-melded contrast beat).
     */
    fun seekToUserSlide(targetStepIndex: Int): SeekResult {
        val steps = scenario.steps
        if (targetStepIndex !in steps.indices) return SeekResult.Failed
        if (!TutorialSlideNav.isUserSlide(steps[targetStepIndex])) return SeekResult.Failed
        val current = director.stepIndex
        if (targetStepIndex == current &&
            director.status == TutorialDirector.Status.Running &&
            (awaitingCoach || awaitingQuiz)
        ) {
            return SeekResult.Stayed
        }
        val canRewindChrome =
            (director.status == TutorialDirector.Status.Running ||
                director.status == TutorialDirector.Status.Completed) &&
                targetStepIndex <= current &&
                !TutorialSlideNav.mutatesBoardBetween(steps, targetStepIndex, current)
        if (canRewindChrome) {
            heldQuizInteraction = null
            val targetLayoutIdx = TutorialSlideNav.activeLayoutIndex(scenario, targetStepIndex)
            val currentLayoutIdx = TutorialSlideNav.activeLayoutIndex(scenario, current)
            if (targetLayoutIdx != currentLayoutIdx) {
                val afterSticky = targetLayoutIdx + 1
                if (TutorialSlideNav.mutatesBoardBetween(steps, afterSticky, targetStepIndex)) {
                    return replayFromStart(targetStepIndex)
                }
                gameState.copyFrom(TutorialSlideNav.activeLayout(scenario, targetStepIndex).materialize())
            }
            return if (director.rewindToUserSlide(targetStepIndex)) {
                SeekResult.Rewound
            } else {
                SeekResult.Failed
            }
        }
        if (targetStepIndex > current && director.status == TutorialDirector.Status.Running) {
            val prevBlock = director.blockOnCoach
            director.blockOnCoach = true
            try {
                seekForwardTo(this, targetStepIndex)
            } finally {
                director.blockOnCoach = prevBlock
            }
            heldQuizInteraction = null
            return if (landedOn(targetStepIndex)) SeekResult.Advanced else SeekResult.Failed
        }
        return replayFromStart(targetStepIndex)
    }

    private fun replayFromStart(targetStepIndex: Int): SeekResult {
        gameState.copyFrom(TutorialSlideNav.activeLayout(scenario, 0).materialize())
        val prevBlock = director.blockOnCoach
        director.resetToStart()
        director.blockOnCoach = true
        try {
            pumpAuto()
            seekForwardTo(this, targetStepIndex)
        } finally {
            director.blockOnCoach = prevBlock
        }
        heldQuizInteraction = null
        return if (landedOn(targetStepIndex)) SeekResult.Rebuilt else SeekResult.Failed
    }

    private fun landedOn(targetStepIndex: Int): Boolean =
        director.stepIndex == targetStepIndex &&
            director.status == TutorialDirector.Status.Running &&
            (awaitingCoach || awaitingQuiz)

    enum class SeekResult {
        /** Already on that user slide. */
        Stayed,
        /** Director chrome only — board instance untouched. */
        Rewound,
        /** Walked forward from the current step (may apply waits). */
        Advanced,
        /** Layout rematerialized and steps replayed from 0. */
        Rebuilt,
        Failed,
    }

    fun submitQuiz(answer: QuizAnswer): Boolean {
        val ok = director.submitQuiz(answer)
        if (!ok) {
            // Non-retryable wrong answer: keep selection; host refreshes via capture.
            return false
        }
        heldQuizInteraction = if (director.quizAwaitingAdvance) {
            QuizInteractionState(
                answer = answer,
                answerRevealed = true,
                failVisible = director.quizFeedbackFail,
                passed = true,
            )
        } else {
            null
        }
        return true
    }

    fun advanceQuiz(): Boolean {
        val ok = director.advanceQuiz()
        if (ok) heldQuizInteraction = null
        return ok
    }

    fun retryQuiz(): Boolean {
        val ok = director.retryQuiz()
        if (ok) heldQuizInteraction = null
        return ok
    }

    /**
     * Snapshot for the `session.tutorial` key (deep-copies board).
     *
     * @param quizInteraction full overlay attempt state when mid-quiz (all kinds)
     */
    fun captureSnapshot(
        quizInteraction: QuizInteractionState? = null,
    ): TutorialSessionSnapshot {
        if (quizInteraction != null) {
            heldQuizInteraction = quizInteraction
        }
        val interaction = when {
            !director.awaitingQuiz -> {
                heldQuizInteraction = null
                null
            }
            quizInteraction != null -> quizInteraction
            heldQuizInteraction != null -> heldQuizInteraction
            // Headless / no overlay yet: director pass/fail flags only.
            else -> QuizInteractionState(
                failVisible = director.quizFeedbackFail && !director.quizAwaitingAdvance,
                passed = director.quizAwaitingAdvance,
            )
        }
        return TutorialSessionSnapshot(
            lessonId = scenario.id,
            stepIndex = director.stepIndex,
            status = director.status,
            awaitingCoach = director.awaitingCoach,
            awaitingQuiz = director.awaitingQuiz,
            quizFeedbackFail = director.quizFeedbackFail,
            lastCoachKey = director.lastCoachKey,
            lastFocus = director.lastFocus,
            lastDiagram = director.lastDiagram,
            failureMessage = director.failureMessage,
            pendingLinkPageId = director.pendingLinkPageId,
            game = gameState.copy(),
            quizHandTiles = director.quizHandTiles(),
            quizAwaitingAdvance = director.quizAwaitingAdvance,
            quizInteraction = interaction,
        )
    }

    companion object {
        fun start(
            scenario: ScenarioDef,
            board: BoardController = headlessBoard(),
            choiceRng: Random = Random(0),
            blockOnCoach: Boolean = false,
        ): TutorialRunner {
            val state = TutorialSlideNav.activeLayout(scenario, 0).materialize()
            board.gate = ChoiceGate.Identity
            val director = TutorialDirector(
                scenario = scenario,
                state = state,
                board = board,
                blockOnCoach = blockOnCoach,
                quizRng = choiceRng,
            )
            val runner = TutorialRunner(
                scenario = scenario,
                gameState = state,
                humanSeat = scenario.humanSeat,
                board = board,
                director = director,
                choiceRng = choiceRng,
            )
            runner.pumpAuto()
            return runner
        }

        /**
         * Start a fresh lesson and land on [targetStepIndex] (a Coach / Quiz).
         * Used for cross-lesson Back / cold land — same-lesson Back seeks
         * in place via [seekToUserSlide] so the table is not torn down.
         *
         * Waits, quizzes, and coaches before the target are solved automatically so
         * board mutations match a normal forward run.
         */
        fun startAtStep(
            scenario: ScenarioDef,
            targetStepIndex: Int,
            board: BoardController = headlessBoard(),
            choiceRng: Random = Random(0),
            blockOnCoach: Boolean = true,
        ): TutorialRunner {
            require(targetStepIndex in scenario.steps.indices) {
                "targetStepIndex $targetStepIndex out of range for ${scenario.id}"
            }
            val target = scenario.steps[targetStepIndex]
            require(TutorialSlideNav.isUserSlide(target)) {
                "target step $targetStepIndex is not a user slide (${target::class.simpleName})"
            }
            val runner = start(
                scenario = scenario,
                board = board,
                choiceRng = choiceRng,
                blockOnCoach = true,
            )
            val result = runner.seekToUserSlide(targetStepIndex)
            require(result != SeekResult.Failed) {
                "seekToUserSlide($targetStepIndex) failed for ${scenario.id}"
            }
            // Caller may want headless; flip only after land.
            runner.director.blockOnCoach = blockOnCoach
            if (!blockOnCoach &&
                !runner.director.isFinished &&
                !runner.awaitingCoach &&
                !runner.awaitingQuiz
            ) {
                runner.pumpAuto()
            }
            return runner
        }

        /**
         * Auto-advance from the first blocking step until [targetStepIndex] is
         * the live coach/quiz (or the run finishes early).
         */
        private fun seekForwardTo(runner: TutorialRunner, targetStepIndex: Int) {
            var guard = 0
            while (guard++ < 512) {
                val d = runner.director
                if (d.isFinished) return
                if (d.stepIndex == targetStepIndex &&
                    (d.awaitingCoach || d.awaitingQuiz)
                ) {
                    return
                }
                if (d.stepIndex > targetStepIndex) return
                when {
                    d.awaitingCoach -> {
                        if (!runner.acknowledgeCoach()) return
                    }
                    d.awaitingQuiz -> {
                        val quiz = runner.currentQuiz ?: return
                        if (!runner.submitQuiz(quiz.correctAnswer())) return
                        if (runner.quizAwaitingAdvance && !runner.advanceQuiz()) return
                    }
                    d.currentStep is TutorialStep.WaitChoice -> {
                        if (!autoSolveWait(runner)) return
                    }
                    else -> {
                        // Gate / script / assert should pump; if stuck, bail.
                        val before = d.stepIndex
                        runner.pumpAuto()
                        if (d.stepIndex == before &&
                            !d.awaitingCoach &&
                            !d.awaitingQuiz &&
                            d.currentStep !is TutorialStep.WaitChoice
                        ) {
                            return
                        }
                    }
                }
            }
        }

        /** Apply the waited legal choice so seek can pass board gates. */
        private fun autoSolveWait(runner: TutorialRunner): Boolean {
            val step = runner.director.currentStep as? TutorialStep.WaitChoice ?: return false
            val state = runner.state ?: return false
            val match = step.match
            val choices = runner.board.allowedChoices(state).ifEmpty {
                runner.board.legalChoices(state)
            }
            val hit = choices.firstOrNull { match.matches(it) } ?: return false
            return if (hit.kind == ChoiceKind.Discard) {
                runner.submitDiscard(hit.tile())
            } else {
                runner.submitChoice(hit)
            }
        }

        /**
         * Rebuild a lesson from a cold-start [TutorialSessionSnapshot].
         * Does not re-run completed steps; board comes from [snapshot.game].
         *
         * @return null if lesson id mismatch or director progress is invalid
         */
        fun restore(
            scenario: ScenarioDef,
            snapshot: TutorialSessionSnapshot,
            board: BoardController = headlessBoard(),
            choiceRng: Random = Random(0),
            blockOnCoach: Boolean = true,
        ): TutorialRunner? {
            if (scenario.id != snapshot.lessonId) return null
            val activeIdx = TutorialSlideNav.activeLayoutIndex(scenario, snapshot.stepIndex)
            val effectiveScenario = if (activeIdx >= 0) {
                val step = scenario.steps[activeIdx] as? TutorialStep.Coach
                if (step?.layout != null) {
                    val boundLayout = ScenarioLayoutSpec.Layout(GameStateLayout.from(snapshot.game))
                    val updatedSteps = scenario.steps.toMutableList()
                    updatedSteps[activeIdx] = step.copy(layout = boundLayout)
                    scenario.copy(steps = updatedSteps)
                } else {
                    scenario
                }
            } else {
                scenario
            }
            val state = snapshot.game.copy()
            board.gate = ChoiceGate.Identity
            val director = TutorialDirector(
                scenario = effectiveScenario,
                state = state,
                board = board,
                blockOnCoach = blockOnCoach,
                quizRng = choiceRng,
            )
            val templateQuiz = scenario.steps.getOrNull(snapshot.stepIndex) as? TutorialStep.Quiz
            val rawInteraction = snapshot.quizInteraction
            // Normalize against the step + director flags so legacy blobs cannot
            // restore "green hits + Confirm" (revealed without dock).
            val interaction = when {
                rawInteraction != null && templateQuiz != null ->
                    rawInteraction.normalized(
                        quiz = templateQuiz,
                        directorAwaitingAdvance = snapshot.quizAwaitingAdvance || rawInteraction.passed,
                        directorFail = snapshot.quizFeedbackFail,
                    )
                rawInteraction != null -> rawInteraction
                snapshot.quizAwaitingAdvance -> QuizInteractionState(
                    passed = true,
                    answerRevealed = true,
                )
                snapshot.quizFeedbackFail -> QuizInteractionState(failVisible = true)
                else -> null
            }
            val quizPassed = interaction?.passed == true || snapshot.quizAwaitingAdvance
            val quizFail = !quizPassed && (
                interaction?.failVisible == true || snapshot.quizFeedbackFail
                )
            if (!director.applyRestoredProgress(
                    restoredStepIndex = snapshot.stepIndex,
                    restoredStatus = snapshot.status,
                    restoredAwaitingCoach = snapshot.awaitingCoach,
                    restoredAwaitingQuiz = snapshot.awaitingQuiz,
                    restoredQuizFeedbackFail = quizFail,
                    restoredLastCoachKey = snapshot.lastCoachKey,
                    restoredLastFocus = snapshot.lastFocus,
                    restoredLastDiagram = snapshot.lastDiagram,
                    restoredFailureMessage = snapshot.failureMessage,
                    restoredPendingLinkPageId = snapshot.pendingLinkPageId,
                    restoredQuizHandTiles = snapshot.quizHandTiles,
                    restoredQuizAwaitingAdvance = quizPassed,
                )
            ) {
                return null
            }
            val runner = TutorialRunner(
                scenario = effectiveScenario,
                gameState = state,
                board = board,
                humanSeat = effectiveScenario.humanSeat,
                director = director,
                choiceRng = choiceRng,
            )
            runner.heldQuizInteraction = interaction
            // Settle only if we landed on an auto-step (rare after a clean pause).
            if (!director.isFinished && !director.awaitingCoach && !director.awaitingQuiz) {
                director.pumpAuto()
            }
            return runner
        }

        /**
         * A private controller for a headless caller, as [start]'s [board] documents.
         * Its emitter paces into a sequencer nobody presents or drains, which is the
         * right answer for a run with no frame loop: a lesson never plays its own
         * board, and Main supplies the shared controller when it does.
         */
        private fun headlessBoard(): BoardController = BoardController()
    }
}
