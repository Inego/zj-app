package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.tutorial.slide.SlidePresentation

/**
 * One step in a headless (and later UI) tutorial scenario.
 *
 * Coach / LinkRef auto-advance in headless runs; UI blocks on coach / quiz until answered.
 */
sealed class TutorialStep {
    /**
     * Show coach copy ([textKey] is an i18n key). Headless director auto-advances.
     *
     * [diagram] places teaching tiles/schematics on the table with the copy.
     * [focus] highlights a registered target (diagram tile, seat, …) and may
     * show a callout bubble — use for “look at this face” beats.
     * [spotlight] is a legacy target-id-only alias when [focus] is null.
     * [presentation] is UI-only motion (see [SlidePresentation]); ignored headless.
     */
    data class Coach(
        val textKey: String,
        val spotlight: String? = null,
        val diagram: TutorialDiagram = TutorialDiagram.None,
        val focus: TutorialFocus? = null,
        val presentation: SlidePresentation = SlidePresentation.Standard,
        /**
         * When true, Main draws the live table under this coach (board-overlay
         * chrome). Use for mid-lesson handoff from diagrams to [PlayTableView].
         * Independent of [ScenarioDef.showsBoard] (scenario-wide default).
         */
        val showsBoard: Boolean = false,
        /**
         * Optional layout override for this step and subsequent steps (until another
         * step overrides it). Falls back to [ScenarioDef.layout].
         */
        val layout: ScenarioLayoutSpec? = null,
        /**
         * Optional seat reveal override for this step. If null, falls back to
         * [ScenarioDef.revealedSeats].
         */
        val revealedSeats: Set<Int>? = null,
    ) : TutorialStep() {
        /** Prefer [focus]; else map [spotlight] to a highlight-only focus. */
        fun resolvedFocus(): TutorialFocus? =
            focus ?: spotlight?.takeIf { it.isNotBlank() }?.let { TutorialFocus(it) }
    }

    /**
     * Recognition quiz (Chapter 0). Headless auto-submits a correct [QuizAnswer].
     * UI blocks until [TutorialDirector.submitQuiz] accepts.
     *
     * When [contentFactory] is set, [options]/[correctIds]/[correctBins] are
     * placeholders; the director materializes live content on enter / retry.
     * When [retryAfterCorrect] is true, Confirm always stays on the quiz with
     * selection review + Next / Try again (new hand), whether right or wrong.
     */
    data class Quiz(
        val promptKey: String,
        val kind: QuizKind,
        val options: List<QuizOption> = emptyList(),
        /** Correct option ids for multi-choice / tile-select. */
        val correctIds: Set<String> = emptySet(),
        val bins: List<QuizBin> = emptyList(),
        /** Correct optionId → binId for [QuizKind.BinAssign]. */
        val correctBins: Map<String, String> = emptyMap(),
        val diagram: TutorialDiagram = TutorialDiagram.None,
        val spotlight: String? = null,
        val confirmKey: String = "tutorial.quiz.confirm",
        /** Runtime content; when non-null, [options] may be empty at authoring time. */
        val contentFactory: QuizContentFactory? = null,
        /**
         * After Confirm, keep the step active with selection review + Next /
         * Try again instead of advancing immediately (any answer).
         */
        val retryAfterCorrect: Boolean = false,
        val nextKey: String = "tutorial.next",
        val tryAgainKey: String = "tutorial.quiz.try_again",
        /**
         * How Confirm feedback is presented. [QuizFeedbackMode.SelectionReview]
         * drives reusable hit / false-positive / miss marks for multiselect.
         */
        val feedbackMode: QuizFeedbackMode = QuizFeedbackMode.defaultFor(kind),
    ) : TutorialStep() {
        init {
            require(promptKey.isNotBlank()) { "quiz prompt blank" }
            if (contentFactory == null) {
                require(options.isNotEmpty()) { "quiz has no options" }
                when (kind) {
                    QuizKind.MultiChoice ->
                        require(correctIds.size == 1) { "multi-choice needs exactly one correct id" }
                    QuizKind.TileSelect ->
                        require(correctIds.isNotEmpty()) { "tile-select needs correctIds" }
                    QuizKind.BinAssign -> {
                        require(bins.isNotEmpty()) { "bin-assign needs bins" }
                        require(correctBins.isNotEmpty()) { "bin-assign needs correctBins" }
                        require(correctBins.keys.all { k -> options.any { it.id == k } }) {
                            "correctBins keys must be option ids"
                        }
                    }
                }
            } else {
                when (kind) {
                    QuizKind.BinAssign ->
                        require(bins.isNotEmpty()) { "bin-assign needs bins" }
                    QuizKind.MultiChoice, QuizKind.TileSelect -> Unit
                }
            }
        }

        /** Apply generated (or restored) content onto this template. */
        fun withContent(content: QuizContent): Quiz = copy(
            options = content.options,
            correctIds = content.correctIds,
            correctBins = content.correctBins.ifEmpty { correctBins },
            contentFactory = null,
        )
    }

    /**
     * Install a human [inego.zungjunggame.session.board.ChoiceGate] allowing only these kinds.
     * Empty [allowKinds] means clear to identity (all legal).
     */
    data class Gate(
        val allowKinds: Set<ChoiceKind>,
    ) : TutorialStep() {
        companion object {
            fun kinds(vararg kinds: ChoiceKind): Gate = Gate(kinds.toSet())
            fun identity(): Gate = Gate(emptySet())
        }
    }

    /**
     * Block until the human commits a choice matching [match] (must also pass the gate).
     */
    data class WaitChoice(
        val match: ChoiceMatch,
    ) : TutorialStep()

    /**
     * Apply one scripted non-human (or forced) [choice] immediately with identity gate.
     * Used for “South discards” style beats (scripted seats, not other rulesets).
     */
    data class ScriptChoice(
        val choice: Choice,
    ) : TutorialStep()

    /**
     * Headless assertion on [GameState]; failure ends the lesson as Failed.
     */
    data class AssertState(
        val message: String,
        val predicate: (GameState) -> Boolean,
    ) : TutorialStep()

    /** Optional reference deep-link (headless: no-op advance; UI opens browser later). */
    data class LinkRef(
        val pageId: String,
    ) : TutorialStep()

    /**
     * Success terminal for this unit (progress marker only).
     *
     * Not a UI slide. Main marks the unit completed and either auto-advances or,
     * only when [ScenarioDef.completeSummaryKey] is set, shows an explicit
     * summary panel. Do not rely on this for teaching copy — use [Coach] if the
     * wrap-up has new information.
     */
    data object Complete : TutorialStep()
}
