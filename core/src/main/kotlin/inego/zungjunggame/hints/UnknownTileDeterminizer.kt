package inego.zungjunggame.hints

import inego.zungjunggame.engine.DEAD_WALL_SIZE
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.random.Random

/**
 * Imperfect-information determinization for Monte Carlo playouts.
 *
 * From [observerSeat]'s view, tiles in other players' closed hands and in the
 * remaining live + dead wall are unknown. This shuffles that multiset and
 * rewrites those slots in place. Public info (observer hand, open melds,
 * rivers, pending discard) is preserved. [GameState.unknownTileCountsByPlayer]
 * is left unchanged (information structure is the same).
 *
 * Reuses an internal tile pool to avoid per-call allocations.
 */
class UnknownTileDeterminizer {
    /** Enough for all remaining wall slots + three closed hands. */
    private val pool = IntArray(WALL_SIZE)
    private val handSizes = IntArray(NUM_PLAYERS)

    /**
     * Shuffle unknown tiles into [state] in place.
     * [state] must already be a working copy (not the live table state).
     */
    fun determinize(state: GameState, observerSeat: Int, rng: Random) {
        require(observerSeat in 0 until NUM_PLAYERS)

        val firstWallSlot = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        // Remaining live draws + full dead wall (never drawn under current rules).
        check(firstWallSlot in 0..LIVE_WALL_DRAWABLE_SIZE)
        check(firstWallSlot + state.liveWallSize + DEAD_WALL_SIZE == WALL_SIZE)

        var poolSize = 0

        for (i in firstWallSlot until WALL_SIZE) {
            pool[poolSize++] = state.wall[i]
        }

        for (p in 0 until NUM_PLAYERS) {
            if (p == observerSeat) {
                handSizes[p] = 0
                continue
            }
            var size = 0
            val concealed = state.players[p].concealed
            for (t in 0 until NUM_TILE_TYPES) {
                val c = concealed[t]
                if (c <= 0) continue
                repeat(c) { pool[poolSize++] = t }
                size += c
            }
            handSizes[p] = size
        }

        // Fisher–Yates
        for (i in poolSize - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }

        var idx = 0
        for (p in 0 until NUM_PLAYERS) {
            if (p == observerSeat) continue
            val ps = state.players[p]
            ps.concealed.fill(0)
            val n = handSizes[p]
            for (k in 0 until n) {
                ps.concealed[pool[idx++]]++
            }
            // Force active-tile cache rebuild on next ensure.
            ps.activeConcealedTileCount = -1
            // Invalidate hu-score caches for this seat (revision mismatch).
            state.handRevisions[p]++
        }

        for (i in firstWallSlot until WALL_SIZE) {
            state.wall[i] = pool[idx++]
        }
        check(idx == poolSize) {
            "determinize pool mismatch: used $idx of $poolSize"
        }
    }
}
