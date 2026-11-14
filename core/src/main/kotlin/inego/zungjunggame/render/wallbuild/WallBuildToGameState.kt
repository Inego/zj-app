package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE

/**
 * Bridge wall-build diagram deal geometry → engine [GameState] opening deal.
 *
 * Pure tooling: given a break count and slot→tile-type map (from
 * [WallBuildLessonState]), build a [wallOrder] so [GameState.fromWallOrder]
 * yields the same per-seat concealed **multisets** as the wall-build hands.
 * Display order is sorted on both sides; only multisets need to match.
 */
object WallBuildToGameState {

    /**
     * Per-seat opening hand tile types in deal order (not sorted).
     * Seat 0 = East (14 tiles), seats 1–3 have 13.
     */
    fun dealHandTypes(
        breakStackCount: Int,
        tileTypeAtSlot: (slot: Int) -> Int,
    ): Array<IntArray> {
        require(breakStackCount in 2..12) { "breakStackCount $breakStackCount" }
        return Array(NUM_PLAYERS) { seat ->
            val slots = WallSlotLayout.dealHandSlotIds(breakStackCount, seat)
            IntArray(slots.size) { i -> tileTypeAtSlot(slots[i]) }
        }
    }

    /**
     * Full [WALL_SIZE] draw order for [GameState.fromWallOrder].
     *
     * Engine deal packs: seat 0 ×13, seat 1 ×13, seat 2 ×13, seat 3 ×13, then
     * East's 14th at index 52 when [eastSeat] is 0. Remaining multiset fills
     * live remainder + dead wall (order only matters for later draws).
     */
    fun wallOrderFromDeal(
        breakStackCount: Int,
        tileTypeAtSlot: (slot: Int) -> Int,
        eastSeat: Int = 0,
    ): IntArray {
        val hands = dealHandTypes(breakStackCount, tileTypeAtSlot)
        val dealer = ((eastSeat % NUM_PLAYERS) + NUM_PLAYERS) % NUM_PLAYERS
        require(hands[dealer].size == WallSlotLayout.HAND_TILES_EAST) {
            "east hand size ${hands[dealer].size}"
        }
        for (s in 0 until NUM_PLAYERS) {
            if (s == dealer) continue
            require(hands[s].size == WallSlotLayout.HAND_TILES_OTHER) {
                "seat $s hand size ${hands[s].size}"
            }
        }

        val wall = IntArray(WALL_SIZE)
        var wi = 0
        // 13 tiles per seat in seat index order (engine dealOpeningHand).
        for (seat in 0 until NUM_PLAYERS) {
            val hand = hands[seat]
            val take = if (seat == dealer) hand.size - 1 else hand.size
            for (i in 0 until take) {
                wall[wi++] = hand[i]
            }
        }
        // East's 14th is the last draw of the opening deal.
        wall[wi++] = hands[dealer].last()
        check(wi == NUM_PLAYERS * 13 + 1) { "dealt $wi tiles" }

        // Leftover types = full multiset minus dealt hands (by count).
        val remaining = IntArray(NUM_TILE_TYPES) { 4 }
        for (seat in 0 until NUM_PLAYERS) {
            for (t in hands[seat]) {
                require(t in 0 until NUM_TILE_TYPES) { "tile type $t" }
                remaining[t]--
                require(remaining[t] >= 0) { "over-dealt type $t" }
            }
        }
        for (t in 0 until NUM_TILE_TYPES) {
            repeat(remaining[t]) {
                wall[wi++] = t
            }
        }
        check(wi == WALL_SIZE) { "wall filled $wi / $WALL_SIZE" }
        return wall
    }

    /**
     * Opening [GameState] matching wall-build dealt hands (East = dealer).
     */
    fun openingState(deal: WallBuildDeal, eastSeat: Int = 0, allowChickenHands: Boolean = false): GameState =
        openingState(
            breakStackCount = deal.stackCount,
            tileTypeAtSlot = { slot -> deal.slotToTile[slot] / 4 },
            eastSeat = eastSeat,
            allowChickenHands = allowChickenHands,
        )

    fun openingState(
        breakStackCount: Int,
        tileTypeAtSlot: (slot: Int) -> Int,
        eastSeat: Int = 0,
        allowChickenHands: Boolean = false,
    ): GameState {
        val order = wallOrderFromDeal(breakStackCount, tileTypeAtSlot, eastSeat)
        return GameState.fromWallOrder(
            wallOrder = order,
            eastSeat = eastSeat,
            allowChickenHands = allowChickenHands,
        )
    }

    /** Remaining live-wall drawables after opening deal (engine constant path). */
    fun liveWallAfterDeal(): Int = LIVE_WALL_DRAWABLE_SIZE - (NUM_PLAYERS * 13 + 1)
}
