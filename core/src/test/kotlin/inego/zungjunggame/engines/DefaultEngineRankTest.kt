package inego.zungjunggame.engines

import inego.zungjunggame.engine.GameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** [DefaultEngine.rank] co-best set is non-empty and contains every [select] pick. */
class DefaultEngineRankTest {
    @Test
    fun rankNonEmptyAndSelectIsAmongCoBest() {
        val engine = DefaultEngine()
        val rng = Random(42)
        repeat(40) {
            val state = GameState.generateRandom(Random(it * 17L + 3))
            var steps = 0
            while (!state.isTerminal() && steps < 80) {
                val choices = state.enumerateChoices()
                if (choices.isEmpty()) break
                val ranked = engine.rank(state, choices)
                assertTrue(ranked.isNotEmpty(), "rank empty at step $steps")
                for (idx in ranked) {
                    assertTrue(idx in 0 until choices.size, "rank index $idx out of range")
                }
                val decision = engine.select(state, choices, rng)
                assertContains(ranked.toList(), decision.choiceIndex)
                state.applyChoice(choices[decision.choiceIndex])
                ++steps
            }
        }
    }

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
