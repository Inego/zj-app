package inego.zungjunggame.hints

import inego.zungjunggame.engine.DEAD_WALL_SIZE
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.LIVE_WALL_DRAWABLE_SIZE
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownTileDeterminizerTest {
    @Test
    fun preservesObserverHandAndPublicMelds() {
        val state = GameState.generateRandom(Random(42), eastSeat = 0)
        // Discard one tile so rivers / reaction path exist.
        val discardType = state.players[0].concealed.indexOfFirst { it > 0 }
        state.applyChoice(inego.zungjunggame.engine.Choice.discard(discardType))

        val observer = 0
        val beforeHand = state.players[observer].concealed.copyOf()
        val beforeRivers = Array(NUM_PLAYERS) { state.discards[it].toList() }
        val beforeLastDiscarded = state.lastDiscarded
        val beforeLive = state.liveWallSize

        val det = UnknownTileDeterminizer()
        det.determinize(state, observer, Random(99))

        assertContentEquals(beforeHand, state.players[observer].concealed)
        assertEquals(beforeLastDiscarded, state.lastDiscarded)
        assertEquals(beforeLive, state.liveWallSize)
        for (p in 0 until NUM_PLAYERS) {
            assertEquals(beforeRivers[p], state.discards[p].toList())
        }
    }

    @Test
    fun preservesOpponentHandSizesAndTileMultiset() {
        val state = GameState.generateRandom(Random(7), eastSeat = 1)
        val observer = 0
        val sizesBefore = IntArray(NUM_PLAYERS) { p -> state.players[p].concealed.sum() }
        val multisetBefore = fullUnknownMultiset(state, observer)

        UnknownTileDeterminizer().determinize(state, observer, Random(123))

        for (p in 0 until NUM_PLAYERS) {
            assertEquals(sizesBefore[p], state.players[p].concealed.sum(), "seat $p size")
        }
        val multisetAfter = fullUnknownMultiset(state, observer)
        assertContentEquals(multisetBefore, multisetAfter)
    }

    @Test
    fun differentSeedsChangeWallOrHands() {
        val base = GameState.generateRandom(Random(1))
        val a = base.copy()
        val b = base.copy()
        UnknownTileDeterminizer().determinize(a, 0, Random(10))
        UnknownTileDeterminizer().determinize(b, 0, Random(11))

        var differ = false
        for (p in 1 until NUM_PLAYERS) {
            if (!a.players[p].concealed.contentEquals(b.players[p].concealed)) {
                differ = true
                break
            }
        }
        if (!differ) {
            val first = LIVE_WALL_DRAWABLE_SIZE - a.liveWallSize
            for (i in first until WALL_SIZE) {
                if (a.wall[i] != b.wall[i]) {
                    differ = true
                    break
                }
            }
        }
        assertTrue(differ, "expected different determinizations for different seeds")
    }

    private fun fullUnknownMultiset(state: GameState, observer: Int): IntArray {
        val counts = IntArray(NUM_TILE_TYPES)
        val first = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        for (i in first until WALL_SIZE) {
            counts[state.wall[i]]++
        }
        for (p in 0 until NUM_PLAYERS) {
            if (p == observer) continue
            for (t in 0 until NUM_TILE_TYPES) {
                counts[t] += state.players[p].concealed[t]
            }
        }
        // Dead wall is included via WALL_SIZE - first; sanity:
        assertEquals(state.liveWallSize + DEAD_WALL_SIZE, WALL_SIZE - first)
        return counts
    }
}
