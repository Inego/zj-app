package inego.zungjunggame.hints

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.DefaultEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McPlayoutRunnerTest {
    @Test
    fun playoutReachesTerminalWithScoreInRange() {
        val root = GameState.generateRandom(Random(55))
        val work = root.copy()
        UnknownTileDeterminizer().determinize(work, observerSeat = 0, rng = Random(3))

        val runner = McPlayoutRunner(DefaultEngine())
        val result = runner.runToTerminal(work, humanSeat = 0, rng = Random(4))

        assertTrue(work.isTerminal() || result.scoreDelta == 0)
        assertTrue(result.scoreDelta in -1390..1440)
        if (result.won) {
            assertTrue(result.handValue > 0)
        } else {
            assertEquals(0, result.handValue)
        }
    }

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
