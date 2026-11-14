package inego.zungjunggame.shuffle

/**
 * Computational schedule for [TileShuffleSim].
 *
 * Material feel (mass, friction, stroke shape, …) lives on [ShuffleSimConfig].
 * This type owns only cost/quality trade-offs so desktop and mobile share one
 * physics model while paying different CPU budgets.
 *
 * ### Cost model
 * Per wall-clock frame the sim runs up to [maxSubSteps] fixed steps of
 * [fixedDt]. Each substep does one velocity solve ([solverIterations]) and
 * one **unified geometric resolve** of up to [hardSeparationIterations]
 * detect→separate→bounds loops (early-out when residual penetration is under
 * the material slop). Work therefore scales roughly as:
 *
 * ```
 * maxSubSteps × (solver + hardSeparationIterations × detect)
 * ```
 *
 * not as an open-ended “keep iterating until perfect” loop. Optional
 * [stepTimeBudgetMs] aborts remaining substeps and drains the accumulator so
 * a slow device cannot spiral into multi-frame stalls.
 */
data class ShuffleSolverSchedule(
    /** Fixed integration step (seconds). */
    val fixedDt: Float = 1f / 120f,
    /** Max fixed substeps per [TileShuffleSim.step] call. */
    val maxSubSteps: Int = 4,
    /** Sequential impulse iterations per substep. */
    val solverIterations: Int = 10,
    /**
     * Soft post-solve positional nudge fraction (0–1) applied once on the
     * velocity-solve contact list before the hard geometric resolve.
     */
    val softPositionCorrection: Float = 0.5f,
    /**
     * Max detect→separate→bounds passes in the unified geometric resolve.
     * Real tiles do not nest; a few full free-body separations keep mash
     * scrapes visual-safe while velocity response stays soft/heavy.
     */
    val hardSeparationIterations: Int = 8,
    /**
     * Soft wall-clock budget for one [TileShuffleSim.step] (milliseconds).
     * After a completed substep, if elapsed time meets/exceeds the budget,
     * further substeps are skipped and the time accumulator is drained so
     * lag cannot compound. `0` = unlimited (desktop, tests, offline).
     */
    val stepTimeBudgetMs: Float = 0f,
) {
    init {
        require(fixedDt > 0f) { "fixedDt must be > 0" }
        require(maxSubSteps >= 1) { "maxSubSteps must be >= 1" }
        require(solverIterations >= 1) { "solverIterations must be >= 1" }
        require(softPositionCorrection in 0f..1f) {
            "softPositionCorrection must be in [0, 1]"
        }
        require(hardSeparationIterations >= 0) {
            "hardSeparationIterations must be >= 0"
        }
        require(stepTimeBudgetMs >= 0f) { "stepTimeBudgetMs must be >= 0" }
    }

    companion object {
        /**
         * High visual fidelity for desktop / unlimited headroom.
         * Single geometric resolve (not doubled); enough substeps for 60 Hz
         * with [fixedDt] = 1/120.
         */
        val High = ShuffleSolverSchedule(
            fixedDt = 1f / 120f,
            maxSubSteps = 4,
            solverIterations = 10,
            softPositionCorrection = 0.55f,
            hardSeparationIterations = 10,
            stepTimeBudgetMs = 0f,
        )

        /**
         * Interactive schedule targeting ~60 Hz on mobile CPUs.
         *
         * Nominal one fixed step per display frame ([fixedDt] ≈ 1/60), at most
         * two catch-up substeps, two hard-separation passes, plus a hard
         * wall-clock budget so a weak frame drops sim time instead of freezing
         * the UI thread. Material feel stays on [ShuffleSimConfig].
         */
        val Interactive = ShuffleSolverSchedule(
            fixedDt = 1f / 60f,
            maxSubSteps = 2,
            solverIterations = 6,
            softPositionCorrection = 0.4f,
            hardSeparationIterations = 2,
            stepTimeBudgetMs = 10f,
        )
    }
}
