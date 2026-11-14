package inego.zungjunggame.render.tiles

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One tile (or tile-sized sprite) to include in a highlight outline.
 *
 * [w]/[h] are the unrotated draw size (same as [TileDrawer.drawOpen]).
 * [groupKey] is the author [inego.zungjunggame.render.table.HighlightGroup] index — different groups never
 * merge, even when adjacent. [glow] picks the stroke color. Mixed-rotation
 * neighbors (a pung's sideways claim tile) still merge when their visual
 * boxes touch and they share a group.
 */
data class TileHighlightBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float = 0f,
    val groupKey: Int = 0,
    val glow: TileFocusGlow = TileFocusGlow.Prompt,
)

/**
 * Axis-aligned rect in the cluster's local (unrotated) frame, then drawn
 * rotated by [rotationDeg] about ([cx], [cy]).
 */
data class TileHighlightCluster(
    val groupKey: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float,
)

/**
 * Groups nearby tiles of the same [TileHighlightBox.groupKey] into one outline
 * each (author [inego.zungjunggame.render.table.HighlightGroup]s stay separate even when adjacent).
 *
 * Adjacent = same [TileHighlightBox.groupKey] and visual world AABBs within
 * [JOIN_FRAC] × min-side of each other (hand tiles with the usual ~8% gap
 * merge; a pung's upright + sideways claim tiles merge; the wider
 * drawn-tile / meld-group gaps do not). Same-rotation clusters keep a
 * rotated local union; mixed 0°/90° groups use an axis-aligned world box.
 */
object TileHighlight {

    /** Expand each visual AABB by this × min(w, h) when testing adjacency. */
    const val JOIN_FRAC = 0.12f

    /**
     * Rotations farther apart than this (degrees) are treated as mixed:
     * the cluster outline is axis-aligned in world space.
     */
    const val ROT_JOIN_DEG = 8f

    fun cluster(
        boxes: List<TileHighlightBox>,
        dest: MutableList<TileHighlightCluster> = ArrayList(),
    ): List<TileHighlightCluster> {
        dest.clear()
        if (boxes.isEmpty()) return dest

        val n = boxes.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(i: Int, j: Int) {
            val a = find(i)
            val b = find(j)
            if (a != b) parent[b] = a
        }

        for (i in 0 until n) {
            val a = boxes[i]
            if (a.w < 1f || a.h < 1f) continue
            for (j in i + 1 until n) {
                val b = boxes[j]
                if (a.groupKey != b.groupKey) continue
                if (b.w < 1f || b.h < 1f) continue
                if (adjacent(a, b)) union(i, j)
            }
        }

        val members = HashMap<Int, ArrayList<Int>>(n)
        for (i in 0 until n) {
            val box = boxes[i]
            if (box.w < 1f || box.h < 1f) continue
            members.getOrPut(find(i)) { ArrayList() }.add(i)
        }
        for (idxs in members.values) {
            dest += boundsOf(boxes, idxs)
        }
        return dest
    }

    private fun adjacent(a: TileHighlightBox, b: TileHighlightBox): Boolean {
        val pad = JOIN_FRAC * min(min(a.w, a.h), min(b.w, b.h))
        val aa = visualAabb(a)
        val bb = visualAabb(b)
        return aabbOverlap(
            aa.x - pad,
            aa.y - pad,
            aa.w + pad * 2f,
            aa.h + pad * 2f,
            bb.x,
            bb.y,
            bb.w,
            bb.h,
        )
    }

    private fun boundsOf(boxes: List<TileHighlightBox>, idxs: List<Int>): TileHighlightCluster {
        val first = boxes[idxs[0]]
        val mixed = idxs.any {
            TileEasing.angleDeltaDeg(boxes[it].rotationDeg, first.rotationDeg) > ROT_JOIN_DEG
        }
        if (mixed) {
            var left = Float.POSITIVE_INFINITY
            var bottom = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var top = Float.NEGATIVE_INFINITY
            for (i in idxs) {
                val box = visualAabb(boxes[i])
                left = minOf(left, box.x)
                bottom = minOf(bottom, box.y)
                right = maxOf(right, box.x + box.w)
                top = maxOf(top, box.y + box.h)
            }
            val lw = (right - left).coerceAtLeast(1f)
            val lh = (top - bottom).coerceAtLeast(1f)
            return TileHighlightCluster(
                groupKey = first.groupKey,
                cx = (left + right) * 0.5f,
                cy = (bottom + top) * 0.5f,
                w = lw,
                h = lh,
                rotationDeg = 0f,
            )
        }
        val rot = first.rotationDeg
        var left = Float.POSITIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = Float.NEGATIVE_INFINITY
        for (i in idxs) {
            val b = boxes[i]
            val (lx, ly) = toLocal(b.cx, b.cy, rot)
            val hw = b.w * 0.5f
            val hh = b.h * 0.5f
            left = minOf(left, lx - hw)
            bottom = minOf(bottom, ly - hh)
            right = maxOf(right, lx + hw)
            top = maxOf(top, ly + hh)
        }
        val lw = (right - left).coerceAtLeast(1f)
        val lh = (top - bottom).coerceAtLeast(1f)
        val (cx, cy) = toWorld((left + right) * 0.5f, (bottom + top) * 0.5f, rot)
        return TileHighlightCluster(
            groupKey = first.groupKey,
            cx = cx,
            cy = cy,
            w = lw,
            h = lh,
            rotationDeg = rot,
        )
    }

    /**
     * Axis-aligned world box of a rotated tile (unrotated [TileHighlightBox.w]/[h]
     * around [TileHighlightBox.cx]/[cy]).
     */
    internal fun visualAabb(box: TileHighlightBox): Aabb {
        val r = box.rotationDeg * DEG_TO_RAD
        val c = abs(cos(r))
        val s = abs(sin(r))
        val bw = c * box.w + s * box.h
        val bh = s * box.w + c * box.h
        return Aabb(box.cx - bw * 0.5f, box.cy - bh * 0.5f, bw, bh)
    }

    internal data class Aabb(val x: Float, val y: Float, val w: Float, val h: Float)

    /** World → local for a CCW rotation about the origin. */
    internal fun toLocal(wx: Float, wy: Float, rotationDeg: Float): Pair<Float, Float> {
        if (abs(rotationDeg) < 0.01f) return wx to wy
        val r = rotationDeg * DEG_TO_RAD
        val c = cos(r)
        val s = sin(r)
        return (c * wx + s * wy) to (-s * wx + c * wy)
    }

    internal fun toWorld(lx: Float, ly: Float, rotationDeg: Float): Pair<Float, Float> {
        if (abs(rotationDeg) < 0.01f) return lx to ly
        val r = rotationDeg * DEG_TO_RAD
        val c = cos(r)
        val s = sin(r)
        return (c * lx - s * ly) to (s * lx + c * ly)
    }

    private fun aabbOverlap(
        ax: Float,
        ay: Float,
        aw: Float,
        ah: Float,
        bx: Float,
        by: Float,
        bw: Float,
        bh: Float,
    ): Boolean =
        ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
}
