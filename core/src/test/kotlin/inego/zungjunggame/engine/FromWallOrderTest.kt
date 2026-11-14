package inego.zungjunggame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GameState.fromWallOrder] — deterministic opening deals for tutorials / tests.
 */
class FromWallOrderTest {

    @Test
    fun dealsThirteenPlusEastFromKnownWall() {
        val wall = canonicalWallShuffled(Random(42))
        val state = GameState.fromWallOrder(wall, eastSeat = 0)

        assertEquals(0, state.eastSeat)
        assertEquals(0, state.currentPlayer)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(INITIAL_DECISION_LIVE_WALL_SIZE, state.liveWallSize)
        assertEquals(-1, state.lastDrawnTile)
        assertEquals(-1, state.lastDrawnPlayer)
        assertEquals(false, state.allowChickenHands)

        assertEquals(14, state.players[0].concealed.sum())
        assertEquals(13, state.players[1].concealed.sum())
        assertEquals(13, state.players[2].concealed.sum())
        assertEquals(13, state.players[3].concealed.sum())
    }

    @Test
    fun handsMatchDrawSequence() {
        // wall[0] is first draw. Deal: 13×seat0, 13×seat1, 13×seat2, 13×seat3, +1 East.
        val wall = IntArray(WALL_SIZE) { it / 4 } // already valid multiset (canonical)

        val state = GameState.fromWallOrder(wall, eastSeat = 0)

        // Seat 0: wall[0..12] plus extra East tile wall[52]
        val expected0 = IntArray(NUM_TILE_TYPES)
        for (i in 0 until 13) expected0[wall[i]]++
        expected0[wall[52]]++
        assertTrue(state.players[0].concealed.contentEquals(expected0))

        val expected1 = IntArray(NUM_TILE_TYPES)
        for (i in 13 until 26) expected1[wall[i]]++
        assertTrue(state.players[1].concealed.contentEquals(expected1))

        val expected2 = IntArray(NUM_TILE_TYPES)
        for (i in 26 until 39) expected2[wall[i]]++
        assertTrue(state.players[2].concealed.contentEquals(expected2))

        val expected3 = IntArray(NUM_TILE_TYPES)
        for (i in 39 until 52) expected3[wall[i]]++
        assertTrue(state.players[3].concealed.contentEquals(expected3))
    }

    @Test
    fun eastSeatGetsFourteenthTile() {
        val wall = canonicalWallShuffled(Random(7))
        val state = GameState.fromWallOrder(wall, eastSeat = 2)

        assertEquals(2, state.eastSeat)
        assertEquals(2, state.currentPlayer)
        assertEquals(13, state.players[0].concealed.sum())
        assertEquals(13, state.players[1].concealed.sum())
        assertEquals(14, state.players[2].concealed.sum())
        assertEquals(13, state.players[3].concealed.sum())

        // Extra deal tile is always wall[52] (after 4×13 = 52 draws).
        assertTrue(state.players[2].concealed[wall[52]] > 0)
    }

    @Test
    fun doesNotAliasCallerWallArray() {
        val wall = canonicalWallShuffled(Random(3))
        val original0 = wall[0]
        val state = GameState.fromWallOrder(wall)
        wall[0] = (wall[0] + 1) % NUM_TILE_TYPES
        // State still has the original tile at index 0 (copy, not reference).
        assertEquals(original0, state.wall[0])
    }

    @Test
    fun enumerateChoicesIncludesDiscards() {
        val wall = canonicalWallShuffled(Random(11))
        val state = GameState.fromWallOrder(wall, eastSeat = 0)
        val choices = state.enumerateChoices().asList()
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.any { it.kind == ChoiceKind.Discard })
    }

    @Test
    fun unknownTileCountsMatchOwnConcealedAfterDeal() {
        // Opening deal only decrements unknowns for tiles each seat received.
        val wall = canonicalWallShuffled(Random(13))
        val state = GameState.fromWallOrder(wall, eastSeat = 1)
        for (p in 0 until NUM_PLAYERS) {
            for (t in 0 until NUM_TILE_TYPES) {
                assertEquals(
                    4 - state.players[p].concealed[t],
                    state.unknownTileCountsByPlayer[p][t],
                    "seat $p tile $t",
                )
            }
        }
    }

    @Test
    fun remainingLiveWallPrefixMatchesUndealtOrder() {
        val wall = canonicalWallShuffled(Random(17))
        val state = GameState.fromWallOrder(wall, eastSeat = 0)
        // Draws consume wall[0], wall[1], … — next draw is at LIVE_WALL_DRAWABLE_SIZE - liveWallSize.
        val nextDrawIndex = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        assertEquals(53, nextDrawIndex) // 4×13 + 1 East
        assertEquals(wall[53], state.wall[nextDrawIndex])
        // Undealt live tiles still sit in the wall array in author order.
        for (i in nextDrawIndex until LIVE_WALL_DRAWABLE_SIZE) {
            assertEquals(wall[i], state.wall[i])
        }
    }

    @Test
    fun discardThenPassAdvancesWithScriptedWall() {
        val wall = canonicalWallShuffled(Random(19))
        val state = GameState.fromWallOrder(wall, eastSeat = 0)
        val discard =
            state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        while (state.decisionType == DecisionType.ReactToDiscard) {
            state.applyChoice(Choice.pass())
        }
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(1, state.currentPlayer)
        // Next seat drew wall[53] (first undealt live tile after opening).
        assertEquals(wall[53], state.lastDrawnTile)
        assertEquals(1, state.lastDrawnPlayer)
    }

    @Test
    fun rejectWrongLength() {
        assertFailsWith<IllegalArgumentException> {
            GameState.fromWallOrder(IntArray(10))
        }
    }

    @Test
    fun rejectOutOfRangeTile() {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        wall[0] = 99
        assertFailsWith<IllegalArgumentException> {
            GameState.fromWallOrder(wall)
        }
    }

    @Test
    fun rejectInvalidMultiset() {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        // Five of tile 0, three of tile 1 — still length 136.
        wall[4] = 0 // was type 1 (index 4 is second of type 1 in canonical)
        assertFailsWith<IllegalArgumentException> {
            GameState.fromWallOrder(wall)
        }
    }

    /** Canonical 4×34 multiset, Fisher–Yates with [rng]. */
    private fun canonicalWallShuffled(rng: Random): IntArray {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        for (i in WALL_SIZE - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}
