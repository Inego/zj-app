package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ready highlighting for the two Thirteen Terminals waits. The green outline is
 * [DiscardWaitTable.readyTypes] and the hover row is [DiscardWaitTable.waitsFor]
 * (`TileBoard.collectReady` / `TouchTileMagnifier`).
 */
class DiscardWaitsThirteenTerminalsTest {

    private val terminals = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)

    private fun player(hand: IntArray): PlayerState {
        val p = PlayerState()
        for (t in hand) ++p.concealed[t]
        p.activeConcealedTileCount = hand.size
        return p
    }

    private fun unseen(hand: IntArray): IntArray {
        val u = IntArray(NUM_TILE_TYPES) { 4 }
        for (t in hand) --u[t]
        return u
    }

    private fun assess(hand: IntArray, vararg discards: Int): DiscardWaitTable =
        DiscardWaits.assess(
            player = player(hand),
            discardTypes = discards,
            unseen = unseen(hand),
            playerIndex = 0,
            eastSeat = 0,
            allowChickenHands = false,
        )

    @Test
    fun thirteenDistinctTerminalsHighlightTheJunkDiscardAndEveryWait() {
        // All 13 terminal/honor types + 2c. Throwing 2c leaves 13 distinct types,
        // waiting on any of them.
        val hand = terminals + 1
        val table = assess(hand, 1)

        assertEquals(setOf(1), table.readyTypes)
        val waits = table.waitsFor(1)
        assertEquals(terminals.toList(), waits.map { it.tileType })
        assertEquals(160, waits.first().score)
    }

    @Test
    fun twelveDistinctTerminalsPlusPairHighlightsItsDiscardsAndTheThirteenthWait() {
        // Pair 1c + 11 other terminal/honor types (9b missing) + junk 2c.
        // Throwing 2c leaves 12 distinct types with the 1c pair, waiting on 9b.
        val pairSource = intArrayOf(0, 0, 8, 9, 17, 18, 27, 28, 29, 30, 31, 32, 33, 1)
        val pairTable = assess(pairSource, 1)
        assertEquals(setOf(1), pairTable.readyTypes)
        assertEquals(listOf(DiscardWait(26, 4, 160)), pairTable.waitsFor(1))

        // Two pairs + the missing 9b: throwing either pair tile leaves 12 distinct
        // types with the other pair.
        val twoPairSource = intArrayOf(0, 0, 8, 8, 9, 17, 18, 27, 28, 29, 30, 31, 32, 33)
        val twoPairTable = assess(twoPairSource, 0, 8)
        assertEquals(setOf(0, 8), twoPairTable.readyTypes)
        assertEquals(listOf(DiscardWait(26, 4, 160)), twoPairTable.waitsFor(0))
        assertEquals(listOf(DiscardWait(26, 4, 160)), twoPairTable.waitsFor(8))
    }
}
