package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.Gdx
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.input.TouchDiagnostics

/**
 * Physics shuffle session for the wall-build diagram: wraps [WallShuffleDriver]
 * plus Android diagnostic logging ([TouchDiagnostics] / zj_touch.log).
 *
 * The diagram square is only a view transform; physics stays on the fixed mm
 * table. The view copies display poses onto [WallBuildAnimTile]s.
 */
internal class WallBuildShuffleSession(
    tableMm: Float,
    tileMm: Float,
    tileAspect: Float,
) {
    private val driver = WallShuffleDriver(
        tableMm = tableMm,
        tileMm = tileMm,
        tileAspect = tileAspect,
    )

    private var diagFrame = 0
    private var diagAccumSec = 0f
    private var diagMaxStepMs = 0f
    /** At most one explicit SLOW line per heartbeat window (avoids I/O storms). */
    private var diagSlowLogged = false

    val active: Boolean
        get() = driver.active

    fun reseedRng() = driver.reseedRng()

    fun setDisplayMap(left: Float, bottom: Float, side: Float) {
        driver.setDisplayMap(left, bottom, side)
    }

    fun applyPosesToTiles(tiles: Array<WallBuildAnimTile>) {
        driver.forEachDisplayPose { i, cx, cy, rotDeg, w, h ->
            val t = tiles[i]
            t.cx = cx
            t.cy = cy
            t.rot = rotDeg
            t.w = w
            t.h = h
            t.open = false
            t.toCx = cx
            t.toCy = cy
            t.toRot = rotDeg
            t.toW = w
            t.toH = h
            t.toOpen = false
        }
    }

    fun start(
        squareLeft: Float,
        squareBottom: Float,
        squareSide: Float,
        tiles: Array<WallBuildAnimTile>,
        seedFromCurrentTiles: Boolean,
    ) {
        driver.setDisplayMap(squareLeft, squareBottom, squareSide)
        val useSeeds = seedFromCurrentTiles &&
            WallBuildAnimTile.anyHasPose(tiles) &&
            !WallBuildAnimTile.lookStacked(tiles)
        val seeds = if (useSeeds) {
            Array(WALL_SIZE) { i ->
                val t = tiles[i]
                WallShuffleDriver.SeedPose(t.cx, t.cy, t.rot, t.type)
            }
        } else {
            null
        }
        val t0 = System.nanoTime()
        driver.start(seeds)
        val startMs = (System.nanoTime() - t0) / 1_000_000f
        applyPosesToTiles(tiles)
        diagFrame = 0
        diagAccumSec = 0f
        diagMaxStepMs = 0f
        diagSlowLogged = false
        val sch = driver.resolvedSchedule()
        TouchDiagnostics.log(
            "shuffle start seed=${if (useSeeds) "poses" else "grid"} " +
                "tiles=$WALL_SIZE sidePx=${TouchDiagnostics.fmt(squareSide)} " +
                "startMs=${fmtMs(startMs)} " +
                "sched=dt${TouchDiagnostics.fmt(sch.fixedDt)}/" +
                "sub${sch.maxSubSteps}/sep${sch.hardSeparationIterations}/" +
                "bud${TouchDiagnostics.fmt(sch.stepTimeBudgetMs)} " +
                "fps=${Gdx.graphics.framesPerSecond}",
        )
    }

    fun stop(reason: String) {
        if (!driver.active) {
            driver.stop()
            return
        }
        val s = driver.frameStats()
        TouchDiagnostics.log(
            "shuffle stop reason=$reason frames=$diagFrame " +
                "t=${s?.let { TouchDiagnostics.fmt(it.shuffleTimeSec) } ?: "?"} " +
                "awake=${s?.awake ?: "?"} contacts=${s?.contacts ?: "?"} " +
                "fps=${Gdx.graphics.framesPerSecond}",
        )
        driver.stop()
        diagFrame = 0
        diagAccumSec = 0f
        diagMaxStepMs = 0f
        diagSlowLogged = false
    }

    /**
     * Step the sim and copy poses. Restarts from current tile poses if the
     * driver is not running.
     */
    fun update(
        dtSec: Float,
        squareLeft: Float,
        squareBottom: Float,
        squareSide: Float,
        tiles: Array<WallBuildAnimTile>,
    ) {
        if (!driver.active) {
            start(
                squareLeft = squareLeft,
                squareBottom = squareBottom,
                squareSide = squareSide,
                tiles = tiles,
                seedFromCurrentTiles = WallBuildAnimTile.anyHasPose(tiles),
            )
            return
        }
        val t0 = System.nanoTime()
        driver.update(dtSec)
        val stepMs = (System.nanoTime() - t0) / 1_000_000f
        applyPosesToTiles(tiles)

        diagFrame++
        diagAccumSec += dtSec
        if (stepMs > diagMaxStepMs) diagMaxStepMs = stepMs

        val slow = stepMs >= 20f
        val intervalDue = diagAccumSec >= 0.5f
        val logSlow = slow && !diagSlowLogged
        if (diagFrame == 1 || logSlow || intervalDue) {
            val s = driver.frameStats()
            val flag = when {
                logSlow -> "SLOW"
                diagFrame == 1 -> "first"
                else -> "hb"
            }
            TouchDiagnostics.log(
                "shuffle $flag frame=$diagFrame " +
                    "dt=${TouchDiagnostics.fmt(dtSec)} stepMs=${fmtMs(stepMs)} " +
                    "maxMs=${fmtMs(diagMaxStepMs)} " +
                    "physMs=${s?.let { fmtMs(it.stepMs) } ?: "?"} " +
                    "sub=${s?.substeps ?: 0} contacts=${s?.contacts ?: 0} " +
                    "awake=${s?.awake ?: 0} grip=${s?.gripCount ?: 0} " +
                    "stroke=${s?.strokeActive == true} " +
                    "budgetCut=${s?.budgetCut == true} " +
                    "t=${s?.let { TouchDiagnostics.fmt(it.shuffleTimeSec) } ?: "?"} " +
                    "fps=${Gdx.graphics.framesPerSecond}",
            )
            if (logSlow) diagSlowLogged = true
            if (intervalDue) {
                diagAccumSec = 0f
                diagMaxStepMs = 0f
                diagSlowLogged = false
            }
        }
    }

    private fun fmtMs(ms: Float): String = "%.1f".format(ms)
}
