package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tool tests for wall-build → engine handoff. No curriculum body keys.
 */
class WallBuildToGameStateTest {

    @Test
    fun wallOrderPreservesHandMultisets() {
        val breakCount = 7
        // Identity map: slot i → tile index i → type i/4
        val typeAtSlot = { slot: Int -> slot / 4 }
        val hands = WallBuildToGameState.dealHandTypes(breakCount, typeAtSlot)
        assertEquals(NUM_PLAYERS, hands.size)
        assertEquals(WallSlotLayout.HAND_TILES_EAST, hands[0].size)
        for (s in 1 until NUM_PLAYERS) {
            assertEquals(WallSlotLayout.HAND_TILES_OTHER, hands[s].size)
        }

        val order = WallBuildToGameState.wallOrderFromDeal(breakCount, typeAtSlot)
        assertEquals(WALL_SIZE, order.size)

        val state = WallBuildToGameState.openingState(breakCount, typeAtSlot)
        for (seat in 0 until NUM_PLAYERS) {
            val expected = multiset(hands[seat])
            val actual = multisetFromConcealed(state.players[seat].concealed)
            assertEquals(expected, actual, "seat $seat multiset")
        }
        assertEquals(
            WallBuildToGameState.liveWallAfterDeal(),
            state.liveWallSize,
        )
        assertEquals(0, state.eastSeat)
        assertEquals(0, state.currentPlayer)
    }

    @Test
    fun openingStateFromDealMatchesSlotMap() {
        val deal = WallBuildDeal.invent(kotlin.random.Random(42))
        val viaDeal = WallBuildToGameState.openingState(deal)
        val viaSlots = WallBuildToGameState.openingState(
            breakStackCount = deal.stackCount,
            tileTypeAtSlot = { slot -> deal.slotToTile[slot] / 4 },
        )
        for (seat in 0 until NUM_PLAYERS) {
            assertTrue(
                viaDeal.players[seat].concealed.contentEquals(viaSlots.players[seat].concealed),
                "seat $seat",
            )
        }
    }

    @Test
    fun shuffledMapStillValidMultiset() {
        val lesson = WallBuildLessonState()
        lesson.commit(2, 5)
        lesson.ensureShuffledWallMapping(kotlin.random.Random(99))
        val breakCount = lesson.stackCount
        val typeAtSlot = { slot: Int -> lesson.tileAtSlot(slot) / 4 }
        val state = WallBuildToGameState.openingState(breakCount, typeAtSlot)
        // Full wall multiset: 4 of each type still held across board.
        val counts = IntArray(NUM_TILE_TYPES)
        for (p in state.players) {
            for (t in 0 until NUM_TILE_TYPES) counts[t] += p.concealed[t]
        }
        // Remaining wall tiles (live remainder + dead) are still in wall[].
        val drawn = NUM_PLAYERS * 13 + 1
        for (i in drawn until WALL_SIZE) {
            counts[state.wall[i]]++
        }
        for (t in 0 until NUM_TILE_TYPES) {
            assertEquals(4, counts[t], "type $t")
        }
    }

    @Test
    fun dealHandSlotIdsCount() {
        val breakCount = 8
        val east = WallSlotLayout.dealHandSlotIds(breakCount, 0)
        assertEquals(WallSlotLayout.HAND_TILES_EAST, east.size)
        assertEquals(east.distinct().size, east.size)
        for (seat in 1 until NUM_PLAYERS) {
            val slots = WallSlotLayout.dealHandSlotIds(breakCount, seat)
            assertEquals(WallSlotLayout.HAND_TILES_OTHER, slots.size)
            assertEquals(slots.distinct().size, slots.size)
        }
    }

    private fun multiset(types: IntArray): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (t in types) m[t] = (m[t] ?: 0) + 1
        return m
    }

    private fun multisetFromConcealed(concealed: IntArray): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (t in 0 until NUM_TILE_TYPES) {
            if (concealed[t] > 0) m[t] = concealed[t]
        }
        return m
    }
}
