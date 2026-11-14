package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerLayout

/**
 * Shared fixture vocabulary that is *not* part of dealing a board.
 *
 * Dealing itself belongs to [TileStock]. What stays here is the two things a chapter
 * needs that are not about the stock: the seat names, and the unknown-tile table a
 * `GameStateLayout` needs. Chapters still assemble their own layout, because the
 * decision phase and the pending-reaction seat are per-scenario facts (the engine
 * requires `currentPlayer != lastDiscarder`).
 */
internal object ScenarioStateKit {

    /**
     * Seat indices for the fixtures, named by the players' relation to the human's seat
     * (always seat 0). Chapters read these instead of restating the wheel.
     */
    const val YOU = 0
    const val RIGHT = 1
    const val OPPOSITE = 2
    const val LEFT = 3

    /**
     * Tiles each seat cannot account for: the whole set minus that seat's concealed
     * hand and every tile already in the rivers. [pending] is the discard still in
     * flight; pass `-1` when the layout has no pending discard.
     */
    fun unseenCounts(
        players: Array<PlayerLayout>,
        discards: Array<List<Int>>,
        pending: Int = -1,
    ): Array<IntArray> = Array(NUM_PLAYERS) { p ->
        val unknown = IntArray(NUM_TILE_TYPES) { 4 }
        for (t in 0 until NUM_TILE_TYPES) {
            unknown[t] -= players[p].concealed[t]
        }
        for (seat in 0 until NUM_PLAYERS) {
            for (tile in discards[seat]) unknown[tile]--
        }
        if (pending in 0 until NUM_TILE_TYPES) {
            unknown[pending]--
        }
        unknown
    }
}

