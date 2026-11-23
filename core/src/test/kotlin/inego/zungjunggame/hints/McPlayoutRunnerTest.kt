package inego.zungjunggame.hints

import inego.zungjunggame.engine.GameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McPlayoutRunnerTest {
    @Test
    fun copyFromMatchesCopy() {
        val a = GameState.generateRandom(Random(9))
        val b = GameState()
        b.copyFrom(a)
        assertEquals(a.liveWallSize, b.liveWallSize)
        assertEquals(a.currentPlayer, b.currentPlayer)
        assertEquals(a.decisionType, b.decisionType)
        assertEquals(a.handScore, b.handScore)
        for (p in 0 until 4) {
            assertTrue(a.players[p].concealed.contentEquals(b.players[p].concealed))
            assertTrue(
                a.unknownTileCountsByPlayer[p].contentEquals(b.unknownTileCountsByPlayer[p]),
            )
        }
        assertTrue(a.wall.contentEquals(b.wall))
    }
}
