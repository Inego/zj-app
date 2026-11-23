package inego.zungjunggame.engines

import inego.zungjunggame.engine.GameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** [DefaultEngine.rank] is deterministic. */
class DefaultEngineRankTest {
    @Test
    fun rankIsDeterministic() {
        val engine = DefaultEngine()
        val state = GameState.generateRandom(Random(99))
        val choices = state.enumerateChoices()
        assertTrue(!choices.isEmpty())
        val a = engine.rank(state, choices)
        val b = engine.rank(state, choices)
        assertTrue(a.contentEquals(b))
    }
}
