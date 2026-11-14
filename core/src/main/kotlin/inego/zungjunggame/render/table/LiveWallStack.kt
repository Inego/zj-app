package inego.zungjunggame.render.table

import inego.zungjunggame.render.tiles.TileEasing
import kotlin.math.min

/**
 * Closed backs behind a wall take. The front card is not here: the tile that
 * left the wall is the visual [inego.zungjunggame.render.tiles.TileBoard] flies
 * to its slot. Cards that were behind it step forward, and a new back fades in
 * at the deepest slot while three or more tiles remain.
 */
internal class LiveWallStack {
    /** Cards currently drawn at rest. Negative until the first [sync]. */
    private var shown: Int = -1
    private var previousCount: Int = -1
    private var shiftFrom: Int = 0
    private var shiftTo: Int = 0
    private var shiftU: Float = 1f

    fun reset() {
        shown = -1
        previousCount = -1
        shiftFrom = 0
        shiftTo = 0
        shiftU = 1f
    }

    /** True when [liveWallSize] is one less than the count last synced. */
    fun willDepartOne(liveWallSize: Int): Boolean {
        return previousCount >= 0 && previousCount - liveWallSize == 1
    }

    /**
     * @param spawnedFromWall tiles [inego.zungjunggame.render.tiles.TileBoard]
     *        spawned from the wall on this reconcile
     */
    fun sync(liveWallSize: Int, spawnedFromWall: Int) {
        val count = liveWallSize.coerceAtLeast(0)
        val taken = if (previousCount < 0) 0 else (previousCount - count).coerceAtLeast(0)
        previousCount = count
        val target = min(count, MAX_STACK)
        if (shown < 0) {
            shown = target
            shiftU = 1f
            return
        }
        if (taken == 1 && spawnedFromWall == 1) {
            if (shiftU < 1f) {
                shown = shiftTo
                shiftU = 1f
            }
            if (isSingleDeparture(shown, target)) {
                shiftFrom = shown
                shiftTo = target
                shiftU = 0f
                return
            }
        }
        if (shiftU < 1f && taken == 0 && target == shiftTo) return
        shown = target
        shiftU = 1f
    }

    /** True while rear cards are still stepping into the hole a take left. */
    fun isShifting(): Boolean = shiftU < 1f

    fun advance(dt: Float, durationSec: Float) {
        if (shiftU >= 1f || dt <= 0f) return
        val duration = durationSec.coerceAtLeast(1e-4f)
        shiftU = (shiftU + dt / duration).coerceAtMost(1f)
        if (shiftU >= 1f) shown = shiftTo
    }

    fun cards(): List<WallStackCard> {
        if (shiftU < 1f) return shiftCards(shiftFrom, shiftTo, shiftU)
        return restCards(shown.coerceAtLeast(0))
    }

    companion object {
        const val MAX_STACK = 3

        private fun isSingleDeparture(before: Int, after: Int): Boolean {
            if (before <= 0) return false
            if (before == MAX_STACK && after == MAX_STACK) return true
            return after == before - 1
        }

        /** Depth 0 is the front card. */
        fun restCards(shown: Int): List<WallStackCard> =
            List(shown.coerceIn(0, MAX_STACK)) { WallStackCard(it.toFloat(), 1f) }

        /**
         * [before] includes the card that just left. Remaining cards step from
         * depth d to d−1. When the stack stays full, a new card fades in at the
         * deepest slot (where the third card was).
         */
        fun shiftCards(before: Int, after: Int, u: Float): List<WallStackCard> {
            val eased = TileEasing.easeOutCubic(u)
            val behind = (before - 1).coerceAtLeast(0)
            val keep = min(behind, after.coerceAtLeast(0))
            val cards = ArrayList<WallStackCard>(keep + 1)
            for (i in 0 until keep) {
                val fromDepth = (i + 1).toFloat()
                val toDepth = i.toFloat()
                cards += WallStackCard(fromDepth + (toDepth - fromDepth) * eased, 1f)
            }
            if (after > behind && eased > 0f) {
                cards += WallStackCard((before - 1).toFloat(), eased)
            }
            return cards
        }
    }
}

/** One closed back in the live-wall stack. Depth 0 is the front. */
internal data class WallStackCard(
    val depth: Float,
    val alpha: Float,
)
