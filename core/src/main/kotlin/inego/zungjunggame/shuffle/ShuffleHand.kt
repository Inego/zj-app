package inego.zungjunggame.shuffle

/**
 * Kinematic palm that grips a compact cluster of tiles and translates them
 * as a rigid group. Not a force field and not a rigid body itself.
 *
 * Tutorial model: one hand at a time holds up to 7 tiles near the free tile
 * farthest from the table square center, pushes toward a jittered point near
 * that center (slightly past), releases, then the next stroke starts immediately.
 */
class ShuffleHand(
    var x: Float = 0f,
    var y: Float = 0f,
    /** World velocity of the palm (drives gripped tiles). */
    var vx: Float = 0f,
    var vy: Float = 0f,
) {
    val gripCount: Int get() = _gripCount
    val hasGrip: Boolean get() = _gripCount > 0

    private var _gripCount: Int = 0
    /** Tile indices currently held (−1 = empty slot). */
    internal val gripTiles = IntArray(MAX_GRIP) { -1 }
    /** Offset of each gripped tile from the palm at acquire time. */
    internal val gripOx = FloatArray(MAX_GRIP)
    internal val gripOy = FloatArray(MAX_GRIP)
    /** Absolute angle locked at acquire (radians). */
    internal val gripTheta = FloatArray(MAX_GRIP)

    fun setMotion(px: Float, py: Float, pvx: Float, pvy: Float) {
        x = px
        y = py
        vx = pvx
        vy = pvy
    }

    fun deactivate() {
        vx = 0f
        vy = 0f
    }

    internal fun clearGrip() {
        for (i in 0 until MAX_GRIP) gripTiles[i] = -1
        _gripCount = 0
    }

    internal fun setGripSlot(slot: Int, tileIndex: Int, ox: Float, oy: Float, theta: Float) {
        gripTiles[slot] = tileIndex
        gripOx[slot] = ox
        gripOy[slot] = oy
        gripTheta[slot] = theta
    }

    internal fun finalizeGripCount(count: Int) {
        _gripCount = count.coerceIn(0, MAX_GRIP)
        for (i in _gripCount until MAX_GRIP) gripTiles[i] = -1
    }

    companion object {
        /** Hard cap — tutorial strokes use up to 7 in a 2-tile radius. */
        const val MAX_GRIP = 10
    }
}
