package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.render.tiles.TileEasing

/**
 * One diagram tile: stable identity ([type] × [copy]) plus current/target pose
 * for current→target ease. [WallBuildView] owns the array; layouts write
 * targets only.
 */
internal class WallBuildAnimTile(
    val type: Int,
    val copy: Int,
    var open: Boolean = true,
    var cx: Float = 0f,
    var cy: Float = 0f,
    var w: Float = 20f,
    var h: Float = 28f,
    var rot: Float = 0f,
    var fromCx: Float = 0f,
    var fromCy: Float = 0f,
    var fromW: Float = 20f,
    var fromH: Float = 28f,
    var fromRot: Float = 0f,
    var fromOpen: Boolean = true,
    var toCx: Float = 0f,
    var toCy: Float = 0f,
    var toW: Float = 20f,
    var toH: Float = 28f,
    var toRot: Float = 0f,
    var toOpen: Boolean = true,
    var elapsed: Float = 1f,
    var duration: Float = 0.45f,
    /**
     * Wall stack floor for painter order: 0 = bottom, 1 = top.
     * All bottoms draw before any tops so the upper layer always covers.
     */
    var stackFloor: Int = 0,
) {
    val hasPose: Boolean
        get() = w > 1f && h > 1f

    /** Position already matches the current target (deal-stagger skip). */
    val atTarget: Boolean
        get() = cx == toCx && cy == toCy

    val motionSettled: Boolean
        get() = elapsed >= duration

    fun setTarget(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rot: Float,
        open: Boolean,
        stackFloor: Int = this.stackFloor,
    ) {
        toCx = cx
        toCy = cy
        toW = w
        toH = h
        toRot = rot
        toOpen = open
        this.stackFloor = stackFloor
    }

    fun beginEaseFromCurrent(duration: Float) {
        this.duration = duration
        fromCx = cx
        fromCy = cy
        fromW = w
        fromH = h
        fromRot = rot
        fromOpen = open
        elapsed = 0f
    }

    fun snapToTarget() {
        cx = toCx
        cy = toCy
        w = toW
        h = toH
        rot = toRot
        open = toOpen
        fromOpen = toOpen
        elapsed = duration
    }

    fun advance(dtSec: Float) {
        elapsed += dtSec
        if (elapsed < 0f) return
        if (elapsed >= duration) {
            cx = toCx
            cy = toCy
            w = toW
            h = toH
            rot = toRot
            open = toOpen
            elapsed = duration
            return
        }
        val u = TileEasing.easeOutCubic(elapsed / duration.coerceAtLeast(1e-4f))
        cx = TileEasing.lerp(fromCx, toCx, u)
        cy = TileEasing.lerp(fromCy, toCy, u)
        w = TileEasing.lerp(fromW, toW, u)
        h = TileEasing.lerp(fromH, toH, u)
        rot = TileEasing.angleLerpDeg(fromRot, toRot, u)
        open = if (u < 0.45f) fromOpen else toOpen
    }

    companion object {
        fun anyHasPose(tiles: Array<WallBuildAnimTile>): Boolean =
            tiles.any { it.hasPose }

        /**
         * Faces phase stacks 4 copies on one center — seeding that into the sim
         * would explode. Detect by unique-ish centers ≪ tile count.
         */
        fun lookStacked(tiles: Array<WallBuildAnimTile>): Boolean {
            var unique = 0
            val seen = HashSet<Long>(WALL_SIZE)
            val quant = 2f // px
            for (t in tiles) {
                if (t.w < 1f) continue
                val key = (t.cx / quant).toLong() shl 32 xor (t.cy / quant).toLong()
                if (seen.add(key)) unique++
            }
            return unique < WALL_SIZE / 3
        }
    }
}
