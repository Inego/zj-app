package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The prod hand behind the Seven Pairs ready-discard bug: six pairs plus two
 * singles, so either single is a ready discard. The green outline is
 * [DiscardWaitTable.readyTypes] and the hover row is [DiscardWaitTable.waitsFor].
 */
class DiscardWaitsSevenPairsTest {

    @Test
    fun sixPairsPlusTwoSinglesHighlightsBothDiscards() {
        // 3c3c 8c8c 3d3d 4d4d 6b6b 7b7b 1b 2b
        val hand = intArrayOf(2, 2, 7, 7, 11, 11, 12, 12, 18, 19, 23, 23, 24, 24)
        val player = PlayerState()
        for (t in hand) ++player.concealed[t]
        player.activeConcealedTileCount = hand.size

        val unseen = IntArray(NUM_TILE_TYPES) { 4 }
        for (t in hand) --unseen[t]

        val table = DiscardWaits.assess(
            player = player,
            discardTypes = intArrayOf(18, 19),
            unseen = unseen,
            playerIndex = 0,
            eastSeat = 2,
            allowChickenHands = false,
        )

        assertEquals(setOf(18, 19), table.readyTypes)
        // Throwing 1b waits on 2b: Seven Pairs 30 + No Terminals 5.
        assertEquals(listOf(DiscardWait(19, 3, 35)), table.waitsFor(18))
        // Throwing 2b keeps 1b, so it waits on 1b for Seven Pairs only.
        assertEquals(listOf(DiscardWait(18, 3, 30)), table.waitsFor(19))
    }
}
