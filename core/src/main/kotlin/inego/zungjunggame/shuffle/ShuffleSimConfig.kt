package inego.zungjunggame.shuffle

/**
 * Material and world parameters for [TileShuffleSim] (see docs/shuffle-sim-model.md).
 *
 * Computational cost lives on [schedule] ([ShuffleSolverSchedule]) so desktop
 * and mobile can share the same feel with different CPU budgets.
 *
 * World units are arbitrary and consistent. The wall-build tutorial uses
 * millimetres with a fixed scale: table 800 mm, tile long side 40 mm
 * (see [inego.zungjunggame.render.wallbuild.WallBuildView] constants).
 */
data class ShuffleSimConfig(
    /** Tile long side (local Y when θ = 0). */
    val tileLength: Float = 1f,
    /** Tile short side (local X when θ = 0). Aspect ≈ diagram tiles (0.72). */
    val tileWidth: Float = 0.72f,
    /** Square table side length (world units). */
    val tableSide: Float = 22f,
    /** Table center X. */
    val tableCenterX: Float = 0f,
    /** Table center Y. */
    val tableCenterY: Float = 0f,
    val mass: Float = 1f,
    /** Surface gravity used only for kinetic friction magnitude (μ_k m g). */
    val gravity: Float = 9.81f,
    /**
     * Kinetic surface friction coefficient (Coulomb stop a ≈ μ g).
     * Paired with [surfaceStopAccelScale] for felt with short inertia, not ice.
     */
    val surfaceMuK: Float = 0.75f,
    /**
     * Minimum free-tile deceleration as a multiple of tile length (a ≥ scale·L).
     * Higher → shorter coast (heavy tiles on felt); much higher feels like glue.
     */
    val surfaceStopAccelScale: Float = 26f,
    /**
     * Fraction of palm velocity kept when a grip is released (0 = drop dead,
     * 1 = full ice-slide). ~0.4–0.55 reads as heavy tiles, not a fling.
     */
    val releaseVelocityKeep: Float = 0.48f,
    /**
     * Viscous angular damping coefficient (τ = −c ω).
     * Should scale with inertia (~L²). Primary spin kill is Coulomb in the sim.
     */
    val angularDamping: Float = 1.6f,
    /** Tile–tile / tile–wall Coulomb friction. */
    val contactMu: Float = 0.55f,
    /** Restitution (mostly inelastic). Free–free only; kinematic hits use 0. */
    val restitution: Float = 0.08f,
    /**
     * Soft push: max normal Δv a free tile may gain from one kinematic contact
     * per substep (× tile length). Infinite-mass grips otherwise match palm
     * speed in one hit and tiles "fly". ~1.0–1.5 keeps heavy scraping.
     */
    val kinematicMaxDeltaVScale: Float = 1.15f,
    /**
     * Hard cap on free-tile linear speed (× tile length / s). Stops slingshots
     * from stacked contacts / baumgarte bias while still allowing a firm shove.
     */
    val freeMaxSpeedScale: Float = 6.5f,
    /** Baumgarte position bias factor (0–1). */
    val baumgarte: Float = 0.2f,
    /** Penetration slop before velocity bias kicks in (soft contacts). */
    val slop: Float = 0.002f,
    /**
     * Allowed residual penetration after hard separation (world units).
     * Keep tiny vs tile size so overlap is not visible.
     */
    val positionSeparationSlop: Float = 0.001f,
    /** Linear speed below which surface static friction may freeze. */
    val linearSleepEpsilon: Float = 0.04f,
    /** Angular speed sleep threshold (rad/s). */
    val angularSleepEpsilon: Float = 0.08f,
    /** Time spent under sleep thresholds before sleeping. */
    val sleepTime: Float = 0.25f,
    /**
     * Grip radius around the seed tile as a multiple of max tile side.
     * All free tiles within this center-to-center distance may be gripped
     * (capped by [strokeGripMax]).
     */
    val clusterNeighborScale: Float = 2f,
    /**
     * Soft minimum for API compatibility; the picker returns whatever lies in
     * the seed radius (may be fewer). Prefer 1 for farthest-from-center seeding.
     */
    val strokeGripMin: Int = 1,
    /** Max tiles per push stroke (seed + neighbors in radius; ≤ [ShuffleHand.MAX_GRIP]). */
    val strokeGripMax: Int = 7,
    /**
     * Aim-point jitter radius around the table square center, as a multiple of
     * tile length. Each stroke picks a uniform random point in that disk
     * (not the exact center) and shoves toward / slightly past it.
     */
    val strokeAimJitterScale: Float = 3f,
    /**
     * Extra travel past the per-stroke aim point, as a multiple of tile length.
     */
    val strokePastComScale: Float = 0.7f,
    /**
     * If the palm is closer to the square center than this (× tile length),
     * stroke distance is still at least this far along the aim direction so
     * the hand does not stall on a tiny nudge.
     */
    val strokeMinLengthScale: Float = 1.5f,
    /**
     * Nominal average palm speed along a stroke (tile lengths / second).
     * Ease-in-out peaks ~3× this; duration is also clamped by
     * [strokeMinDuration] / [strokeMaxDuration].
     */
    val strokeAvgSpeedScale: Float = 9f,
    /** Random ± jitter on [strokeAvgSpeedScale] (tile lengths / second). */
    val strokeAvgSpeedJitter: Float = 2.5f,
    /** Shortest shove duration (seconds) — keeps tiny moves snappy. */
    val strokeMinDuration: Float = 0.22f,
    /** Longest shove duration (seconds) — long aims stay brisk, not crawls. */
    val strokeMaxDuration: Float = 0.55f,
    /** Broad-phase cell size multiplier relative to max(tile L, W). */
    val broadphaseCellScale: Float = 1.15f,
    /**
     * Integration / solver / separation budget. Defaults to [ShuffleSolverSchedule.High].
     * Use [ShuffleSolverSchedule.Interactive] on mobile.
     */
    val schedule: ShuffleSolverSchedule = ShuffleSolverSchedule.High,
) {
    val halfLength: Float get() = tileLength * 0.5f
    val halfWidth: Float get() = tileWidth * 0.5f
    val inertia: Float get() = mass * (tileLength * tileLength + tileWidth * tileWidth) / 12f
    val invMass: Float get() = if (mass > 0f) 1f / mass else 0f
    val invInertia: Float get() = if (inertia > 0f) 1f / inertia else 0f

    val tableMinX: Float get() = tableCenterX - tableSide * 0.5f
    val tableMaxX: Float get() = tableCenterX + tableSide * 0.5f
    val tableMinY: Float get() = tableCenterY - tableSide * 0.5f
    val tableMaxY: Float get() = tableCenterY + tableSide * 0.5f

    // Convenience mirrors so call sites / tests can write cfg.fixedDt etc.
    val fixedDt: Float get() = schedule.fixedDt
    val solverIterations: Int get() = schedule.solverIterations
    val positionCorrection: Float get() = schedule.softPositionCorrection
    val positionSeparationIterations: Int get() = schedule.hardSeparationIterations

    companion object {
        val DEFAULT = ShuffleSimConfig()

        /**
         * Wall-build tutorial material preset (millimetre table).
         * [schedule] selects desktop vs interactive CPU budget; feel params
         * are shared so mash weight stays consistent across platforms.
         */
        fun wallBuildTutorial(
            tileLength: Float,
            tileWidth: Float,
            tableSide: Float,
            schedule: ShuffleSolverSchedule = ShuffleSolverSchedule.High,
            tableCenterX: Float = 0f,
            tableCenterY: Float = 0f,
        ): ShuffleSimConfig {
            val mass = 1f
            val inertia = mass * (tileLength * tileLength + tileWidth * tileWidth) / 12f
            return ShuffleSimConfig(
                tileLength = tileLength,
                tileWidth = tileWidth,
                tableSide = tableSide,
                tableCenterX = tableCenterX,
                tableCenterY = tableCenterY,
                mass = mass,
                gravity = 9.81f * tileLength,
                // Heavy bone/plastic on felt: short scrape, not ice hockey.
                surfaceMuK = 0.92f,
                surfaceStopAccelScale = 30f,
                releaseVelocityKeep = 0.42f,
                angularDamping = inertia / 0.08f,
                contactMu = 0.62f,
                restitution = 0.04f,
                kinematicMaxDeltaVScale = 1.1f,
                freeMaxSpeedScale = 6f,
                baumgarte = 0.16f,
                slop = tileLength * 0.008f,
                positionSeparationSlop = tileLength * 0.002f,
                linearSleepEpsilon = tileLength * 0.1f,
                angularSleepEpsilon = 0.25f,
                sleepTime = 0.1f,
                strokeGripMin = 1,
                strokeGripMax = 7,
                clusterNeighborScale = 2f,
                schedule = schedule,
            )
        }
    }
}
