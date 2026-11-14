package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuCheckTest {
    @Test
    fun canDecomposeThreePungsAndPair() {
        // 111222333m 99s
        val counts = IntArray(NUM_TILE_TYPES)
        counts[0] = 3
        counts[1] = 3
        counts[2] = 3
        counts[26] = 2
        assertTrue(HuCheck.canDecompose(counts, setsNeeded = 3))
    }

    @Test
    fun sevenPairsAcceptsQuadAsTwoPairs() {
        val counts = IntArray(NUM_TILE_TYPES)
        counts[0] = 4 // two pairs
        counts[1] = 2
        counts[2] = 2
        counts[3] = 2
        counts[4] = 2
        counts[5] = 2
        assertTrue(HuCheck.isSevenPairsHand(counts))
    }

    @Test
    fun incompleteIsNotSevenPairs() {
        val counts = IntArray(NUM_TILE_TYPES)
        counts[0] = 2
        counts[1] = 2
        assertFalse(HuCheck.isSevenPairsHand(counts))
    }
}
