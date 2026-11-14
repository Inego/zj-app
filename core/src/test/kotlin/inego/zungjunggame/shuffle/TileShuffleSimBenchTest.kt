package inego.zungjunggame.shuffle

import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Local timing probe for wall-build schedules (not a correctness gate on CI
 * wall-clock — only relative structure + basic smoke).
 *
 * Run: `./gradlew :core:test --tests inego.zungjunggame.shuffle.TileShuffleSimBenchTest`
 */
class TileShuffleSimBenchTest {

    @Test
    fun scheduleStepCostComparison() {
        val tileL = 40f
        val tileW = tileL * 0.72f
        val table = 800f

        fun run(cfg: ShuffleSimConfig, frames: Int, seed: Int): Triple<Double, Double, String> {
            val sim = TileShuffleSim(cfg, tileCount = 136, maxHands = 1)
            val rng = Random(seed)
            val out = IntArray(7)
            sim.setupPackedGrid(
                cols = 17,
                gap = tileL * 0.06f,
                angleJitter = 0.1f,
                positionJitter = tileL * 0.04f,
                rng = rng,
            )
            val n = sim.pickCompactCluster(7, out)
            if (n > 0) {
                sim.gripTiles(0, out, n)
                sim.setHandMotion(0, sim.hands[0].x, sim.hands[0].y, tileL * 9f, tileL * 2f)
            }
            repeat(8) { sim.step(1f / 60f) }
            var total = 0L
            var max = 0L
            var sumC = 0
            var sumA = 0
            var sumS = 0
            var cuts = 0
            repeat(frames) { i ->
                if (i % 6 == 0) {
                    if (sim.hands[0].hasGrip) sim.releaseHand(0)
                    val nn = sim.pickCompactCluster(7, out)
                    if (nn > 0) {
                        sim.gripTiles(0, out, nn)
                        sim.setHandMotion(0, sim.hands[0].x, sim.hands[0].y, tileL * 9f, 0f)
                    }
                }
                val ns = measureNanoTime { sim.step(1f / 60f) }
                total += ns
                if (ns > max) max = ns
                sumC += sim.lastContactCount
                sumA += sim.awakeCount()
                sumS += sim.lastStepSubsteps
                if (sim.lastStepBudgetCut) cuts++
            }
            val avgMs = total / frames / 1e6
            val maxMs = max / 1e6
            val detail =
                "avgMs=${"%.2f".format(avgMs)} maxMs=${"%.2f".format(maxMs)} " +
                    "avgSub=${"%.1f".format(sumS.toDouble() / frames)} " +
                    "avgC=${"%.0f".format(sumC.toDouble() / frames)} " +
                    "avgAwake=${"%.0f".format(sumA.toDouble() / frames)} " +
                    "budgetCuts=$cuts"
            return Triple(avgMs, maxMs, detail)
        }

        val high = ShuffleSimConfig.wallBuildTutorial(tileL, tileW, table, ShuffleSolverSchedule.High)
        val interactive = ShuffleSimConfig.wallBuildTutorial(
            tileL, tileW, table, ShuffleSolverSchedule.Interactive,
        )
        // Unlimited budget so bench measures pure sim cost of Interactive knobs.
        val interactiveUnlimited = interactive.copy(
            schedule = ShuffleSolverSchedule.Interactive.copy(stepTimeBudgetMs = 0f),
        )

        val highR = run(high, frames = 40, seed = 1)
        val intR = run(interactiveUnlimited, frames = 40, seed = 1)
        println("HIGH:         ${highR.third}")
        println("INTERACTIVE:  ${intR.third}")

        // Structural expectation: Interactive work schedule is substantially cheaper.
        // (Absolute ms is machine-dependent; ratio is the industrial check.)
        assertTrue(
            intR.first < highR.first * 0.75 + 0.05,
            "Interactive should be cheaper than High: int=${intR.first} high=${highR.first}",
        )

        for (sep in listOf(0, 1, 2, 3, 6, 10)) {
            val cfg = high.copy(
                schedule = ShuffleSolverSchedule.High.copy(hardSeparationIterations = sep),
            )
            val r = run(cfg, frames = 24, seed = 2)
            println("sepIters=$sep: ${r.third}")
        }

        val budgeted = run(interactive, frames = 30, seed = 3)
        println("INTERACTIVE+budget: ${budgeted.third}")
    }

    @Test
    fun interactiveStillSeparatesOverlaps() {
        val tileL = 40f
        val tileW = tileL * 0.72f
        val cfg = ShuffleSimConfig.wallBuildTutorial(
            tileLength = tileL,
            tileWidth = tileW,
            tableSide = 800f,
            schedule = ShuffleSolverSchedule.Interactive.copy(stepTimeBudgetMs = 0f),
        )
        val sim = TileShuffleSim(cfg, tileCount = 2)
        sim.tiles[0].setPose(-tileW * 0.25f, 0f, 0f)
        sim.tiles[1].setPose(tileW * 0.25f, 0f, 0f)
        sim.tiles[0].setVelocity(0f, 0f, 0f)
        sim.tiles[1].setVelocity(0f, 0f, 0f)

        val minSep = tileW * 0.85f
        repeat(12) { sim.subStep(cfg.fixedDt) }

        val dx = sim.tiles[1].x - sim.tiles[0].x
        val dy = sim.tiles[1].y - sim.tiles[0].y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue(dist >= minSep, "Interactive schedule left overlap dist=$dist min=$minSep")
        assertTrue(
            sim.maxPenetration() <= cfg.positionSeparationSlop * 8f + 1e-3f,
            "residual pen=${sim.maxPenetration()}",
        )
    }
}
