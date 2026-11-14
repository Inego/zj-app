package inego.zungjunggame.tutorial

/**
 * Headless-authorable unit in the tutorial spine: fixed layout + ordered
 * [TutorialStep]s (slides). Progress is tracked per [id] in the campaign tree.
 *
 * [showsBoard] false = orientation / recognition unit: Main skips table draw and
 * board hit-testing; [layout] may still materialize a dummy [GameState] for the runner.
 *
 * **Complete UI is opt-in.** [TutorialStep.Complete] only marks progress and
 * advances the spine. Do **not** set [completeSummaryKey] unless this unit is
 * long enough that a real summary earns the screen — short coach/quiz chains
 * must auto-advance with no "lesson complete" chrome. Prefer a normal
 * [TutorialStep.Coach] for any wrap-up that teaches something new.
 */
data class ScenarioDef(
    val id: String,
    val titleKey: String,
    val layout: ScenarioLayoutSpec,
    val steps: List<TutorialStep>,
    /** Absolute seat the human controls (usually 0). */
    val humanSeat: Int = 0,
    /**
     * When true, Main draws the live table for every step of this unit.
     * When false, board is still drawn for individual [TutorialStep.Coach]
     * steps that set [TutorialStep.Coach.showsBoard] (mid-lesson handoff).
     */
    val showsBoard: Boolean = true,
    /**
     * When non-null, show a one-shot complete panel with this body l10n key after
     * [TutorialStep.Complete]. Null (default) = no complete slide; auto-advance.
     */
    val completeSummaryKey: String? = null,
    /**
     * Absolute seat indices whose concealed hands should be drawn face-up by default
     * throughout this scenario (can be overridden per-step in [TutorialStep.Coach]).
     */
    val revealedSeats: Set<Int> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "scenario id blank" }
        require(steps.isNotEmpty()) { "scenario $id has no steps" }
        require(steps.any { it is TutorialStep.Complete }) {
            "scenario $id must include a Complete terminal (progress marker, not a slide)"
        }
        require(humanSeat in 0..3) { "humanSeat $humanSeat" }
        completeSummaryKey?.let {
            require(it.isNotBlank()) { "scenario $id completeSummaryKey blank" }
        }
    }
}
