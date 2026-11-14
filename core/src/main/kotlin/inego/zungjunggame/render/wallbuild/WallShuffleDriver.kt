package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.render.tiles.TileEasing
import inego.zungjunggame.shuffle.ShuffleHand
import inego.zungjunggame.shuffle.ShuffleSimConfig
import inego.zungjunggame.shuffle.ShuffleSolverSchedule
import inego.zungjunggame.shuffle.TileShuffleSim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tutorial wall-build shuffle: millimetre-space [TileShuffleSim] plus a single
 * sequential hand that grips clusters and shoves them toward the table center
 * with short ease-in-out strokes.
 *
 * The diagram square is only a view transform ([setDisplayMap]); physics always
 * runs on a fixed [tableMm] × [tileMm] table.
 *
 * Material feel is shared via [ShuffleSimConfig.wallBuildTutorial]; the
 * computational budget is [ShuffleSolverSchedule.High] on desktop and
 * [ShuffleSolverSchedule.Interactive] on Android/iOS (overridable via
 * [solverSchedule]).
 */
class WallShuffleDriver(
    private val tableMm: Float,
    private val tileMm: Float,
    private val tileAspect: Float,
    private val tileCount: Int = WALL_SIZE,
    /**
     * Solver CPU schedule. `null` (default) picks [detectSolverSchedule] from
     * the running libGDX backend so mobile stays interactive without a
     * separate code path for material params.
     */
    var solverSchedule: ShuffleSolverSchedule? = null,
) {
    /**
     * Diagram-space pose used to seed the sim (center, rotation degrees, type).
     * Converted to mm about the display origin when [start] runs.
     */
    data class SeedPose(
        val cx: Float,
        val cy: Float,
        val rotDeg: Float,
        val type: Int,
    )

    private var sim: TileShuffleSim? = null
    private var shuffleTime = 0f
    private var rng = Random(1L)

    /** Diagram px per sim millimetre (square side / tableMm). */
    private var pxPerMm = 1f
    private var originX = 0f
    private var originY = 0f

    private val seedXs = FloatArray(tileCount)
    private val seedYs = FloatArray(tileCount)
    private val seedThetas = FloatArray(tileCount)
    private val seedTypes = IntArray(tileCount) { it / 4 }

    // Single sequential mash: one cluster push at a time (eased, not linear crawl).
    private var strokeActive = false
    private var strokeOriginX = 0f
    private var strokeOriginY = 0f
    private var strokePalmX = 0f
    private var strokePalmY = 0f
    private var strokeDirX = 1f
    private var strokeDirY = 0f
    private var strokeTravel = 0f
    private var strokeElapsed = 0f
    private var strokeDuration = 0.2f
    private val strokeCluster = IntArray(ShuffleHand.MAX_GRIP)

    val active: Boolean
        get() = sim != null

    /** Snapshot after the last [update] (null when sim is not running). */
    data class FrameStats(
        val shuffleTimeSec: Float,
        val substeps: Int,
        val contacts: Int,
        val awake: Int,
        val strokeActive: Boolean,
        val gripCount: Int,
        /** Physics wall time of the last sim [TileShuffleSim.step] (ms). */
        val stepMs: Float = 0f,
        /** True when the step aborted remaining substeps under a time budget. */
        val budgetCut: Boolean = false,
    )

    /** Fresh RNG when entering the shuffle phase (non-deterministic mash). */
    fun reseedRng(seed: Long = System.nanoTime()) {
        rng = Random(seed)
        shuffleTime = 0f
    }

    fun stop() {
        sim?.clearHands()
        sim = null
        shuffleTime = 0f
        strokeActive = false
        strokeElapsed = 0f
        strokeTravel = 0f
    }

    fun frameStats(): FrameStats? {
        val world = sim ?: return null
        val grip = if (world.hands.isNotEmpty()) world.hands[0].gripCount else 0
        return FrameStats(
            shuffleTimeSec = shuffleTime,
            substeps = world.lastStepSubsteps,
            contacts = world.lastContactCount,
            awake = world.awakeCount(),
            strokeActive = strokeActive,
            gripCount = grip,
            stepMs = world.lastStepMs,
            budgetCut = world.lastStepBudgetCut,
        )
    }

    /** Schedule that will be used on the next [start] (resolved if still null). */
    fun resolvedSchedule(): ShuffleSolverSchedule =
        solverSchedule ?: detectSolverSchedule()

    /**
     * Map the fixed mm table onto the current diagram square (left/bottom/side
     * in diagram pixels). Call before [start] and whenever the diagram resizes.
     */
    fun setDisplayMap(left: Float, bottom: Float, side: Float) {
        originX = left + side * 0.5f
        originY = bottom + side * 0.5f
        pxPerMm = (side / tableMm).coerceAtLeast(1e-4f)
    }

    /**
     * Build the sim and begin stroking. When [seedPoses] is non-null and length
     * [tileCount], seeds from those diagram poses; otherwise a packed grid.
     */
    fun start(seedPoses: Array<SeedPose>?) {
        val tileL = tileMm
        val tileW = tileL * tileAspect
        val tableSide = tableMm
        val schedule = resolvedSchedule()
        val config = ShuffleSimConfig.wallBuildTutorial(
            tileLength = tileL,
            tileWidth = tileW,
            tableSide = tableSide,
            schedule = schedule,
        )

        val world = TileShuffleSim(config, tileCount = tileCount, maxHands = 1)
        sim = world
        strokeActive = false
        strokeElapsed = 0f
        strokeTravel = 0f

        if (seedPoses != null && seedPoses.size >= tileCount) {
            val half = tableSide * 0.5f - tileL * 0.55f
            val inv = if (pxPerMm > 1e-6f) 1f / pxPerMm else 1f
            for (i in 0 until tileCount) {
                val p = seedPoses[i]
                // Diagram px → sim mm (origin = table center).
                seedXs[i] = ((p.cx - originX) * inv).coerceIn(-half, half)
                seedYs[i] = ((p.cy - originY) * inv).coerceIn(-half, half)
                seedThetas[i] = p.rotDeg * (PI.toFloat() / 180f)
                seedTypes[i] = p.type
            }
            world.setupFromPoses(seedXs, seedYs, seedThetas, seedTypes)
        } else {
            world.setupPackedGrid(
                cols = 17,
                gap = tileL * 0.06f,
                angleJitter = 0.1f,
                positionJitter = tileL * 0.04f,
                rng = rng,
            )
        }

        driveHands(world, dtSec = 1f / 60f)
    }

    /**
     * Advance hand strokes and physics. No-op if not [active] (caller should
     * [start] first). After stepping, call [forEachDisplayPose] to copy out.
     */
    fun update(dtSec: Float) {
        val world = sim ?: return
        shuffleTime += dtSec
        driveHands(world, dtSec)
        world.step(dtSec)
        // Sim hard-clamps gripped clusters at the rim; shift the eased stroke
        // origin so the parametric path stays consistent with the clamped palm.
        if (strokeActive && world.hands.isNotEmpty() && world.hands[0].hasGrip) {
            val hx = world.hands[0].x
            val hy = world.hands[0].y
            val ox = hx - strokePalmX
            val oy = hy - strokePalmY
            if (ox != 0f || oy != 0f) {
                strokeOriginX += ox
                strokeOriginY += oy
                strokePalmX = hx
                strokePalmY = hy
            }
        }
    }

    /**
     * Current tile poses in diagram pixels (closed tiles at fixed physical
     * size). No-op when the sim is not running.
     */
    fun forEachDisplayPose(
        action: (index: Int, cx: Float, cy: Float, rotDeg: Float, w: Float, h: Float) -> Unit,
    ) {
        val world = sim ?: return
        val radToDeg = 180f / PI.toFloat()
        val s = pxPerMm
        val tw = world.config.tileWidth * s
        val th = world.config.tileLength * s
        for (i in 0 until tileCount) {
            val body = world.tiles[i]
            action(
                i,
                originX + body.x * s,
                originY + body.y * s,
                body.theta * radToDeg,
                tw,
                th,
            )
        }
    }

    /**
     * One hand at a time: grip the free tile farthest from the square center
     * plus up to 6 neighbors within 2 tile lengths, shove toward (and slightly
     * past) a jittered point near the square center with a short ease-in-out
     * punch, release, immediately start the next stroke.
     */
    private fun driveHands(world: TileShuffleSim, dtSec: Float) {
        if (world.hands.isEmpty()) return
        val dt = dtSec.coerceAtLeast(1e-4f)

        if (!strokeActive || !world.hands[0].hasGrip) {
            if (!beginStroke(world)) return
        }

        strokeElapsed += dt
        val duration = strokeDuration.coerceAtLeast(1e-4f)
        val u = (strokeElapsed / duration).coerceIn(0f, 1f)
        // Sample slightly before the end so release still carries shove velocity
        // (ease-in-out derivative is 0 at u=1).
        val uPos = u
        val uVel = u.coerceAtMost(0.88f)
        val e = TileEasing.easeInOutCubic(uPos)
        val speed = strokeTravel * TileEasing.easeInOutCubicDerivative(uVel) / duration
        strokePalmX = strokeOriginX + strokeDirX * strokeTravel * e
        strokePalmY = strokeOriginY + strokeDirY * strokeTravel * e
        // Mild residual so gripped tiles still coast a bit after release
        // (keep this modest — a high floor reintroduces a "fierce" fling).
        val releaseFloor = strokeTravel / duration * 0.22f
        val palmSpeed = if (u >= 1f) maxOf(speed, releaseFloor) else speed
        world.setHandMotion(
            0,
            strokePalmX,
            strokePalmY,
            strokeDirX * palmSpeed,
            strokeDirY * palmSpeed,
        )

        if (u >= 1f) {
            world.releaseHand(0)
            strokeActive = false
            // Next hand starts immediately — no idle gap.
            beginStroke(world)
        }
    }

    /**
     * Pick farthest-from-square-center cluster; shove palm toward a jittered
     * point near the square center and slightly past it. Returns false if
     * nothing to grip.
     */
    private fun beginStroke(world: TileShuffleSim): Boolean {
        val cfg = world.config
        val tL = cfg.tileLength
        val n = world.pickCompactCluster(
            maxCount = cfg.strokeGripMax,
            out = strokeCluster,
        )
        if (n <= 0) {
            strokeActive = false
            return false
        }
        val gripped = world.gripTiles(0, strokeCluster, n)
        if (gripped <= 0) {
            strokeActive = false
            return false
        }

        val hand = world.hands[0]
        strokeOriginX = hand.x
        strokeOriginY = hand.y
        strokePalmX = hand.x
        strokePalmY = hand.y

        // Aim near the square center (uniform disk, radius = strokeAimJitterScale · L).
        val jitterR = tL * cfg.strokeAimJitterScale
        val jitterAng = rng.nextFloat() * (2f * PI.toFloat())
        val jitterRad = jitterR * sqrt(rng.nextFloat())
        val aimX = cfg.tableCenterX + cos(jitterAng) * jitterRad
        val aimY = cfg.tableCenterY + sin(jitterAng) * jitterRad

        var dx = aimX - strokePalmX
        var dy = aimY - strokePalmY
        var dist = sqrt(dx * dx + dy * dy)
        val minAim = tL * 1e-3f
        if (dist < minAim) {
            // Already on aim point — any unit direction so the stroke still runs.
            val ang = rng.nextFloat() * (2f * PI.toFloat())
            dx = cos(ang)
            dy = sin(ang)
            dist = 1f
        }
        val inv = 1f / dist
        strokeDirX = dx * inv
        strokeDirY = dy * inv

        val past = tL * cfg.strokePastComScale
        val minLen = tL * cfg.strokeMinLengthScale
        strokeTravel = maxOf(dist + past, minLen)

        // Short punch: duration from travel / avg speed, clamped so long aims
        // stay brisk and tiny ones still read as a shove (not a crawl).
        val avgScale = cfg.strokeAvgSpeedScale +
            (rng.nextFloat() * 2f - 1f) * cfg.strokeAvgSpeedJitter
        val avgSpeed = tL * avgScale.coerceAtLeast(4f)
        strokeDuration = (strokeTravel / avgSpeed).coerceIn(
            cfg.strokeMinDuration,
            cfg.strokeMaxDuration,
        )
        strokeElapsed = 0f
        strokeActive = true
        // Start nearly still — ease-in builds the shove (derivative ~0 at u=0).
        world.setHandMotion(0, strokePalmX, strokePalmY, 0f, 0f)
        return true
    }

    companion object {
        /**
         * Desktop → [ShuffleSolverSchedule.High]; Android/iOS →
         * [ShuffleSolverSchedule.Interactive]. Safe when [Gdx.app] is unset
         * (unit tests): falls back to High.
         */
        fun detectSolverSchedule(): ShuffleSolverSchedule {
            val app = try {
                Gdx.app
            } catch (_: Throwable) {
                null
            } ?: return ShuffleSolverSchedule.High
            return when (app.type) {
                Application.ApplicationType.Android,
                Application.ApplicationType.iOS,
                -> ShuffleSolverSchedule.Interactive
                else -> ShuffleSolverSchedule.High
            }
        }
    }
}
