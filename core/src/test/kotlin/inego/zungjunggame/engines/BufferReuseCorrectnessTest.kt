package inego.zungjunggame.engines

import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DeficiencyFast
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.hints.McPlayoutRunner
import inego.zungjunggame.hints.UnknownTileDeterminizer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the MC-hot-path buffer reuse optimizations:
 * - [DeficiencyFast] ThreadLocal scratch
 * - [DefaultEngine] instance rankCache / trialHand / extrasOut / kongWork
 * - [McPlayoutRunner] ChoiceList reuse + [selectIndex]
 */
class BufferReuseCorrectnessTest {

    @Test
    fun deficiencyFastRepeatedCallsAreStable() {
        val hands = listOf(
            handOf(
                Tiles.CHAR_1, Tiles.CHAR_1, Tiles.CHAR_1,
                Tiles.CHAR_2, Tiles.CHAR_3, Tiles.CHAR_4,
                Tiles.DOT_5, Tiles.DOT_5,
                Tiles.BAMBOO_7, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
                Tiles.EAST, Tiles.EAST, Tiles.RED,
            ),
            handOf(
                Tiles.CHAR_1, Tiles.CHAR_9, Tiles.DOT_1, Tiles.DOT_9,
                Tiles.BAMBOO_1, Tiles.BAMBOO_9,
                Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH,
                Tiles.RED, Tiles.GREEN, Tiles.WHITE, Tiles.WHITE,
            ),
            handOf(
                Tiles.CHAR_2, Tiles.CHAR_2, Tiles.CHAR_4, Tiles.CHAR_6, Tiles.CHAR_8,
                Tiles.DOT_2, Tiles.DOT_2, Tiles.DOT_4, Tiles.DOT_6,
                Tiles.BAMBOO_2, Tiles.BAMBOO_4,
                Tiles.SOUTH, Tiles.RED, Tiles.WHITE,
            ),
        )

        for (hand in hands) {
            val first = DeficiencyFast.handDeficiencyFast(hand)
            repeat(64) {
                assertEquals(first, DeficiencyFast.handDeficiencyFast(hand), "def changed under reuse")
            }

            val remaining = IntArray(NUM_TILE_TYPES) { 4 }
            for (t in 0 until NUM_TILE_TYPES) remaining[t] -= hand.concealed[t]
            val base = first.coerceAtLeast(0)
            val acc0 = DeficiencyFast.acceptanceCountFast(hand, remaining, base)
            repeat(32) {
                assertEquals(
                    acc0,
                    DeficiencyFast.acceptanceCountFast(hand, remaining, base),
                    "acceptance changed under reuse",
                )
            }
        }
    }

    @Test
    fun deficiencyFastIndependentHandsDoNotBleed() {
        val a = handOf(
            Tiles.CHAR_1, Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
            Tiles.DOT_1, Tiles.DOT_2, Tiles.DOT_3,
            Tiles.BAMBOO_5, Tiles.BAMBOO_5, Tiles.BAMBOO_5,
            Tiles.EAST, Tiles.EAST, Tiles.EAST, Tiles.RED,
        )
        val b = handOf(
            Tiles.CHAR_9, Tiles.CHAR_9,
            Tiles.DOT_7, Tiles.DOT_8, Tiles.DOT_9,
            Tiles.BAMBOO_1, Tiles.BAMBOO_1, Tiles.BAMBOO_1,
            Tiles.SOUTH, Tiles.SOUTH, Tiles.WEST, Tiles.WEST,
            Tiles.GREEN, Tiles.WHITE,
        )
        val defA = DeficiencyFast.handDeficiencyFast(a)
        val defB = DeficiencyFast.handDeficiencyFast(b)
        // Interleave many times — ThreadLocal scratch must not mix hands.
        repeat(40) {
            assertEquals(defA, DeficiencyFast.handDeficiencyFast(a))
            assertEquals(defB, DeficiencyFast.handDeficiencyFast(b))
        }
        assertTrue(defA != defB || a.concealed.contentEquals(b.concealed).not())
    }

    @Test
    fun defaultEngineRankStableAfterManyCallsOnSameInstance() {
        val engine = DefaultEngine()
        val states = (0 until 20).map { GameState.generateRandom(Random(it * 31L + 7)) }
        val expected = states.map { st ->
            val ch = st.enumerateChoices()
            if (ch.isEmpty()) intArrayOf() else engine.rank(st, ch).copyOf()
        }
        // Second pass: same engine instance (rankCache / trialHand / extrasOut reused).
        for (i in states.indices) {
            val ch = states[i].enumerateChoices()
            if (ch.isEmpty()) {
                assertTrue(expected[i].isEmpty())
                continue
            }
            val again = engine.rank(states[i], ch)
            assertContentEquals(expected[i], again, "rank drifted on reused engine state $i")
        }
        // Fresh engine must match reused engine.
        val fresh = DefaultEngine()
        for (i in states.indices) {
            val ch = states[i].enumerateChoices()
            if (ch.isEmpty()) continue
            assertContentEquals(expected[i], fresh.rank(states[i], ch), "fresh vs reused mismatch $i")
        }
    }

    @Test
    fun selectIndexMatchesSelectChoiceIndex() {
        val engine = DefaultEngine()
        val rngA = Random(12345)
        val rngB = Random(12345)
        repeat(30) {
            val state = GameState.generateRandom(Random(it * 19L + 1))
            var steps = 0
            while (!state.isTerminal() && steps < 40) {
                val choices = state.enumerateChoices()
                if (choices.isEmpty()) break
                val viaSelect = engine.select(state, choices, rngA).choiceIndex
                val viaIndex = engine.selectIndex(state, choices, rngB)
                assertEquals(viaSelect, viaIndex, "select/selectIndex diverge at step $steps")
                state.applyChoice(choices[viaIndex])
                ++steps
            }
        }
    }

    @Test
    fun choiceListReuseEnumerateMatchesFreshList() {
        val reused = ChoiceList()
        repeat(50) {
            val state = GameState.generateRandom(Random(it * 41L + 9))
            val fresh = state.enumerateChoices()
            state.enumerateChoicesInto(reused)
            assertEquals(fresh.size, reused.size)
            for ((i, element) in fresh.withIndex()) {
                assertEquals(element, reused[i], "stale ChoiceList entry at $i after clear")
            }
        }
    }

    @Test
    fun mcPlayoutRunnerReuseDoesNotCorruptScores() {
        val engine = DefaultEngine()
        val runner = McPlayoutRunner(engine)
        val determinizer = UnknownTileDeterminizer()
        val scores = ArrayList<Int>()
        repeat(12) {
            val root = GameState.generateRandom(Random(1000L + it))
            val work = root.copy()
            determinizer.determinize(work, observerSeat = 0, rng = Random(2000L + it))
            val result = runner.runToTerminal(work, humanSeat = 0, rng = Random(3000L + it))
            assertTrue(result.scoreDelta in -1390..1440)
            scores.add(result.scoreDelta)
        }
        // Second runner (fresh ChoiceList) with identical seeds must match first runner's outcomes
        // only when starting from the same determinized state — re-run pairwise.
        val runner2 = McPlayoutRunner(DefaultEngine())
        for (it in 0 until 12) {
            val root = GameState.generateRandom(Random(1000L + it))
            val w1 = root.copy()
            val w2 = root.copy()
            determinizer.determinize(w1, 0, Random(2000L + it))
            determinizer.determinize(w2, 0, Random(2000L + it))
            val r1 = runner.runToTerminal(w1, 0, Random(3000L + it))
            val r2 = runner2.runToTerminal(w2, 0, Random(3000L + it))
            assertEquals(r1.scoreDelta, r2.scoreDelta, "playout $it score mismatch under ChoiceList reuse")
            assertEquals(r1.won, r2.won)
            assertEquals(r1.handValue, r2.handValue)
        }
    }

    @Test
    fun cloneEngineRanksIndependentlyFromParent() {
        val parent = DefaultEngine()
        val child = parent.clone() as DefaultEngine
        val state = GameState.generateRandom(Random(77))
        val choices = state.enumerateChoices()
        assertTrue(!choices.isEmpty())
        // Warm parent scratch/cache heavily.
        repeat(20) {
            parent.rank(state, choices)
        }
        val fromChild = child.rank(state, choices)
        val fromParent = parent.rank(state, choices)
        assertContentEquals(fromParent, fromChild)
        val fromFresh = DefaultEngine().rank(state, choices)
        assertContentEquals(fromFresh, fromChild)
    }

    private fun handOf(vararg tiles: Int): PlayerState {
        val ps = PlayerState()
        for (t in tiles) ps.concealed[t]++
        ps.activeConcealedTileCount = -1
        return ps
    }
}
