package inego.zungjunggame.shuffle

/**
 * One rigid mahjong tile in the 2D shuffle sim.
 *
 * Pose is center (x, y) and angle θ (radians, CCW). Local axes at θ = 0:
 * half-width along X, half-length along Y.
 */
class ShuffleTileBody(
    /** Stable identity (0 until caller assigns type/copy). */
    var id: Int = 0,
    /** Optional tile type index (0–33); sim does not use it. */
    var type: Int = 0,
    var x: Float = 0f,
    var y: Float = 0f,
    /** Radians, counter-clockwise. */
    var theta: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var omega: Float = 0f,
) {
    /** Accumulated external force for the current substep (cleared each step). */
    var fx: Float = 0f
    var fy: Float = 0f
    var torque: Float = 0f

    var awake: Boolean = true
    /** Time continuously under sleep thresholds. */
    var sleepTimer: Float = 0f

    /**
     * When true, this tile is held by a hand: infinite mass in the solver,
     * pose driven kinematically. Passive tiles stay free.
     */
    var kinematic: Boolean = false
    /** Hand index currently gripping this tile, or −1. */
    var grippedBy: Int = -1

    fun speedSq(): Float = vx * vx + vy * vy

    fun clearForces() {
        fx = 0f
        fy = 0f
        torque = 0f
    }

    fun applyImpulse(jx: Float, jy: Float, rx: Float, ry: Float, invMass: Float, invInertia: Float) {
        if (kinematic || invMass == 0f) return
        vx += jx * invMass
        vy += jy * invMass
        // τ impulse: r × J = rx*jy − ry*jx
        omega += (rx * jy - ry * jx) * invInertia
        awake = true
        sleepTimer = 0f
    }

    fun setPose(px: Float, py: Float, angle: Float) {
        x = px
        y = py
        theta = angle
    }

    fun setVelocity(lvx: Float, lvy: Float, ang: Float) {
        vx = lvx
        vy = lvy
        omega = ang
        if (lvx * lvx + lvy * lvy > 1e-10f || kotlin.math.abs(ang) > 1e-10f) {
            awake = true
            sleepTimer = 0f
        }
    }

    /** Read-only snapshot for render / debug. */
    fun snapshot(): TilePoseSnapshot =
        TilePoseSnapshot(id, type, x, y, theta, vx, vy, omega, awake)
}

/** Immutable pose snapshot for consumers that must not mutate the sim. */
data class TilePoseSnapshot(
    val id: Int,
    val type: Int,
    val x: Float,
    val y: Float,
    val theta: Float,
    val vx: Float,
    val vy: Float,
    val omega: Float,
    val awake: Boolean,
)
