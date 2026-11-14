package inego.zungjunggame.shuffle

import inego.zungjunggame.engine.WALL_SIZE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import inego.zungjunggame.platform.newRandom
import kotlin.random.Random

/**
 * 2D rigid-body mahjong tile shuffle simulation.
 *
 * Implements the model in docs/shuffle-sim-model.md:
 * - 136 OBB tiles on a square table with hard borders
 * - surface kinetic friction + angular damping + sleep
 * - tile–tile SAT contacts + tile–wall contacts
 * - sequential impulse solver (normal + Coulomb friction)
 * - one unified geometric resolve (detect → separate → bounds) per substep
 * - one hand at a time grips up to 7 tiles near the free tile farthest from
 *   the table square center and pushes them (free tiles only move via contacts)
 *
 * Computational cost is governed by [ShuffleSimConfig.schedule]
 * ([ShuffleSolverSchedule]); material feel stays on the config itself.
 *
 * Pure JVM — no libGDX dependency. Units are those of [ShuffleSimConfig].
 */
class TileShuffleSim(
    val config: ShuffleSimConfig = ShuffleSimConfig.DEFAULT,
    tileCount: Int = WALL_SIZE,
    maxHands: Int = 1,
) {
    val tileCount: Int = tileCount.coerceAtLeast(0)

    val tiles: Array<ShuffleTileBody> = Array(this.tileCount) { i ->
        ShuffleTileBody(id = i, type = i / 4)
    }

    val hands: Array<ShuffleHand> = Array(maxHands.coerceAtLeast(0)) { ShuffleHand() }

    private var accumulator = 0f

    /** Substeps actually run in the most recent [step] call (for diagnostics). */
    var lastStepSubsteps: Int = 0
        private set

    /** Contact count after the last completed substep (for diagnostics). */
    var lastContactCount: Int = 0
        private set

    /**
     * Wall-clock time of the most recent [step] in milliseconds
     * (physics only; excludes driver hand logic).
     */
    var lastStepMs: Float = 0f
        private set

    /** True when [step] aborted remaining substeps due to [ShuffleSolverSchedule.stepTimeBudgetMs]. */
    var lastStepBudgetCut: Boolean = false
        private set

    // ── Contact buffer (pooled; no per-frame Contact allocs) ────────────────
    private val contacts = ArrayList<Contact>(512)
    private val contactPool = ArrayList<Contact>(512)

    // Broad-phase grid: cell → tile indices (lists returned to pool on clear)
    private val grid = HashMap<Long, ArrayList<Int>>(256)
    private val gridListPool = ArrayList<ArrayList<Int>>(256)
    private val pairSeen = HashSet<Long>(1024)

    // Scratch for OBB axes / corners / cluster pick
    private val cornerX = FloatArray(4)
    private val cornerY = FloatArray(4)
    private val supportScratch = FloatArray(2)
    private val clusterScratch = IntArray(ShuffleHand.MAX_GRIP)
    private val freeScratch = IntArray(WALL_SIZE.coerceAtLeast(tileCount))
    // fillCorners sign table (avoids floatArrayOf alloc per call)
    private val cornerSignX = floatArrayOf(-1f, -1f, 1f, 1f)
    private val cornerSignY = floatArrayOf(-1f, 1f, -1f, 1f)

    /**
     * Place [tileCount] non-overlapping tiles in a centered grid on the table.
     * Optional small random jitter for a less sterile start.
     */
    fun setupPackedGrid(
        cols: Int = 17,
        gap: Float = 0.04f,
        angleJitter: Float = 0f,
        positionJitter: Float = 0f,
        rng: Random = newRandom(),
    ) {
        val tw = config.tileWidth
        val tl = config.tileLength
        val cellW = tw + gap
        val cellH = tl + gap
        val rows = (tileCount + cols - 1) / cols
        val originX = config.tableCenterX - (cols - 1) * cellW * 0.5f
        val originY = config.tableCenterY - (rows - 1) * cellH * 0.5f
        for (i in 0 until tileCount) {
            val col = i % cols
            val row = i / cols
            val t = tiles[i]
            t.id = i
            t.type = i / 4
            var px = originX + col * cellW
            var py = originY + row * cellH
            if (positionJitter > 0f) {
                px += (rng.nextFloat() * 2f - 1f) * positionJitter
                py += (rng.nextFloat() * 2f - 1f) * positionJitter
            }
            var ang = 0f
            if (angleJitter > 0f) {
                ang = (rng.nextFloat() * 2f - 1f) * angleJitter
            }
            t.setPose(px, py, ang)
            t.setVelocity(0f, 0f, 0f)
            t.awake = true
            t.sleepTimer = 0f
            t.clearForces()
        }
        clampAllInsideTable()
        accumulator = 0f
    }

    /**
     * Place tiles from caller-supplied centers/angles (e.g. after face-down layout).
     * Length must match [tileCount]. Velocities are zeroed.
     */
    fun setupFromPoses(
        xs: FloatArray,
        ys: FloatArray,
        thetas: FloatArray? = null,
        types: IntArray? = null,
    ) {
        require(xs.size >= tileCount && ys.size >= tileCount) {
            "Need at least $tileCount poses"
        }
        for (i in 0 until tileCount) {
            val t = tiles[i]
            t.id = i
            if (types != null && i < types.size) t.type = types[i]
            val ang = if (thetas != null && i < thetas.size) thetas[i] else 0f
            t.setPose(xs[i], ys[i], ang)
            t.setVelocity(0f, 0f, 0f)
            t.awake = true
            t.sleepTimer = 0f
            t.clearForces()
        }
        accumulator = 0f
    }

    fun clearHands() {
        for (hi in hands.indices) {
            if (hands[hi].hasGrip) releaseGrip(hi)
            hands[hi].deactivate()
        }
        clearAllKinematicFlags()
    }

    /**
     * Grip the given free tiles as a rigid cluster. Palm is placed at their
     * centroid; each tile's offset and angle are locked for the stroke.
     * Returns how many tiles were gripped (0 if none free / empty).
     */
    fun gripTiles(handIndex: Int, indices: IntArray, count: Int = indices.size): Int {
        require(handIndex in hands.indices) { "hand index $handIndex out of range" }
        if (hands[handIndex].hasGrip) releaseGrip(handIndex)
        val n = count.coerceIn(0, min(indices.size, ShuffleHand.MAX_GRIP))
        if (n <= 0) return 0

        var cx = 0f
        var cy = 0f
        var used = 0
        for (i in 0 until n) {
            val ti = indices[i]
            if (ti !in 0 until tileCount) continue
            val t = tiles[ti]
            if (t.kinematic) continue
            cx += t.x
            cy += t.y
            used++
        }
        if (used == 0) return 0
        val inv = 1f / used
        cx *= inv
        cy *= inv

        val hand = hands[handIndex]
        hand.setMotion(cx, cy, 0f, 0f)
        var slot = 0
        for (i in 0 until n) {
            if (slot >= ShuffleHand.MAX_GRIP) break
            val ti = indices[i]
            if (ti !in 0 until tileCount) continue
            val t = tiles[ti]
            if (t.kinematic) continue
            hand.setGripSlot(slot, ti, t.x - cx, t.y - cy, t.theta)
            t.kinematic = true
            t.grippedBy = handIndex
            t.awake = true
            t.sleepTimer = 0f
            t.vx = 0f
            t.vy = 0f
            t.omega = 0f
            slot++
        }
        hand.finalizeGripCount(slot)
        return slot
    }

    /**
     * Drive the palm this frame (position authoritative). Gripped tiles snap
     * to locked offsets with velocity ([vx], [vy]).
     */
    fun setHandMotion(handIndex: Int, x: Float, y: Float, vx: Float = 0f, vy: Float = 0f) {
        require(handIndex in hands.indices) { "hand index $handIndex out of range" }
        hands[handIndex].setMotion(x, y, vx, vy)
    }

    /** Release gripped tiles; they keep a fraction of palm velocity. */
    fun releaseHand(handIndex: Int) {
        require(handIndex in hands.indices) { "hand index $handIndex out of range" }
        if (hands[handIndex].hasGrip) releaseGrip(handIndex)
        hands[handIndex].deactivate()
    }

    /**
     * Pick a free-tile cluster for the next hand stroke:
     * 1. Seed = free tile farthest from the **table square center**
     *    ([ShuffleSimConfig.tableCenterX], [ShuffleSimConfig.tableCenterY]).
     * 2. Take that seed plus free tiles within [clusterNeighborScale] tile
     *    lengths (center-to-center), closest first, up to [maxCount] (default 7).
     *
     * Writes indices into [out] and returns count (0 if no free tiles).
     */
    fun pickCompactCluster(
        maxCount: Int = config.strokeGripMax,
        out: IntArray = clusterScratch,
    ): Int {
        val wantMax = maxCount.coerceIn(1, min(out.size, ShuffleHand.MAX_GRIP))

        var nFree = 0
        for (i in 0 until tileCount) {
            if (tiles[i].kinematic) continue
            if (nFree < freeScratch.size) freeScratch[nFree++] = i
        }
        if (nFree == 0) return 0
        if (nFree == 1) {
            out[0] = freeScratch[0]
            return 1
        }

        // Outlier toward the rim: farthest free tile from the square center.
        val cx = config.tableCenterX
        val cy = config.tableCenterY
        var seedFi = 0
        var bestD2 = -1f
        for (fi in 0 until nFree) {
            val t = tiles[freeScratch[fi]]
            val dx = t.x - cx
            val dy = t.y - cy
            val d2 = dx * dx + dy * dy
            if (d2 > bestD2) {
                bestD2 = d2
                seedFi = fi
            }
        }
        val seed = freeScratch[seedFi]
        val seedBody = tiles[seed]

        val neighborR = max(config.tileLength, config.tileWidth) * config.clusterNeighborScale
        val neighborR2 = neighborR * neighborR

        // Collect free tiles in radius; keep closest to seed up to wantMax.
        // Scratch layout: freeScratch[0..nFree) still holds free indices; we
        // sort a parallel distance list via selection into [out].
        out[0] = seed
        var count = 1
        while (count < wantMax) {
            var bestTi = -1
            var bestNear = Float.MAX_VALUE
            for (fi in 0 until nFree) {
                val ti = freeScratch[fi]
                if (ti == seed) continue
                var already = false
                for (s in 0 until count) {
                    if (out[s] == ti) {
                        already = true
                        break
                    }
                }
                if (already) continue
                val t = tiles[ti]
                val dx = t.x - seedBody.x
                val dy = t.y - seedBody.y
                val d2 = dx * dx + dy * dy
                if (d2 <= neighborR2 && d2 < bestNear) {
                    bestNear = d2
                    bestTi = ti
                }
            }
            if (bestTi < 0) break
            out[count++] = bestTi
        }
        return count
    }

    /**
     * Mean position of free (non-kinematic) tiles — i.e. the center of mass of
     * everything not currently gripped, when mass is uniform.
     *
     * Writes `(x, y)` into [out] at indices 0 and 1. Returns `false` if there
     * are no free tiles (caller should aim elsewhere).
     */
    fun freeTilesCenterOfMass(out: FloatArray): Boolean {
        require(out.size >= 2) { "out needs at least 2 slots" }
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until tileCount) {
            val t = tiles[i]
            if (t.kinematic) continue
            sx += t.x
            sy += t.y
            n++
        }
        if (n == 0) return false
        val inv = 1f / n
        out[0] = sx * inv
        out[1] = sy * inv
        return true
    }

    /**
     * Advance by wall-clock [dt] seconds using fixed substeps
     * ([ShuffleSolverSchedule.fixedDt]).
     *
     * Honours [ShuffleSolverSchedule.stepTimeBudgetMs]: if the budget is spent
     * mid-frame, remaining substeps are skipped and the accumulator drained so
     * lag cannot compound into a multi-frame stall.
     */
    fun step(dt: Float) {
        if (dt <= 0f || tileCount == 0) {
            lastStepSubsteps = 0
            lastStepMs = 0f
            lastStepBudgetCut = false
            return
        }
        val t0 = System.nanoTime()
        val schedule = config.schedule
        val h = schedule.fixedDt
        val maxSub = schedule.maxSubSteps
        val budgetNs = if (schedule.stepTimeBudgetMs > 0f) {
            (schedule.stepTimeBudgetMs * 1_000_000f).toLong()
        } else {
            Long.MAX_VALUE
        }
        accumulator = min(accumulator + dt, h * maxSub)
        var steps = 0
        var budgetCut = false
        while (accumulator >= h && steps < maxSub) {
            if (steps > 0 && budgetNs < Long.MAX_VALUE &&
                System.nanoTime() - t0 >= budgetNs
            ) {
                budgetCut = true
                break
            }
            subStep(h)
            accumulator -= h
            steps++
        }
        // Drop sim-time debt rather than banking it — UI responsiveness wins.
        if (budgetCut) {
            accumulator = 0f
        }
        lastStepSubsteps = steps
        lastStepBudgetCut = budgetCut
        lastStepMs = (System.nanoTime() - t0) / 1_000_000f
    }

    /**
     * Single fixed substep (for tests / deterministic control).
     *
     * Pipeline:
     * 1. surface friction + integrate velocities
     * 2. grip kinematics, detect, soft impulse solve
     * 3. integrate positions + soft positional nudge
     * 4. **one** unified geometric resolve (detect→separate→bounds, early-out)
     * 5. sleep
     *
     * The old double hard-separation pass (pre/post rim clamp) is replaced by
     * interleaving bounds inside the separation loop — same visual non-nesting
     * guarantee at roughly half the detect cost.
     */
    fun subStep(dt: Float) {
        applySurfaceFriction()
        integrateVelocities(dt)
        // Re-assert grip velocities after force integration.
        applyGripKinematics()
        detectContacts()
        // Two immovable clusters cannot both "win" — drop clashing grips so the
        // free-body solver can separate them (otherwise they tunnel).
        releaseConflictingGrips()
        solveContacts(dt)
        // Infinite-mass grips + bias can still over-accelerate free tiles;
        // hard cap keeps the mash heavy instead of slingshot.
        clampFreeTileSpeeds()
        integratePositions(dt)
        // Soft residual nudge on the velocity-solve contact list, then palm
        // wins pose, then one unified hard geometric resolve.
        positionalCorrection()
        applyGripKinematics()
        resolveGeometry()
        updateSleep(dt)
        for (t in tiles) t.clearForces()
        lastContactCount = contacts.size
    }

    /** How many tiles are currently awake (for diagnostics). */
    fun awakeCount(): Int {
        var n = 0
        for (t in tiles) if (t.awake) n++
        return n
    }

    fun kineticEnergy(): Float {
        var e = 0f
        val m = config.mass
        val i = config.inertia
        for (t in tiles) {
            e += 0.5f * m * t.speedSq() + 0.5f * i * t.omega * t.omega
        }
        return e
    }


    // ── Hands / grips ───────────────────────────────────────────────────────

    /**
     * If tiles held by different palms overlap, release both grips. Two
     * kinematic bodies have infinite mass, so the impulse solver cannot
     * separate them and grip snaps would drive them through each other.
     * With the single-hand driver this is a no-op; kept for multi-hand use.
     */
    private fun releaseConflictingGrips() {
        if (contacts.isEmpty() || hands.size < 2) return
        for (c in contacts) {
            if (c.b < 0) continue
            val a = tiles[c.a]
            val b = tiles[c.b]
            if (!a.kinematic || !b.kinematic) continue
            val ha = a.grippedBy
            val hb = b.grippedBy
            if (ha < 0 || hb < 0 || ha == hb) continue
            if (hands[ha].hasGrip) releaseGrip(ha)
            if (hands[hb].hasGrip) releaseGrip(hb)
        }
    }

    private fun releaseGrip(handIndex: Int) {
        val hand = hands[handIndex]
        for (s in 0 until hand.gripCount) {
            val ti = hand.gripTiles[s]
            if (ti !in 0..<tileCount) continue
            val t = tiles[ti]
            // Keep some hand velocity (inertia) but not full ice-slide.
            val keep = config.releaseVelocityKeep
            t.vx = hand.vx * keep
            t.vy = hand.vy * keep
            t.omega = 0f
            t.kinematic = false
            t.grippedBy = -1
            t.awake = true
            t.sleepTimer = 0f
        }
        hand.clearGrip()
    }

    private fun applyGripKinematics() {
        for (hi in hands.indices) {
            val hand = hands[hi]
            if (!hand.hasGrip) continue
            for (s in 0 until hand.gripCount) {
                val ti = hand.gripTiles[s]
                if (ti !in 0..<tileCount) continue
                val t = tiles[ti]
                t.x = hand.x + hand.gripOx[s]
                t.y = hand.y + hand.gripOy[s]
                t.theta = hand.gripTheta[s]
                t.vx = hand.vx
                t.vy = hand.vy
                t.omega = 0f
                t.kinematic = true
                t.grippedBy = hi
                t.awake = true
                t.sleepTimer = 0f
            }
        }
    }

    private fun clearAllKinematicFlags() {
        for (t in tiles) {
            t.kinematic = false
            t.grippedBy = -1
        }
    }

    // ── Forces ──────────────────────────────────────────────────────────────

    /**
     * Felt table with inertia: free tiles coast a short distance after a shove,
     * then stop. Not ice (long glide) and not glue (instant freeze).
     * Hands grip/kinematic; free motion is contacts + residual velocity under friction.
     */
    private fun applySurfaceFriction() {
        val cW = config.angularDamping
        for (t in tiles) {
            if (!t.awake || t.kinematic) continue
            t.torque += -cW * t.omega
        }
    }

    private fun integrateVelocities(dt: Float) {
        val invM = config.invMass
        val invI = config.invInertia
        for (t in tiles) {
            if (!t.awake || t.kinematic) continue
            t.vx += t.fx * invM * dt
            t.vy += t.fy * invM * dt
            t.omega += t.torque * invI * dt
        }
        applySurfaceVelocityStop(dt)
    }

    /**
     * Coulomb stop in velocity space (stable, scale-correct).
     * a ≈ μ g, with a modest floor so residual crawl still dies.
     */
    private fun applySurfaceVelocityStop(dt: Float) {
        if (dt <= 0f) return
        val mu = config.surfaceMuK
        if (mu <= 0f) return // allow frictionless tests
        val g = config.gravity
        val invI = config.invInertia
        val l = max(config.tileLength, config.tileWidth)
        // Floor ~18 L/s² → a few tenths of a tile of coast after a brisk shove.
        val floorA = l * config.surfaceStopAccelScale
        val aStop = max(mu * g, floorA)
        val dv = aStop * dt
        val rSpin = 0.28f * l
        val dw = max(mu * g * rSpin * invI * dt, 12f * dt)
        val linSnap = config.linearSleepEpsilon
        val angSnap = config.angularSleepEpsilon

        for (t in tiles) {
            if (!t.awake || t.kinematic) continue

            val sp = sqrt(t.speedSq())
            if (sp <= dv || sp <= linSnap) {
                t.vx = 0f
                t.vy = 0f
            } else {
                val s = (sp - dv) / sp
                t.vx *= s
                t.vy *= s
            }

            val w = t.omega
            val aw = abs(w)
            if (aw <= dw || aw <= angSnap) {
                t.omega = 0f
            } else {
                t.omega = w - dw * (if (w >= 0f) 1f else -1f)
            }
        }
    }

    private fun integratePositions(dt: Float) {
        for (t in tiles) {
            if (!t.awake || t.kinematic) continue
            t.x += t.vx * dt
            t.y += t.vy * dt
            t.theta += t.omega * dt
            if (t.theta > PI.toFloat() || t.theta < -PI.toFloat()) {
                t.theta = wrapAngle(t.theta)
            }
        }
    }

    private fun updateSleep(dt: Float) {
        val lin = config.linearSleepEpsilon
        val ang = config.angularSleepEpsilon
        val need = config.sleepTime
        for (t in tiles) {
            if (!t.awake || t.kinematic) continue
            val slow = t.speedSq() <= lin * lin && abs(t.omega) <= ang
            if (slow) {
                t.sleepTimer += dt
                if (t.sleepTimer >= need) {
                    t.vx = 0f
                    t.vy = 0f
                    t.omega = 0f
                    t.awake = false
                    t.sleepTimer = 0f
                }
            } else {
                t.sleepTimer = 0f
            }
        }
    }

    private fun invMassOf(t: ShuffleTileBody): Float =
        if (t.kinematic) 0f else config.invMass

    private fun invInertiaOf(t: ShuffleTileBody): Float =
        if (t.kinematic) 0f else config.invInertia

    // ── Collision detection ─────────────────────────────────────────────────

    private fun detectContacts() {
        recycleContacts()
        detectWallContacts()
        detectTileTileContacts()
    }

    private fun recycleContacts() {
        for (i in contacts.indices) {
            contactPool.add(contacts[i])
        }
        contacts.clear()
    }

    private fun obtainContact(): Contact {
        val last = contactPool.size - 1
        return if (last >= 0) {
            contactPool.removeAt(last)
        } else {
            Contact()
        }
    }

    private fun addContact(
        a: Int,
        b: Int,
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        penetration: Float,
    ) {
        val c = obtainContact()
        c.set(a, b, px, py, nx, ny, penetration)
        contacts.add(c)
    }

    private fun detectWallContacts() {
        val minX = config.tableMinX
        val maxX = config.tableMaxX
        val minY = config.tableMinY
        val maxY = config.tableMaxY
        val hx = config.halfWidth
        val hy = config.halfLength

        for (i in 0 until tileCount) {
            val t = tiles[i]
            // Sleeping tiles still need wall contacts if somehow outside, but
            // skip if deep asleep and clearly inside AABB margin.
            fillCorners(t, hx, hy)
            var cMinX = cornerX[0]
            var cMaxX = cornerX[0]
            var cMinY = cornerY[0]
            var cMaxY = cornerY[0]
            for (k in 1 until 4) {
                cMinX = min(cMinX, cornerX[k])
                cMaxX = max(cMaxX, cornerX[k])
                cMinY = min(cMinY, cornerY[k])
                cMaxY = max(cMaxY, cornerY[k])
            }

            if (cMinX < minX) {
                val depth = minX - cMinX
                // Contact near leftmost corner(s)
                val cy = clamp(t.y, minY, maxY)
                addWallContact(i, minX, cy, 1f, 0f, depth)
            }
            if (cMaxX > maxX) {
                val depth = cMaxX - maxX
                val cy = clamp(t.y, minY, maxY)
                addWallContact(i, maxX, cy, -1f, 0f, depth)
            }
            if (cMinY < minY) {
                val depth = minY - cMinY
                val cx = clamp(t.x, minX, maxX)
                addWallContact(i, cx, minY, 0f, 1f, depth)
            }
            if (cMaxY > maxY) {
                val depth = cMaxY - maxY
                val cx = clamp(t.x, minX, maxX)
                addWallContact(i, cx, maxY, 0f, -1f, depth)
            }
        }
    }

    private fun addWallContact(
        tileIndex: Int,
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        penetration: Float,
    ) {
        if (penetration <= 0f) return
        val t = tiles[tileIndex]
        t.awake = true
        addContact(tileIndex, -1, px, py, nx, ny, penetration)
    }

    private fun detectTileTileContacts() {
        buildBroadphase()
        pairSeen.clear()
        val cell = broadphaseCellSize()
        val invCell = 1f / cell
        val hx = config.halfWidth
        val hy = config.halfLength
        // Approximate radius for neighbour ring
        val reach = (max(config.tileLength, config.tileWidth) * 1.5f)
        val reachCells = max(1, ceilToInt(reach * invCell))

        for (i in 0 until tileCount) {
            val a = tiles[i]
            val cx = floor(a.x * invCell).toInt()
            val cy = floor(a.y * invCell).toInt()
            for (oy in -reachCells..reachCells) {
                for (ox in -reachCells..reachCells) {
                    val bucket = grid[packCell(cx + ox, cy + oy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        val key = packPair(i, j)
                        if (!pairSeen.add(key)) continue
                        val b = tiles[j]
                        if (!a.awake && !b.awake) continue
                        // Same palm: welded cluster — no internal contacts.
                        if (a.kinematic && b.kinematic && a.grippedBy == b.grippedBy && a.grippedBy >= 0) {
                            continue
                        }
                        // Cheap circle reject
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val maxR = config.tileLength + config.tileWidth
                        if (dx * dx + dy * dy > maxR * maxR) continue
                        satObb(i, j, hx, hy)
                    }
                }
            }
        }
    }

    private fun broadphaseCellSize(): Float =
        max(config.tileLength, config.tileWidth) * config.broadphaseCellScale

    private fun buildBroadphase() {
        clearGrid()
        val invCell = 1f / broadphaseCellSize()
        for (i in 0 until tileCount) {
            val t = tiles[i]
            val cx = floor(t.x * invCell).toInt()
            val cy = floor(t.y * invCell).toInt()
            val key = packCell(cx, cy)
            val list = grid.getOrPut(key) { obtainGridList() }
            list.add(i)
        }
    }

    private fun clearGrid() {
        for (list in grid.values) {
            list.clear()
            gridListPool.add(list)
        }
        grid.clear()
    }

    private fun obtainGridList(): ArrayList<Int> {
        val last = gridListPool.size - 1
        return if (last >= 0) {
            gridListPool.removeAt(last)
        } else {
            ArrayList(4)
        }
    }

    /**
     * OBB–OBB via SAT. On overlap, adds one contact with min-penetration normal.
     */
    private fun satObb(ia: Int, ib: Int, hx: Float, hy: Float) {
        val a = tiles[ia]
        val b = tiles[ib]

        val cA = cos(a.theta)
        val sA = sin(a.theta)
        val cB = cos(b.theta)
        val sB = sin(b.theta)

        // Local axes (unit)
        // A: ax=(cA,sA) along width, ay=(-sA,cA) along length
        val axX = cA
        val ayX = -sA
        val byX = -sB

        val dx = b.x - a.x
        val dy = b.y - a.y

        var minPen = Float.MAX_VALUE
        var nX = 0f
        var nY = 0f

        // Project half-extents of both boxes onto axis (nx,ny)
        fun testAxis(tx: Float, ty: Float): Boolean {
            val len = sqrt(tx * tx + ty * ty)
            if (len < 1e-8f) return true
            val nx = tx / len
            val ny = ty / len
            val ra = abs(nx * axX + ny * sA) * hx + abs(nx * ayX + ny * cA) * hy
            val rb = abs(nx * cB + ny * sB) * hx + abs(nx * byX + ny * cB) * hy
            val dist = abs(dx * nx + dy * ny)
            val pen = ra + rb - dist
            if (pen < 0f) return false
            if (pen < minPen) {
                minPen = pen
                // Orient normal A → B
                if (dx * nx + dy * ny < 0f) {
                    nX = -nx
                    nY = -ny
                } else {
                    nX = nx
                    nY = ny
                }
            }
            return true
        }

        if (!testAxis(axX, sA)) return
        if (!testAxis(ayX, cA)) return
        if (!testAxis(cB, sB)) return
        if (!testAxis(byX, cB)) return
        if (minPen == Float.MAX_VALUE || minPen <= 0f) return

        // Contact point: midpoint between support points along normal
        supportPointInto(a, hx, hy, nX, nY, supportScratch)
        val sax = supportScratch[0]
        val say = supportScratch[1]
        supportPointInto(b, hx, hy, -nX, -nY, supportScratch)
        val px = (sax + supportScratch[0]) * 0.5f
        val py = (say + supportScratch[1]) * 0.5f

        a.awake = true
        b.awake = true
        addContact(ia, ib, px, py, nX, nY, minPen)
    }

    /** Support point of tile OBB in world direction (dx,dy) — not necessarily unit. */
    private fun supportPointInto(
        t: ShuffleTileBody,
        hx: Float,
        hy: Float,
        dx: Float,
        dy: Float,
        out: FloatArray,
    ) {
        val c = cos(t.theta)
        val s = sin(t.theta)
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        val sx = if (lx >= 0f) hx else -hx
        val sy = if (ly >= 0f) hy else -hy
        out[0] = t.x + sx * c - sy * s
        out[1] = t.y + sx * s + sy * c
    }

    private fun fillCorners(t: ShuffleTileBody, hx: Float, hy: Float) {
        val c = cos(t.theta)
        val s = sin(t.theta)
        // corners of (±hx, ±hy) in local — sign tables avoid per-call allocs
        for (k in 0 until 4) {
            val sx = cornerSignX[k] * hx
            val sy = cornerSignY[k] * hy
            cornerX[k] = t.x + sx * c - sy * s
            cornerY[k] = t.y + sx * s + sy * c
        }
    }

    // ── Solver ──────────────────────────────────────────────────────────────

    private fun solveContacts(dt: Float) {
        if (contacts.isEmpty()) return
        val eFree = config.restitution
        val mu = config.contactMu
        val baum = config.baumgarte
        val slop = config.slop
        val invDt = if (dt > 0f) 1f / dt else 0f
        val l = max(config.tileLength, config.tileWidth)
        // Cap kinematic→free normal impulse so free tiles do not instantly match
        // palm speed (infinite-mass grip otherwise flings them like chips).
        val maxKineJn = config.mass * l * config.kinematicMaxDeltaVScale

        // Precompute r vectors and effective masses (kinematic → infinite mass).
        for (c in contacts) {
            val a = tiles[c.a]
            val invMA = invMassOf(a)
            val invIA = invInertiaOf(a)
            c.rax = c.px - a.x
            c.ray = c.py - a.y
            val invMB: Float
            val invIB: Float
            val bKinematic: Boolean
            if (c.b >= 0) {
                val b = tiles[c.b]
                c.rbx = c.px - b.x
                c.rby = c.py - b.y
                invMB = invMassOf(b)
                invIB = invInertiaOf(b)
                bKinematic = b.kinematic
            } else {
                c.rbx = 0f
                c.rby = 0f
                invMB = 0f
                invIB = 0f
                bKinematic = true // wall = immovable
            }

            val rnA = c.rax * c.ny - c.ray * c.nx
            val rnB = c.rbx * c.ny - c.rby * c.nx
            var kN = invMA + rnA * rnA * invIA
            if (c.b >= 0) kN += invMB + rnB * rnB * invIB
            c.massN = if (kN > 0f) 1f / kN else 0f

            val tx = -c.ny
            val ty = c.nx
            val rtA = c.rax * ty - c.ray * tx
            val rtB = c.rbx * ty - c.rby * tx
            var kT = invMA + rtA * rtA * invIA
            if (c.b >= 0) kT += invMB + rtB * rtB * invIB
            c.massT = if (kT > 0f) 1f / kT else 0f

            val pen = max(0f, c.penetration - slop)
            // Softer bias when a grip is driving the contact — full baumgarte
            // injects large Δv against infinite mass and looks bouncy/light.
            val baumScale = if (a.kinematic || bKinematic) 0.45f else 1f
            c.bias = baum * baumScale * invDt * pen
        }

        val iters = config.solverIterations
        (0 until iters).forEach { _ ->
            for (c in contacts) {
                val a = tiles[c.a]
                val b = if (c.b >= 0) tiles[c.b] else null
                val invMA = invMassOf(a)
                val invIA = invInertiaOf(a)
                val invMB = if (b != null) invMassOf(b) else 0f
                val invIB = if (b != null) invInertiaOf(b) else 0f

                // Wall contacts against pure kinematic tiles: skip impulses
                // (grip snap re-asserts pose; free tiles still get wall hits).
                if (b == null && a.kinematic) continue
                // Same-hand welded pair should not appear as a contact; if two
                // kinematics remain, massN is 0 — skip (inter-hand clashes are
                // released before solve).
                if (b != null && a.kinematic && b.kinematic) continue

                val kineFree = b != null && (a.kinematic xor b.kinematic)
                // No bounce off the hand — heavy plastic/bone on felt.
                val e = if (kineFree) 0f else eFree

                val vAx = a.vx - a.omega * c.ray
                val vAy = a.vy + a.omega * c.rax
                val rvx: Float
                val rvy: Float
                if (b != null) {
                    val vBx = b.vx - b.omega * c.rby
                    val vBy = b.vy + b.omega * c.rbx
                    rvx = vBx - vAx
                    rvy = vBy - vAy
                } else {
                    // Wall: relative velocity of tile vs immovable rim.
                    rvx = vAx
                    rvy = vAy
                }

                val vn = rvx * c.nx + rvy * c.ny
                var jn = c.massN * (-(1f + e) * vn + c.bias)
                val jn0 = c.jnAcc
                var jnAcc = max(0f, jn0 + jn)
                // Soft grip push: limit total normal impulse per contact / substep.
                if (kineFree && maxKineJn > 0f) {
                    jnAcc = min(jnAcc, maxKineJn)
                }
                c.jnAcc = jnAcc
                jn = c.jnAcc - jn0

                val jx = jn * c.nx
                val jy = jn * c.ny
                if (b != null) {
                    a.applyImpulse(-jx, -jy, c.rax, c.ray, invMA, invIA)
                    b.applyImpulse(jx, jy, c.rbx, c.rby, invMB, invIB)
                } else {
                    a.applyImpulse(jx, jy, c.rax, c.ray, invMA, invIA)
                }

                val vAx2 = a.vx - a.omega * c.ray
                val vAy2 = a.vy + a.omega * c.rax
                val rvx2: Float
                val rvy2: Float
                if (b != null) {
                    val vBx2 = b.vx - b.omega * c.rby
                    val vBy2 = b.vy + b.omega * c.rbx
                    rvx2 = vBx2 - vAx2
                    rvy2 = vBy2 - vAy2
                } else {
                    rvx2 = vAx2
                    rvy2 = vAy2
                }
                val tx = -c.ny
                val ty = c.nx
                val vt = rvx2 * tx + rvy2 * ty
                var jt = c.massT * -vt
                val maxF = mu * c.jnAcc
                val jt0 = c.jtAcc
                c.jtAcc = clamp(jt0 + jt, -maxF, maxF)
                jt = c.jtAcc - jt0

                val fx = jt * tx
                val fy = jt * ty
                if (b != null) {
                    a.applyImpulse(-fx, -fy, c.rax, c.ray, invMA, invIA)
                    b.applyImpulse(fx, fy, c.rbx, c.rby, invMB, invIB)
                } else {
                    a.applyImpulse(fx, fy, c.rax, c.ray, invMA, invIA)
                }
            }
        }
    }

    /**
     * Hard cap free-tile speeds so stacked contacts / bias cannot slingshot
     * tiles across the table. Gripped tiles keep palm velocity.
     */
    private fun clampFreeTileSpeeds() {
        val maxSp = max(config.tileLength, config.tileWidth) * config.freeMaxSpeedScale
        if (maxSp <= 0f) return
        val maxSp2 = maxSp * maxSp
        for (t in tiles) {
            if (t.kinematic || !t.awake) continue
            val sp2 = t.speedSq()
            if (sp2 <= maxSp2) continue
            val s = maxSp / sqrt(sp2)
            t.vx *= s
            t.vy *= s
        }
    }

    private fun positionalCorrection() {
        val percent = config.positionCorrection
        val slop = config.slop
        if (percent <= 0f || contacts.isEmpty()) return
        applyPositionalSeparation(percent, slop)
    }

    /**
     * Unified geometric resolve: detect → free-body separate → hard table
     * bounds, repeated up to [ShuffleSolverSchedule.hardSeparationIterations]
     * with early-out when residual penetration is under the material slop.
     *
     * Bounds run **inside** the loop so rim clamps that re-introduce free–free
     * / free–grip overlap are cleaned by the next pass — no second full
     * separation phase (the previous double-call schedule).
     *
     * Gripped poses stay fixed during separation; only free tiles move.
     * Soft velocity response is unchanged — this is visual / geometric rigidity.
     */
    private fun resolveGeometry() {
        val iters = config.positionSeparationIterations
        val eps = config.positionSeparationSlop.coerceAtLeast(0f)
        if (iters <= 0) {
            enforceHardTableBounds()
            return
        }
        (0 until iters).forEach { _ ->
            detectContacts()
            val maxPen = if (contacts.isEmpty()) {
                0f
            } else {
                applyPositionalSeparation(percent = 1f, slop = eps)
            }
            // Hard borders: kinematic grips skip wall impulses, so clamp everyone
            // (free + gripped clusters as rigid groups) so nothing leaves the square.
            enforceHardTableBounds()
            if (maxPen <= eps) return
        }
    }

    /**
     * Apply contact positional separation. Returns max raw penetration seen.
     * [percent] 1 = full free-body de-overlap for this contact list snapshot.
     */
    private fun applyPositionalSeparation(percent: Float, slop: Float): Float {
        if (percent <= 0f || contacts.isEmpty()) return 0f
        var maxPen = 0f
        for (c in contacts) {
            if (c.penetration > maxPen) maxPen = c.penetration
            val pen = c.penetration - slop
            if (pen <= 0f) continue
            val corr = pen * percent
            if (c.b < 0) {
                val a = tiles[c.a]
                if (a.kinematic) continue
                a.x += c.nx * corr
                a.y += c.ny * corr
                a.awake = true
            } else {
                val a = tiles[c.a]
                val b = tiles[c.b]
                val aK = a.kinematic
                val bK = b.kinematic
                when {
                    aK && bK -> Unit
                    aK -> {
                        b.x += c.nx * corr
                        b.y += c.ny * corr
                        b.awake = true
                    }
                    bK -> {
                        a.x -= c.nx * corr
                        a.y -= c.ny * corr
                        a.awake = true
                    }
                    else -> {
                        val half = corr * 0.5f
                        a.x -= c.nx * half
                        a.y -= c.ny * half
                        b.x += c.nx * half
                        b.y += c.ny * half
                        a.awake = true
                        b.awake = true
                    }
                }
            }
        }
        return maxPen
    }

    /**
     * Largest OBB–OBB / free–wall penetration depth currently present
     * (after a fresh contact detect). Useful for tests and diagnostics.
     */
    fun maxPenetration(): Float {
        detectContacts()
        var m = 0f
        for (c in contacts) {
            if (c.penetration > m) m = c.penetration
        }
        return m
    }

    private fun clampAllInsideTable() {
        // Setup helper: axis-aligned margin (tiles start unrotated).
        val minX = config.tableMinX + config.halfWidth
        val maxX = config.tableMaxX - config.halfWidth
        val minY = config.tableMinY + config.halfLength
        val maxY = config.tableMaxY - config.halfLength
        for (t in tiles) {
            t.x = clamp(t.x, minX, maxX)
            t.y = clamp(t.y, minY, maxY)
        }
    }

    /**
     * Keep every tile's OBB fully inside the table square.
     *
     * Gripped tiles are kinematic and intentionally ignore wall impulses (the
     * palm is authoritative). Without this, a stroke can drag a cluster past
     * the rim. Free tiles can also leak under large multi-body piles.
     */
    private fun enforceHardTableBounds() {
        val minX = config.tableMinX
        val maxX = config.tableMaxX
        val minY = config.tableMinY
        val maxY = config.tableMaxY
        val hx = config.halfWidth
        val hy = config.halfLength

        // ── Gripped clusters: one rigid translation for palm + all grips ──
        for (hi in hands.indices) {
            val hand = hands[hi]
            if (!hand.hasGrip) continue
            var pushRight = 0f
            var pushLeft = 0f
            var pushUp = 0f
            var pushDown = 0f
            for (s in 0 until hand.gripCount) {
                val ti = hand.gripTiles[s]
                if (ti !in 0 until tileCount) continue
                val t = tiles[ti]
                fillCorners(t, hx, hy)
                var cMinX = cornerX[0]
                var cMaxX = cornerX[0]
                var cMinY = cornerY[0]
                var cMaxY = cornerY[0]
                for (k in 1 until 4) {
                    cMinX = min(cMinX, cornerX[k])
                    cMaxX = max(cMaxX, cornerX[k])
                    cMinY = min(cMinY, cornerY[k])
                    cMaxY = max(cMaxY, cornerY[k])
                }
                if (cMinX < minX) pushRight = max(pushRight, minX - cMinX)
                if (cMaxX > maxX) pushLeft = max(pushLeft, cMaxX - maxX)
                if (cMinY < minY) pushUp = max(pushUp, minY - cMinY)
                if (cMaxY > maxY) pushDown = max(pushDown, cMaxY - maxY)
            }
            val dx = pushRight - pushLeft
            val dy = pushUp - pushDown
            if (dx != 0f || dy != 0f) {
                hand.x += dx
                hand.y += dy
                for (s in 0 until hand.gripCount) {
                    val ti = hand.gripTiles[s]
                    if (ti !in 0 until tileCount) continue
                    val t = tiles[ti]
                    t.x += dx
                    t.y += dy
                }
            }
            // Kill palm velocity into a wall we are already pressed against.
            if (pushRight > 0f && hand.vx < 0f) hand.vx = 0f
            if (pushLeft > 0f && hand.vx > 0f) hand.vx = 0f
            if (pushUp > 0f && hand.vy < 0f) hand.vy = 0f
            if (pushDown > 0f && hand.vy > 0f) hand.vy = 0f
            for (s in 0 until hand.gripCount) {
                val ti = hand.gripTiles[s]
                if (ti !in 0 until tileCount) continue
                val t = tiles[ti]
                t.vx = hand.vx
                t.vy = hand.vy
                t.omega = 0f
            }
        }

        // ── Free tiles: per-body OBB clamp + kill outward velocity ──
        for (i in 0 until tileCount) {
            val t = tiles[i]
            if (t.kinematic) continue
            fillCorners(t, hx, hy)
            var cMinX = cornerX[0]
            var cMaxX = cornerX[0]
            var cMinY = cornerY[0]
            var cMaxY = cornerY[0]
            for (k in 1 until 4) {
                cMinX = min(cMinX, cornerX[k])
                cMaxX = max(cMaxX, cornerX[k])
                cMinY = min(cMinY, cornerY[k])
                cMaxY = max(cMaxY, cornerY[k])
            }
            var dx = 0f
            var dy = 0f
            var hitL = false
            var hitR = false
            var hitB = false
            var hitT = false
            if (cMinX < minX) {
                dx = minX - cMinX
                hitL = true
            }
            if (cMaxX + dx > maxX) {
                dx = maxX - cMaxX
                hitR = true
            }
            if (cMinY < minY) {
                dy = minY - cMinY
                hitB = true
            }
            if (cMaxY + dy > maxY) {
                dy = maxY - cMaxY
                hitT = true
            }
            if (dx != 0f || dy != 0f) {
                t.x += dx
                t.y += dy
                t.awake = true
                t.sleepTimer = 0f
            }
            if (hitL && t.vx < 0f) t.vx = 0f
            if (hitR && t.vx > 0f) t.vx = 0f
            if (hitB && t.vy < 0f) t.vy = 0f
            if (hitT && t.vy > 0f) t.vy = 0f
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun wrapAngle(a: Float): Float {
        var x = a
        val twoPi = (2.0 * PI).toFloat()
        while (x > PI.toFloat()) x -= twoPi
        while (x < -PI.toFloat()) x += twoPi
        return x
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (v < lo) lo else if (v > hi) hi else v

    private fun ceilToInt(v: Float): Int {
        val i = v.toInt()
        return if (v > i) i + 1 else i
    }

    private fun packCell(cx: Int, cy: Int): Long =
        (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private fun packPair(i: Int, j: Int): Long {
        val a = if (i < j) i else j
        val b = if (i < j) j else i
        return (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
    }

    /** Mutable contact record; instances live in [contactPool] / [contacts]. */
    private class Contact {
        var a: Int = 0
        var b: Int = -1 // -1 = wall
        var px: Float = 0f
        var py: Float = 0f
        var nx: Float = 0f
        var ny: Float = 0f
        var penetration: Float = 0f
        var rax = 0f
        var ray = 0f
        var rbx = 0f
        var rby = 0f
        var massN = 0f
        var massT = 0f
        var bias = 0f
        var jnAcc = 0f
        var jtAcc = 0f

        fun set(
            a: Int,
            b: Int,
            px: Float,
            py: Float,
            nx: Float,
            ny: Float,
            penetration: Float,
        ) {
            this.a = a
            this.b = b
            this.px = px
            this.py = py
            this.nx = nx
            this.ny = ny
            this.penetration = penetration
            rax = 0f
            ray = 0f
            rbx = 0f
            rby = 0f
            massN = 0f
            massT = 0f
            bias = 0f
            jnAcc = 0f
            jtAcc = 0f
        }
    }
}

