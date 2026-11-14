package inego.zungjunggame.shuffle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileShuffleSimTest {

    @Test
    fun packedGridPlacesAllTilesOnTable() {
        val cfg = ShuffleSimConfig(tableSide = 24f)
        val sim = TileShuffleSim(cfg, tileCount = 136)
        sim.setupPackedGrid(cols = 17, gap = 0.05f)

        val margin = cfg.halfLength + 0.01f
        for (t in sim.tiles) {
            assertTrue(t.x >= cfg.tableMinX + margin * 0.5f, "x=${t.x}")
            assertTrue(t.x <= cfg.tableMaxX - margin * 0.5f, "x=${t.x}")
            assertTrue(t.y >= cfg.tableMinY + margin * 0.5f, "y=${t.y}")
            assertTrue(t.y <= cfg.tableMaxY - margin * 0.5f, "y=${t.y}")
        }
    }

    @Test
    fun wallReflectsInboundTile() {
        val cfg = ShuffleSimConfig(
            tableSide = 10f,
            surfaceMuK = 0f,
            angularDamping = 0f,
            restitution = 0.2f,
            sleepTime = 10f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 1)
        val t = sim.tiles[0]
        t.setPose(cfg.tableMinX + cfg.halfWidth + 0.05f, 0f, 0f)
        t.setVelocity(-4f, 0f, 0f)

        repeat(60) { sim.subStep(cfg.fixedDt) }

        assertTrue(t.x >= cfg.tableMinX - 0.05f, "still past wall x=${t.x}")
        assertTrue(t.x < cfg.tableCenterX, "should stay on left half x=${t.x}")
        assertTrue(t.vx > -4f, "vx should increase after wall hit, vx=${t.vx}")
    }

    @Test
    fun overlappingTilesSeparate() {
        val cfg = ShuffleSimConfig(
            tableSide = 12f,
            surfaceMuK = 0.05f,
            restitution = 0.1f,
            sleepTime = 5f,
            positionSeparationSlop = 0.001f,
            schedule = ShuffleSolverSchedule.High.copy(hardSeparationIterations = 12),
        )
        val sim = TileShuffleSim(cfg, tileCount = 2)
        sim.tiles[0].setPose(-0.1f, 0f, 0f)
        sim.tiles[1].setPose(0.1f, 0f, 0f)
        sim.tiles[0].setVelocity(0f, 0f, 0f)
        sim.tiles[1].setVelocity(0f, 0f, 0f)

        val minSep = cfg.tileWidth * 0.85f
        // Hard separation should clear deep overlap within a few substeps.
        repeat(8) { sim.subStep(cfg.fixedDt) }

        val dx = sim.tiles[1].x - sim.tiles[0].x
        val dy = sim.tiles[1].y - sim.tiles[0].y
        val dist = sqrt(dx * dx + dy * dy)
        assertTrue(dist >= minSep, "tiles still overlapping dist=$dist min=$minSep")
        assertTrue(
            sim.maxPenetration() <= cfg.positionSeparationSlop * 4f + 1e-4f,
            "residual pen=${sim.maxPenetration()}",
        )
    }

    @Test
    fun gripPushDoesNotLeaveVisibleOverlap() {
        val cfg = ShuffleSimConfig(
            tableSide = 16f,
            surfaceMuK = 0.9f,
            surfaceStopAccelScale = 28f,
            kinematicMaxDeltaVScale = 1.1f,
            freeMaxSpeedScale = 6f,
            positionSeparationSlop = 0.002f,
            sleepTime = 5f,
            schedule = ShuffleSolverSchedule.High.copy(hardSeparationIterations = 12),
        )
        val sim = TileShuffleSim(cfg, tileCount = 4)
        // Gripped tile driven into a free wall of three.
        sim.tiles[0].setPose(-1.2f, 0f, 0f)
        sim.tiles[1].setPose(0.2f, 0f, 0f)
        sim.tiles[2].setPose(0.2f + cfg.tileWidth * 1.05f, 0f, 0f)
        sim.tiles[3].setPose(0.2f + cfg.tileWidth * 2.1f, 0f, 0f)
        assertEquals(1, sim.gripTiles(0, intArrayOf(0)))

        var x = sim.hands[0].x
        val y = sim.hands[0].y
        val speed = cfg.tileLength * 8f
        repeat(50) {
            x += speed * cfg.fixedDt
            sim.setHandMotion(0, x, y, speed, 0f)
            sim.subStep(cfg.fixedDt)
            val pen = sim.maxPenetration()
            assertTrue(
                pen <= cfg.positionSeparationSlop * 8f + 0.02f,
                "visible nest during push pen=$pen step",
            )
        }
    }

    @Test
    fun grippedClusterMovesTogetherAndPushesOthers() {
        val cfg = ShuffleSimConfig(
            tableSide = 16f,
            surfaceMuK = 0.05f,
            sleepTime = 5f,
            strokeGripMin = 1,
            strokeGripMax = 7,
        )
        val sim = TileShuffleSim(cfg, tileCount = 12)
        // Compact row in the center.
        for (i in 0 until 12) {
            sim.tiles[i].setPose(-3f + i * cfg.tileWidth * 1.05f, 0f, 0f)
            sim.tiles[i].setVelocity(0f, 0f, 0f)
        }

        val indices = intArrayOf(1, 2, 3, 4, 5)
        val n = sim.gripTiles(0, indices)
        assertEquals(5, n, "should grip 5 tiles, got $n")
        assertTrue(sim.hands[0].hasGrip)

        val palmX0 = sim.hands[0].x
        val palmY0 = sim.hands[0].y
        val gripped = (0 until sim.hands[0].gripCount).map { sim.hands[0].gripTiles[it] }
        val rel = gripped.map { ti ->
            val t = sim.tiles[ti]
            floatArrayOf(t.x - palmX0, t.y - palmY0, t.theta)
        }

        var x = palmX0
        val speed = 6f
        val dt = cfg.fixedDt
        repeat(50) {
            x += speed * dt
            sim.setHandMotion(0, x, palmY0, speed, 0f)
            sim.subStep(dt)
        }

        for (s in gripped.indices) {
            val ti = gripped[s]
            val t = sim.tiles[ti]
            val ox = rel[s][0]
            val oy = rel[s][1]
            val th = rel[s][2]
            assertTrue(abs(t.x - (x + ox)) < 0.05f, "grip ox broken tile=$ti")
            assertTrue(abs(t.y - (palmY0 + oy)) < 0.05f, "grip oy broken tile=$ti")
            assertTrue(abs(t.theta - th) < 0.05f, "grip theta broken tile=$ti")
        }

        val ahead = sim.tiles[10]
        assertTrue(
            ahead.x > -3f + 10 * cfg.tileWidth * 1.05f - 0.5f || ahead.speedSq() > 0.01f,
            "cluster should disturb free tiles ahead",
        )
    }

    @Test
    fun pickCompactClusterSeedsFarthestFromSquareCenterWithinRadius() {
        val cfg = ShuffleSimConfig(
            tableSide = 30f,
            tableCenterX = 0f,
            tableCenterY = 0f,
            strokeGripMin = 1,
            strokeGripMax = 7,
            clusterNeighborScale = 2f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 6)
        // Off-center pile so farthest-from-COM ≠ farthest-from-square-center:
        // pile around x≈4, left tile at −2 (far from pile COM, closer to center),
        // rim pair at ~8 (farthest from square center) with a neighbor.
        val xs = floatArrayOf(3.5f, 4f, 4.5f, -2f, 8f, 8.6f)
        val ys = floatArrayOf(0f, 0.1f, -0.1f, 0f, 0f, 0.1f)
        sim.setupFromPoses(xs, ys)

        val out = IntArray(10)
        val n = sim.pickCompactCluster(maxCount = 7, out = out)
        assertTrue(n in 1..7, "expected 1–7 tiles, got $n")

        // Seed must be the free tile farthest from the square center (index 4 or 5).
        var farTi = 0
        var farD2 = -1f
        for (i in 0 until 6) {
            val t = sim.tiles[i]
            val dx = t.x - cfg.tableCenterX
            val dy = t.y - cfg.tableCenterY
            val d2 = dx * dx + dy * dy
            if (d2 > farD2) {
                farD2 = d2
                farTi = i
            }
        }
        assertEquals(out[0], farTi, "seed should be farthest from center seed=${out[0]} far=$farTi")
        assertTrue(farTi == 4 || farTi == 5, "rim outlier should win far=$farTi")
        // COM-farthest would prefer the left tile (−2); square-center must not.
        assertTrue(out[0] != 3, "must not seed left COM-outlier")

        val l = maxOf(cfg.tileLength, cfg.tileWidth)
        val r = l * cfg.clusterNeighborScale
        val seed = sim.tiles[out[0]]
        for (i in 0 until n) {
            val t = sim.tiles[out[i]]
            val dx = t.x - seed.x
            val dy = t.y - seed.y
            assertTrue(
                dx * dx + dy * dy <= r * r * 1.01f,
                "tile ${out[i]} outside 2L radius of seed",
            )
        }
        // Rim pair should both be gripped when within radius.
        assertTrue(n >= 2, "neighbor of outlier should be included n=$n")
    }

    @Test
    fun kinematicPushDoesNotSlingshotFreeTile() {
        // Grip drives into a resting free tile: free speed must stay well below
        // palm speed (heavy tiles scrape, not fly).
        val cfg = ShuffleSimConfig(
            tableSide = 20f,
            surfaceMuK = 0.9f,
            surfaceStopAccelScale = 28f,
            restitution = 0.05f,
            kinematicMaxDeltaVScale = 1.1f,
            freeMaxSpeedScale = 6.5f,
            sleepTime = 5f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 2)
        sim.tiles[0].setPose(-2f, 0f, 0f)
        sim.tiles[1].setPose(0.5f, 0f, 0f)
        assertEquals(1, sim.gripTiles(0, intArrayOf(0)))

        val palmSpeed = cfg.tileLength * 10f
        var x = sim.hands[0].x
        val y = sim.hands[0].y
        repeat(40) {
            x += palmSpeed * cfg.fixedDt
            sim.setHandMotion(0, x, y, palmSpeed, 0f)
            sim.subStep(cfg.fixedDt)
        }

        val free = sim.tiles[1]
        val freeSp = sqrt(free.speedSq())
        val maxAllowed = cfg.tileLength * cfg.freeMaxSpeedScale + 0.05f
        assertTrue(freeSp <= maxAllowed, "free slingshot freeSp=$freeSp max=$maxAllowed")
        // Soft push: should not fully match palm in one short shove.
        assertTrue(freeSp < palmSpeed * 0.85f, "free should lag palm freeSp=$freeSp palm=$palmSpeed")
    }

    @Test
    fun freeTilesCenterOfMassExcludesGripped() {
        val cfg = ShuffleSimConfig(tableSide = 20f)
        val sim = TileShuffleSim(cfg, tileCount = 6)
        // Two groups: left free pile and right pile we will grip.
        val xs = floatArrayOf(-4f, -3f, -2f, 3f, 4f, 5f)
        val ys = floatArrayOf(0f, 0.5f, -0.5f, 0f, 0.2f, -0.2f)
        sim.setupFromPoses(xs, ys)

        val out = FloatArray(2)
        assertTrue(sim.freeTilesCenterOfMass(out), "all free initially")
        assertTrue(abs(out[0] - xs.average().toFloat()) < 1e-4f, "com x=${out[0]}")
        assertTrue(abs(out[1] - ys.average().toFloat()) < 1e-4f, "com y=${out[1]}")

        assertEquals(3, sim.gripTiles(0, intArrayOf(3, 4, 5)))
        assertTrue(sim.freeTilesCenterOfMass(out), "left tiles still free")
        val freeX = (xs[0] + xs[1] + xs[2]) / 3f
        val freeY = (ys[0] + ys[1] + ys[2]) / 3f
        assertTrue(abs(out[0] - freeX) < 1e-4f, "free com x=${out[0]} expected $freeX")
        assertTrue(abs(out[1] - freeY) < 1e-4f, "free com y=${out[1]} expected $freeY")

        // Helper still excludes gripped tiles; free COM stays left of right-side palm.
        val palmX = sim.hands[0].x
        assertTrue(out[0] < palmX, "free COM should be left of grip palm")
    }

    @Test
    fun grippedClusterCannotLeaveTable() {
        val cfg = ShuffleSimConfig(
            tableSide = 12f,
            surfaceMuK = 0.05f,
            sleepTime = 5f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 8)
        // Pack near the right edge, then shove hard past the wall.
        val x0 = cfg.tableMaxX - cfg.halfWidth - 0.5f
        for (i in 0 until 8) {
            sim.tiles[i].setPose(x0 - i * cfg.tileWidth * 1.05f, 0f, 0.3f)
            sim.tiles[i].setVelocity(0f, 0f, 0f)
        }
        val indices = intArrayOf(0, 1, 2, 3, 4, 5)
        assertTrue(sim.gripTiles(0, indices) >= 5)
        var x = sim.hands[0].x
        val y = sim.hands[0].y
        val dt = cfg.fixedDt
        val speed = 20f
        repeat(80) {
            x += speed * dt
            sim.setHandMotion(0, x, y, speed, 0f)
            sim.subStep(dt)
        }
        // Driver may have advanced palm outside; sim must keep tile OBBs inside.
        for (t in sim.tiles) {
            assertTileObbInsideTable(t, cfg, label = "gripped id=${t.id}")
        }
    }

    @Test
    fun freeTileShovedIntoWallStaysInside() {
        val cfg = ShuffleSimConfig(
            tableSide = 10f,
            surfaceMuK = 0.1f,
            restitution = 0.05f,
            sleepTime = 5f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 3)
        // Grip two tiles left of a free tile near the right wall; push right.
        sim.tiles[0].setPose(1f, 0f, 0f)
        sim.tiles[1].setPose(1f + cfg.tileWidth * 1.05f, 0f, 0f)
        sim.tiles[2].setPose(cfg.tableMaxX - cfg.halfWidth - 0.15f, 0f, 0f)
        assertEquals(2, sim.gripTiles(0, intArrayOf(0, 1)))
        var x = sim.hands[0].x
        val y = sim.hands[0].y
        val dt = cfg.fixedDt
        val speed = 12f
        repeat(100) {
            x += speed * dt
            sim.setHandMotion(0, x, y, speed, 0f)
            sim.subStep(dt)
        }
        assertTileObbInsideTable(sim.tiles[2], cfg, label = "free tile")
        for (i in 0 until 2) {
            assertTileObbInsideTable(sim.tiles[i], cfg, label = "grip $i")
        }
    }

    private fun assertTileObbInsideTable(
        t: ShuffleTileBody,
        cfg: ShuffleSimConfig,
        label: String,
        margin: Float = 0.02f,
    ) {
        val c = cos(t.theta)
        val s = sin(t.theta)
        val hx = cfg.halfWidth
        val hy = cfg.halfLength
        for (sx in floatArrayOf(-hx, hx)) {
            for (sy in floatArrayOf(-hy, hy)) {
                val px = t.x + sx * c - sy * s
                val py = t.y + sx * s + sy * c
                assertTrue(px >= cfg.tableMinX - margin, "$label corner x=$px left of table")
                assertTrue(px <= cfg.tableMaxX + margin, "$label corner x=$px right of table")
                assertTrue(py >= cfg.tableMinY - margin, "$label corner y=$py below table")
                assertTrue(py <= cfg.tableMaxY + margin, "$label corner y=$py above table")
            }
        }
    }

    @Test
    fun releaseLeavesTilesPassive() {
        val cfg = ShuffleSimConfig(tableSide = 14f)
        val sim = TileShuffleSim(cfg, tileCount = 8)
        sim.setupPackedGrid(cols = 4, gap = 0.08f)

        val indices = intArrayOf(0, 1, 2, 3, 4)
        assertTrue(sim.gripTiles(0, indices) >= 1)
        sim.setHandMotion(0, sim.hands[0].x + 1f, sim.hands[0].y, 4f, 0f)
        sim.releaseHand(0)
        assertTrue(!sim.hands[0].hasGrip)
        for (t in sim.tiles) {
            assertTrue(!t.kinematic, "no tile should stay kinematic after release")
        }
    }

    @Test
    fun freeTilesCoastBrieflyThenStop() {
        val cfg = ShuffleSimConfig(
            tableSide = 16f,
            surfaceMuK = 0.7f,
            surfaceStopAccelScale = 16f,
            restitution = 0f,
            sleepTime = 0.1f,
            linearSleepEpsilon = 0.08f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 1)
        val t = sim.tiles[0]
        t.setPose(0f, 0f, 0f)
        t.setVelocity(5f, 0f, 2f)
        val x0 = t.x
        // Early: still moving (inertia).
        repeat(4) { sim.subStep(cfg.fixedDt) }
        assertTrue(t.speedSq() > 0.01f || abs(t.x - x0) > 0.02f, "should slide a bit at first")
        // Later: fully stopped, short coast only.
        repeat(80) { sim.subStep(cfg.fixedDt) }
        assertTrue(t.speedSq() < 1e-4f, "still sliding speedSq=${t.speedSq()}")
        assertTrue(abs(t.omega) < 1e-3f, "still spinning omega=${t.omega}")
        assertTrue(abs(t.x - x0) < cfg.tileLength * 1.6f, "coasted too far dx=${t.x - x0}")
        assertTrue(abs(t.x - x0) > cfg.tileLength * 0.05f, "no inertia at all dx=${t.x - x0}")
    }

    @Test
    fun releaseKeepsPartialHandVelocity() {
        val cfg = ShuffleSimConfig(
            tableSide = 14f,
            surfaceMuK = 0.7f,
            releaseVelocityKeep = 0.6f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 4)
        sim.setupPackedGrid(cols = 2, gap = 0.08f)
        val t0 = sim.tiles[0]
        assertEquals(1, sim.gripTiles(0, intArrayOf(0)))
        sim.setHandMotion(0, t0.x, t0.y, 10f, 0f)
        sim.releaseHand(0)
        assertTrue(abs(t0.vx - 6f) < 0.05f, "expected ~0.6 of hand vx, got ${t0.vx}")
        assertEquals(0f, t0.omega)
    }

    @Test
    fun settlingReducesEnergyWithoutHands() {
        val cfg = ShuffleSimConfig(
            tableSide = 14f,
            surfaceMuK = 0.35f,
            angularDamping = 2f,
            restitution = 0.1f,
            sleepTime = 0.2f,
        )
        val sim = TileShuffleSim(cfg, tileCount = 32)
        sim.setupPackedGrid(cols = 8, gap = 0.06f, positionJitter = 0.02f, angleJitter = 0.1f)

        for (i in 0 until 8) {
            sim.tiles[i].setVelocity(
                (i % 3 - 1) * 3f,
                ((i + 1) % 3 - 1) * 3f,
                0.5f,
            )
        }

        val eKick = sim.kineticEnergy()
        assertTrue(eKick > 1f, "kick should give energy")

        // One push stroke then settle.
        val cluster = intArrayOf(0, 1, 2, 3, 4)
        assertTrue(sim.gripTiles(0, cluster) >= 1)
        var x = sim.hands[0].x
        val y = sim.hands[0].y
        repeat(25) {
            x += 0.12f
            sim.setHandMotion(0, x, y, 6f, 0f)
            sim.subStep(cfg.fixedDt)
        }
        sim.clearHands()
        repeat(400) { sim.subStep(cfg.fixedDt) }

        val eEnd = sim.kineticEnergy()
        assertTrue(eEnd < eKick * 0.25f, "should settle eKick=$eKick eEnd=$eEnd")
    }

    @Test
    fun stepDoesNotExplodeFullSet() {
        val cfg = ShuffleSimConfig.DEFAULT
        val sim = TileShuffleSim(cfg, tileCount = 136, maxHands = 1)
        sim.setupPackedGrid(cols = 17, gap = 0.04f, angleJitter = 0.05f)

        val rng = Random(7)
        val cluster = IntArray(10)
        var originX = 0f
        var originY = 0f
        var dirX = 1f
        var dirY = 0f
        var travel = 0f
        var elapsed = 0f
        var duration = 0.2f
        var active = false

        val dt = 1f / 60f
        repeat(90) {
            if (!active || !sim.hands[0].hasGrip) {
                val n = sim.pickCompactCluster(7, cluster)
                if (n > 0 && sim.gripTiles(0, cluster, n) > 0) {
                    originX = sim.hands[0].x
                    originY = sim.hands[0].y
                    // Aim near table square center (same policy as WallBuildView).
                    val jitterR = cfg.tileLength * cfg.strokeAimJitterScale
                    val jAng = rng.nextFloat() * (2f * PI.toFloat())
                    val jRad = jitterR * sqrt(rng.nextFloat())
                    val aimX = cfg.tableCenterX + cos(jAng) * jRad
                    val aimY = cfg.tableCenterY + sin(jAng) * jRad
                    var dx = aimX - originX
                    var dy = aimY - originY
                    var dist = sqrt(dx * dx + dy * dy)
                    if (dist < 1e-3f) {
                        dx = 1f
                        dy = 0f
                        dist = 1f
                    }
                    dirX = dx / dist
                    dirY = dy / dist
                    travel = maxOf(
                        dist + cfg.tileLength * cfg.strokePastComScale,
                        cfg.tileLength * cfg.strokeMinLengthScale,
                    )
                    val avg = cfg.tileLength * cfg.strokeAvgSpeedScale
                    duration = (travel / avg).coerceIn(cfg.strokeMinDuration, cfg.strokeMaxDuration)
                    elapsed = 0f
                    active = true
                }
            }
            if (active) {
                elapsed += dt
                val u = (elapsed / duration).coerceIn(0f, 1f)
                val e = // simple smoothstep (no render dep in shuffle tests)
                    u * u * (3f - 2f * u)
                val de = 6f * u * (1f - u)
                val palmX = originX + dirX * travel * e
                val palmY = originY + dirY * travel * e
                val speed = travel * de / duration.coerceAtLeast(1e-4f)
                sim.setHandMotion(0, palmX, palmY, dirX * speed, dirY * speed)
                if (u >= 1f) {
                    sim.releaseHand(0)
                    active = false
                }
            }
            sim.step(dt)
        }
        sim.clearHands()
        repeat(120) { sim.step(dt) }

        for (body in sim.tiles) {
            assertTrue(body.x.isFinite() && body.y.isFinite(), "non-finite pose")
            assertTrue(abs(body.x) < cfg.tableSide, "escaped table x=${body.x}")
            assertTrue(abs(body.y) < cfg.tableSide, "escaped table y=${body.y}")
            assertTrue(abs(body.vx) < 200f, "runaway vx=${body.vx}")
            assertTrue(abs(body.omega) < 200f, "runaway omega=${body.omega}")
        }
        assertTrue(sim.kineticEnergy().isFinite())
    }
}
