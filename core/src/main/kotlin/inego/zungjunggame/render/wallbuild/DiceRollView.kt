package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.tiles.TileEasing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two d6 as fake-3D cubes under a single continuous motion:
 * airborne tumble → energy decay → soft face-up alignment → rest on the table.
 *
 * Camera is not a second timed “sit” animation; it tracks the same air/energy
 * as the dice, so the move into true top-down is continuous with the tumble.
 * Procedural [whitePixel] quads only.
 */
class DiceRollView {

    private enum class Phase {
        Hidden,
        Active,
    }

    private data class Die(
        val faces: IntArray = IntArray(6) { 1 },
        var finalFace: Int = 1,
        var rx: Float = 0f,
        var ry: Float = 0f,
        var rz: Float = 0f,
        var wx: Float = 0f,
        var wy: Float = 0f,
        var wz: Float = 0f,
        /** Extra world-Z above rest (rest center is +0.5 so bottom sits on table). */
        var height: Float = 0f,
        var jx: Float = 0f,
        var jy: Float = 0f,
        /** Once true, top face has been painted with [finalFace]. */
        var faceLocked: Boolean = false,
        /** Fully frozen on the table. */
        var atRest: Boolean = false,
    )

    private var phase = Phase.Hidden
    private var elapsed = 0f
    private var viewYaw = 0f
    private var viewPitch = 0f
    private var rng = Random(1L)
    private val dice = arrayOf(Die(), Die())

    private val projX = FloatArray(4)
    private val projY = FloatArray(4)
    private val edgeX = FloatArray(4)
    private val edgeY = FloatArray(4)
    private val quadVerts = FloatArray(20)
    private val faceOrder = IntArray(6)
    private val faceDepth = FloatArray(6)
    private val rotOut = FloatArray(3)
    private val tmpN = FloatArray(3)
    private val tmpP = FloatArray(3)

    val visible: Boolean
        get() = phase != Phase.Hidden

    /** True once both dice have finished the tumble and sit face-up. */
    val allAtRest: Boolean
        get() = phase == Phase.Active && dice[0].atRest && dice[1].atRest

    /** Left die final face (1..6). Valid after [startRoll]. */
    val faceA: Int
        get() = dice[0].finalFace

    /** Right die final face (1..6). Valid after [startRoll]. */
    val faceB: Int
        get() = dice[1].finalFace

    /** Sum of the two final faces (2..12). Valid after [startRoll]. */
    val faceSum: Int
        get() = dice[0].finalFace + dice[1].finalFace

    fun reset() {
        phase = Phase.Hidden
        elapsed = 0f
        viewYaw = 0f
        viewPitch = 0f
        for (d in dice) {
            d.finalFace = 1
            d.rx = 0f
            d.ry = 0f
            d.rz = 0f
            d.wx = 0f
            d.wy = 0f
            d.wz = 0f
            d.height = 0f
            d.jx = 0f
            d.jy = 0f
            d.faceLocked = false
            d.atRest = false
            for (i in d.faces.indices) d.faces[i] = 1
        }
    }

    fun startRoll(seed: Long = System.nanoTime()) {
        rng = Random(seed)
        val faceA = rng.nextInt(1, 7)
        val faceB = rng.nextInt(1, 7)
        beginTumble(faceA, faceB)
    }

    private fun beginTumble(faceA: Int, faceB: Int) {
        elapsed = 0f
        phase = Phase.Active
        viewYaw = TUMBLE_YAW
        viewPitch = TUMBLE_PITCH
        val faces = intArrayOf(faceA, faceB)
        for (i in dice.indices) {
            val d = dice[i]
            d.finalFace = faces[i]
            // Fixed paint for the whole tumble; lock step only ensures the up
            // face shows the rolled value (may reassign which physical face).
            paintTopFace(d, faceIndex = 0, value = d.finalFace, rng)
            d.rx = rng.nextFloat() * 360f
            d.ry = rng.nextFloat() * 360f
            d.rz = rng.nextFloat() * 360f
            d.wx = (640f + rng.nextFloat() * 520f) * if (rng.nextBoolean()) 1f else -1f
            d.wy = (580f + rng.nextFloat() * 560f) * if (rng.nextBoolean()) 1f else -1f
            d.wz = (420f + rng.nextFloat() * 480f) * if (rng.nextBoolean()) 1f else -1f
            d.height = 0.70f + rng.nextFloat() * 0.35f
            d.jx = 0f
            d.jy = 0f
            d.faceLocked = false
            d.atRest = false
        }
    }

    /**
     * Instantly land both dice face-up showing their rolled values (top-down).
     * Used when jumping straight to a post-roll beat without replaying the tumble.
     */
    fun snapToRest() {
        if (phase == Phase.Hidden) return
        elapsed = TUMBLE_SPAN * 2f
        viewYaw = REST_YAW
        viewPitch = REST_PITCH
        for (d in dice) {
            d.rx = 0f
            d.ry = 0f
            d.rz = 0f
            d.wx = 0f
            d.wy = 0f
            d.wz = 0f
            d.height = 0f
            d.jx = 0f
            d.jy = 0f
            paintTopFace(d, faceIndex = 0, value = d.finalFace, rng)
            d.faceLocked = true
            d.atRest = true
        }
    }

    /**
     * Show both dice at rest with fixed faces (no tumble). Ensures caption,
     * count highlight, and drawn pips all agree after Back / re-entry.
     */
    fun showSettled(faceA: Int, faceB: Int) {
        rng = Random(faceA * 31 + faceB)
        phase = Phase.Active
        dice[0].finalFace = faceA.coerceIn(1, 6)
        dice[1].finalFace = faceB.coerceIn(1, 6)
        snapToRest()
    }

    fun update(dtSec: Float) {
        if (dtSec <= 0f || phase != Phase.Active) return
        elapsed += dtSec
        val dt = dtSec.coerceAtMost(0.05f)

        var maxAir = 0f
        for (d in dice) {
            if (!d.atRest) {
                stepDie(d, dt)
            }
            maxAir = maxOf(maxAir, airAmount(d))
        }

        // Camera tracks dice energy/height — same continuous signal as motion.
        // No separate settle timer or second choreography.
        val cam = smooth01(maxAir)
        viewYaw = TileEasing.lerp(REST_YAW, TUMBLE_YAW, cam)
        viewPitch = TileEasing.lerp(REST_PITCH, TUMBLE_PITCH, cam)
    }

    /**
     * Continuous rigid-ish step: free spin early, then decay + soft pull onto
     * the nearest face-up as bounce dies. No phase-switch lerp.
     */
    private fun stepDie(d: Die, dt: Float) {
        val t = (elapsed / TUMBLE_SPAN).coerceIn(0f, 1.25f)

        // Bounce envelope from the master clock (shared table rhythm) with a
        // per-die phase so the pair is not identical.
        val targetHeight = bounceHeight(elapsed, d.finalFace)
        // Soft follow so height is continuous even as the envelope drops.
        d.height = TileEasing.lerp(d.height, targetHeight, 1f - exp(-dt * 14f))

        // Spin damping ramps up over the tumble (losing energy).
        val damp = TileEasing.lerp(0.15f, 6.5f, smooth01(t))
        d.wx *= exp(-damp * dt)
        d.wy *= exp(-damp * dt)
        d.wz *= exp(-damp * dt)

        // Alignment strength grows as we run out of air/spin — continuous torque
        // into the nearest face-up, not a post-hoc reorient.
        val spin = abs(d.wx) + abs(d.wy) + abs(d.wz)
        // 0 early → 1 late; also require low air / spin so we don't pin mid-flight.
        val alignGate = smooth01(t) *
            (1f - smooth01(d.height * 2.2f)) *
            smooth01(1f - spin / 900f)
        if (alignGate > 0.02f) {
            val tx = snap90(d.rx)
            val ty = snap90(d.ry)
            // Angle error on shortest path (deg).
            val ex = normalizeNear(d.rx, tx) - tx
            val ey = normalizeNear(d.ry, ty) - ty
            // Spring + damper into the snap (feels like last table contacts).
            val k = 420f * alignGate
            val c = 18f * alignGate
            d.wx += (-k * ex - c * d.wx) * dt
            d.wy += (-k * ey - c * d.wy) * dt
            // Mildly kill in-plane spin as we stick the landing.
            d.wz *= exp(-3.5f * alignGate * dt)
        }

        d.rx = wrap360(d.rx + d.wx * dt)
        d.ry = wrap360(d.ry + d.wy * dt)
        d.rz = wrap360(d.rz + d.wz * dt)

        // Tiny table jitter only while still lively.
        val lively = smooth01(spin / 400f + d.height)
        d.jx = (rng.nextFloat() - 0.5f) * JITTER_PX * lively
        d.jy = (rng.nextFloat() - 0.5f) * JITTER_PX * lively

        // Lock face value once we're clearly committed to an up face.
        if (!d.faceLocked && alignGate > 0.45f && d.height < 0.12f) {
            val up = faceIndexPointingUp(d.rx, d.ry, d.rz)
            paintTopFace(d, faceIndex = up, value = d.finalFace, rng)
            d.faceLocked = true
        }

        // Freeze when fully spent and aligned.
        val toX = snap90(d.rx)
        val toY = snap90(d.ry)
        val err = abs(angleDiff(d.rx, toX)) + abs(angleDiff(d.ry, toY))
        if (d.height < 0.02f && spin < 12f && err < 2.5f && t > 0.55f) {
            d.rx = toX
            d.ry = toY
            d.wx = 0f
            d.wy = 0f
            d.wz = 0f
            d.height = 0f
            d.jx = 0f
            d.jy = 0f
            if (!d.faceLocked) {
                val up = faceIndexPointingUp(d.rx, d.ry, d.rz)
                paintTopFace(d, faceIndex = up, value = d.finalFace, rng)
                d.faceLocked = true
            }
            d.atRest = true
        }
    }

    /** 1 = fully airborne / energetic, 0 = flat on table. */
    private fun airAmount(d: Die): Float {
        if (d.atRest) return 0f
        val spin = (abs(d.wx) + abs(d.wy) + abs(d.wz)) / 1400f
        val h = d.height / 0.85f
        return smooth01(maxOf(h, spin * 0.85f))
    }

    fun draw(
        batch: SpriteBatch,
        whitePixel: Texture,
        cx: Float,
        cy: Float,
        dieSize: Float,
        alpha: Float,
    ) {
        if (phase == Phase.Hidden || alpha < 0.01f || dieSize < 4f) return
        val gap = dieSize * 0.55f
        val leftCx = cx - (dieSize + gap) * 0.5f
        val rightCx = cx + (dieSize + gap) * 0.5f
        val scale = dieSize * 0.78f
        drawDie(batch, whitePixel, dice[0], leftCx, cy, scale, alpha)
        drawDie(batch, whitePixel, dice[1], rightCx, cy, scale, alpha)
        batch.setColor(Color.WHITE)
    }

    private fun drawDie(
        batch: SpriteBatch,
        px: Texture,
        die: Die,
        cx: Float,
        cy: Float,
        scale: Float,
        alpha: Float,
    ) {
        val ox = cx + die.jx
        val oy = cy + die.jy
        val worldZ = 0.5f + die.height
        val air = airAmount(die)

        transformPoint(0f, 0f, 0f, 0f, 0f, 0f, zLift = 0f, out = rotOut)
        val shadowCx = ox + rotOut[0] * scale * 0.1f
        val shadowCy = oy + rotOut[1] * scale * 0.1f
        val shadowAlpha = (0.30f * alpha * (1f - die.height * 0.45f)).coerceIn(0.08f, 0.36f)
        val grow = 1f + die.height * 0.35f
        val sw = scale * TileEasing.lerp(1.15f, 1.05f, 1f - air) * grow
        val sh = scale * TileEasing.lerp(1.05f, 0.70f, 1f - air) * grow
        batch.setColor(AppPalette.tint(AppPalette.shadow, shadowAlpha))
        batch.draw(px, shadowCx - sw * 0.5f, shadowCy - sh * 0.5f, sw, sh)
        batch.setColor(Color.WHITE)

        for (fi in 0 until 6) {
            copy3(FACE_N[fi], tmpN)
            transformPoint(tmpN[0], tmpN[1], tmpN[2], die.rx, die.ry, die.rz, zLift = 0f, out = rotOut)
            faceDepth[fi] = rotOut[2]
            faceOrder[fi] = fi
        }
        for (i in 0 until 5) {
            for (j in i + 1 until 6) {
                if (faceDepth[faceOrder[j]] < faceDepth[faceOrder[i]]) {
                    val tmp = faceOrder[i]
                    faceOrder[i] = faceOrder[j]
                    faceOrder[j] = tmp
                }
            }
        }

        for (k in 0 until 6) {
            val fi = faceOrder[k]
            copy3(FACE_N[fi], tmpN)
            transformPoint(tmpN[0], tmpN[1], tmpN[2], die.rx, die.ry, die.rz, zLift = 0f, out = rotOut)
            val nz = rotOut[2]
            if (nz <= 0.04f) continue

            val corners = FACE_CORNERS[fi]
            for (c in 0 until 4) {
                val p = corners[c]
                transformPoint(p[0], p[1], p[2], die.rx, die.ry, die.rz, zLift = worldZ, out = rotOut)
                projX[c] = ox + rotOut[0] * scale
                projY[c] = oy + rotOut[1] * scale
            }

            val shade = (0.55f + 0.45f * nz).coerceIn(0.48f, 1f)
            val up = faceIndexPointingUp(die.rx, die.ry, die.rz)
            val warm = if (fi == up) 1.03f else 0.94f
            val r = (BODY_R * shade * warm).coerceIn(0f, 1f)
            val g = (BODY_G * shade * warm).coerceIn(0f, 1f)
            val b = (BODY_B * shade).coerceIn(0f, 1f)
            drawQuad(batch, px, projX, projY, Color.toFloatBits(r, g, b, alpha))

            if (air > 0.08f) {
                val edgeA = 0.45f * alpha * air
                drawFaceEdges(
                    batch, px, projX, projY,
                    Color.toFloatBits(AppPalette.diceEdge.r, AppPalette.diceEdge.g, AppPalette.diceEdge.b, edgeA),
                    scale * 0.028f,
                )
            }
            drawFacePips(batch, px, fi, die.faces[fi], die.rx, die.ry, die.rz, ox, oy, worldZ, scale, alpha)
        }
    }

    private fun drawFacePips(
        batch: SpriteBatch,
        px: Texture,
        faceIdx: Int,
        faceVal: Int,
        rx: Float,
        ry: Float,
        rz: Float,
        ox: Float,
        oy: Float,
        worldZ: Float,
        scale: Float,
        alpha: Float,
    ) {
        val o = 0.28f
        val pipR = 0.085f
        val positions = pipLayout(faceVal, o) ?: return
        val center = FACE_CENTERS[faceIdx]
        val uAxis = FACE_U[faceIdx]
        val vAxis = FACE_V[faceIdx]
        val n = FACE_N[faceIdx]
        val inset = 0.02f
        val pipBits = Color.toFloatBits(AppPalette.dicePip.r, AppPalette.dicePip.g, AppPalette.dicePip.b, alpha)
        val du = floatArrayOf(-pipR, pipR, pipR, -pipR)
        val dv = floatArrayOf(-pipR, -pipR, pipR, pipR)

        var i = 0
        while (i < positions.size) {
            val pu = positions[i]
            val pv = positions[i + 1]
            i += 2
            for (c in 0 until 4) {
                val lx = center[0] + (pu + du[c]) * uAxis[0] + (pv + dv[c]) * vAxis[0] - n[0] * inset
                val ly = center[1] + (pu + du[c]) * uAxis[1] + (pv + dv[c]) * vAxis[1] - n[1] * inset
                val lz = center[2] + (pu + du[c]) * uAxis[2] + (pv + dv[c]) * vAxis[2] - n[2] * inset
                transformPoint(lx, ly, lz, rx, ry, rz, zLift = worldZ, out = rotOut)
                projX[c] = ox + rotOut[0] * scale
                projY[c] = oy + rotOut[1] * scale
            }
            drawQuad(batch, px, projX, projY, pipBits)
        }
    }

    private fun drawFaceEdges(
        batch: SpriteBatch,
        px: Texture,
        xs: FloatArray,
        ys: FloatArray,
        bits: Float,
        thickness: Float,
    ) {
        for (e in 0 until 4) {
            val i1 = (e + 1) % 4
            val x0 = xs[e]
            val y0 = ys[e]
            val x1 = xs[i1]
            val y1 = ys[i1]
            val dx = x1 - x0
            val dy = y1 - y0
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val nx = -dy / len * thickness
            val ny = dx / len * thickness
            edgeX[0] = x0 - nx
            edgeY[0] = y0 - ny
            edgeX[1] = x0 + nx
            edgeY[1] = y0 + ny
            edgeX[2] = x1 + nx
            edgeY[2] = y1 + ny
            edgeX[3] = x1 - nx
            edgeY[3] = y1 - ny
            drawQuad(batch, px, edgeX, edgeY, bits)
        }
    }

    private fun drawQuad(
        batch: SpriteBatch,
        px: Texture,
        xs: FloatArray,
        ys: FloatArray,
        colorBits: Float,
    ) {
        val u = 0.5f
        val v = 0.5f
        var i = 0
        for (c in 0 until 4) {
            quadVerts[i++] = xs[c]
            quadVerts[i++] = ys[c]
            quadVerts[i++] = colorBits
            quadVerts[i++] = u
            quadVerts[i++] = v
        }
        batch.draw(px, quadVerts, 0, 20)
    }

    private fun transformPoint(
        x: Float,
        y: Float,
        z: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        zLift: Float,
        out: FloatArray,
    ) {
        var px = x
        var py = y
        var pz = z
        var c = cosd(rx)
        var s = sind(rx)
        var y1 = py * c - pz * s
        var z1 = py * s + pz * c
        py = y1
        pz = z1
        c = cosd(ry)
        s = sind(ry)
        var x1 = px * c + pz * s
        z1 = -px * s + pz * c
        px = x1
        pz = z1
        c = cosd(rz)
        s = sind(rz)
        x1 = px * c - py * s
        y1 = px * s + py * c
        px = x1
        py = y1
        pz += zLift
        c = cosd(viewYaw)
        s = sind(viewYaw)
        x1 = px * c + pz * s
        z1 = -px * s + pz * c
        px = x1
        pz = z1
        c = cosd(viewPitch)
        s = sind(viewPitch)
        y1 = py * c - pz * s
        z1 = py * s + pz * c
        out[0] = px
        out[1] = y1
        out[2] = z1
    }

    private fun faceIndexPointingUp(rx: Float, ry: Float, rz: Float): Int {
        var best = 0
        var bestZ = -1e9f
        for (fi in 0 until 6) {
            objectRotate(FACE_N[fi][0], FACE_N[fi][1], FACE_N[fi][2], rx, ry, rz, tmpP)
            if (tmpP[2] > bestZ) {
                bestZ = tmpP[2]
                best = fi
            }
        }
        return best
    }

    private fun objectRotate(
        x: Float,
        y: Float,
        z: Float,
        rx: Float,
        ry: Float,
        rz: Float,
        out: FloatArray,
    ) {
        var px = x
        var py = y
        var pz = z
        var c = cosd(rx)
        var s = sind(rx)
        var y1 = py * c - pz * s
        var z1 = py * s + pz * c
        py = y1
        pz = z1
        c = cosd(ry)
        s = sind(ry)
        var x1 = px * c + pz * s
        z1 = -px * s + pz * c
        px = x1
        pz = z1
        c = cosd(rz)
        s = sind(rz)
        x1 = px * c - py * s
        y1 = px * s + py * c
        out[0] = x1
        out[1] = y1
        out[2] = pz
    }

    private fun paintTopFace(d: Die, faceIndex: Int, value: Int, rng: Random) {
        val opp = oppositeFace(faceIndex)
        for (i in d.faces.indices) d.faces[i] = 0
        d.faces[faceIndex] = value
        d.faces[opp] = 7 - value
        val rest = (1..6).filter { it != value && it != 7 - value }.shuffled(rng)
        var ri = 0
        for (i in 0 until 6) {
            if (i == faceIndex || i == opp) continue
            d.faces[i] = rest[ri++]
        }
    }

    private fun oppositeFace(fi: Int): Int = when (fi) {
        0 -> 1
        1 -> 0
        2 -> 3
        3 -> 2
        4 -> 5
        else -> 4
    }

    private fun bounceHeight(t: Float, seedFace: Int): Float {
        val phase = t * 2.35f * PI.toFloat() + seedFace * 0.7f
        val u = (t / TUMBLE_SPAN).coerceIn(0f, 1.2f)
        // Envelope → 0 by end of tumble span; stay down after.
        val envelope = (1f - u.coerceAtMost(1f)).coerceAtLeast(0f)
        val env2 = envelope * envelope
        if (u >= 1f) return 0f
        return (0.70f * abs(sin(phase)) + 0.12f * abs(sin(phase * 2.15f))) * env2 + 0.08f * env2
    }

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun snap90(a: Float): Float = round(a / 90f) * 90f

    private fun angleDiff(from: Float, to: Float): Float = normalizeNear(from, to) - to

    private fun wrap360(a: Float): Float {
        var x = a % 360f
        if (x < 0f) x += 360f
        return x
    }

    private fun normalizeNear(from: Float, to: Float): Float {
        var f = from
        while (f - to > 180f) f -= 360f
        while (to - f > 180f) f += 360f
        return f
    }

    private fun cosd(deg: Float): Float = cos(deg * (PI.toFloat() / 180f))
    private fun sind(deg: Float): Float = sin(deg * (PI.toFloat() / 180f))

    private fun copy3(src: FloatArray, dst: FloatArray) {
        dst[0] = src[0]
        dst[1] = src[1]
        dst[2] = src[2]
    }

    companion object {
        /** Master duration of the energetic tumble/bounce (seconds). */
        private const val TUMBLE_SPAN = 1.05f
        private const val JITTER_PX = 2.0f
        private const val TUMBLE_YAW = 28f
        private const val TUMBLE_PITCH = -48f
        private const val REST_YAW = 0f
        private const val REST_PITCH = 0f
        private const val BODY_R = 0.97f
        private const val BODY_G = 0.95f
        private const val BODY_B = 0.91f

        private val FACE_N: Array<FloatArray> = arrayOf(
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(-1f, 0f, 0f),
        )

        private val FACE_CENTERS: Array<FloatArray> = arrayOf(
            floatArrayOf(0f, 0f, 0.5f),
            floatArrayOf(0f, 0f, -0.5f),
            floatArrayOf(0f, 0.5f, 0f),
            floatArrayOf(0f, -0.5f, 0f),
            floatArrayOf(0.5f, 0f, 0f),
            floatArrayOf(-0.5f, 0f, 0f),
        )

        private val FACE_U: Array<FloatArray> = arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 0f, 1f),
        )

        private val FACE_V: Array<FloatArray> = arrayOf(
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, -1f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f),
        )

        private val FACE_CORNERS: Array<Array<FloatArray>> = arrayOf(
            arrayOf(
                floatArrayOf(-0.5f, -0.5f, 0.5f),
                floatArrayOf(0.5f, -0.5f, 0.5f),
                floatArrayOf(0.5f, 0.5f, 0.5f),
                floatArrayOf(-0.5f, 0.5f, 0.5f),
            ),
            arrayOf(
                floatArrayOf(-0.5f, 0.5f, -0.5f),
                floatArrayOf(0.5f, 0.5f, -0.5f),
                floatArrayOf(0.5f, -0.5f, -0.5f),
                floatArrayOf(-0.5f, -0.5f, -0.5f),
            ),
            arrayOf(
                floatArrayOf(-0.5f, 0.5f, 0.5f),
                floatArrayOf(0.5f, 0.5f, 0.5f),
                floatArrayOf(0.5f, 0.5f, -0.5f),
                floatArrayOf(-0.5f, 0.5f, -0.5f),
            ),
            arrayOf(
                floatArrayOf(-0.5f, -0.5f, -0.5f),
                floatArrayOf(0.5f, -0.5f, -0.5f),
                floatArrayOf(0.5f, -0.5f, 0.5f),
                floatArrayOf(-0.5f, -0.5f, 0.5f),
            ),
            arrayOf(
                floatArrayOf(0.5f, -0.5f, 0.5f),
                floatArrayOf(0.5f, -0.5f, -0.5f),
                floatArrayOf(0.5f, 0.5f, -0.5f),
                floatArrayOf(0.5f, 0.5f, 0.5f),
            ),
            arrayOf(
                floatArrayOf(-0.5f, -0.5f, -0.5f),
                floatArrayOf(-0.5f, -0.5f, 0.5f),
                floatArrayOf(-0.5f, 0.5f, 0.5f),
                floatArrayOf(-0.5f, 0.5f, -0.5f),
            ),
        )

        private fun pipLayout(face: Int, o: Float): FloatArray? {
            return when (face.coerceIn(1, 6)) {
                1 -> floatArrayOf(0f, 0f)
                2 -> floatArrayOf(-o, o, o, -o)
                3 -> floatArrayOf(-o, o, 0f, 0f, o, -o)
                4 -> floatArrayOf(-o, o, o, o, -o, -o, o, -o)
                5 -> floatArrayOf(-o, o, o, o, 0f, 0f, -o, -o, o, -o)
                6 -> floatArrayOf(-o, o, -o, 0f, -o, -o, o, o, o, 0f, o, -o)
                else -> null
            }
        }
    }
}
