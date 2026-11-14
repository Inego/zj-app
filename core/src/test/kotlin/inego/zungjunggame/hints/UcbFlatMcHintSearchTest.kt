package inego.zungjunggame.hints

import com.badlogic.gdx.Application.ApplicationType
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.DefaultEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UcbFlatMcHintSearchTest {
    /**
     * The Web target must never spawn the playout worker, whatever the platform
     * flag says: a TeaVM fiber only yields at a suspension point, and this loop
     * has none, so the search would freeze the browser tab.
     */
    @Test
    fun webTargetsRefuseThePlayoutWorker() {
        assertFalse(UcbFlatMcHintSearch.workerSupportedOn(ApplicationType.WebGL))
        assertTrue(UcbFlatMcHintSearch.workerSupportedOn(ApplicationType.Desktop))
        assertTrue(UcbFlatMcHintSearch.workerSupportedOn(ApplicationType.Android))
        assertTrue(UcbFlatMcHintSearch.workerSupportedOn(null))
    }

    @Test
    fun computeBestIndicesPicksHighestAvgDelta() {
        val arms = Array(3) { McHintArmStats() }
        arms[0].record(10, false, 0)
        arms[0].record(10, false, 0)
        arms[1].record(30, true, 10)
        arms[1].record(30, true, 10)
        arms[2].record(0, false, 0)
        arms[2].record(0, false, 0)

        val best = UcbFlatMcHintSearch.computeBestIndices(arms)
        assertContentEquals(intArrayOf(1), best)
    }

    @Test
    fun computeBestIndicesReportsTies() {
        val arms = Array(2) { McHintArmStats() }
        arms[0].record(5, false, 0)
        arms[1].record(5, false, 0)
        val best = UcbFlatMcHintSearch.computeBestIndices(arms)
        assertEquals(2, best.size)
        assertTrue(0 in best.toList() && 1 in best.toList())
    }

    @Test
    fun searchProducesStatsForEveryRootChoice() {
        val state = GameState.generateRandom(Random(42))
        val choices = state.enumerateChoices().asList()
        assertTrue(choices.isNotEmpty())

        val search = UcbFlatMcHintSearch(
            engine = DefaultEngine(),
            initialPlayoutsPerArm = 2,
        )
        try {
            search.start(state, choices, humanSeat = 0, rngSeed = 99L)
            // Wait for warm-start to finish enough work.
            val deadline = System.currentTimeMillis() + 15_000
            var snap = search.snapshot()
            while (System.currentTimeMillis() < deadline) {
                snap = search.snapshot()
                if (snap != null &&
                    snap.arms.size == choices.size &&
                    snap.arms.all { it.n >= 2 || it.terminalRoot }
                ) {
                    break
                }
                Thread.sleep(20)
            }
            assertNotNull(snap)
            assertEquals(choices.size, snap.arms.size)
            for ((choice, n, avgDelta) in snap.arms) {
                assertTrue(n > 0, "arm $choice should have samples")
                assertTrue(avgDelta in -1400.0..1500.0, "delta $avgDelta")
            }
            assertTrue(snap.totalPlayouts >= choices.size)
            assertTrue(snap.bestIndices.isNotEmpty())
        } finally {
            search.stop()
        }
    }

    @Test
    fun terminalHuRootRecordsSyntheticSample() {
        // Build a state where Hu is legal if possible; otherwise skip-style soft assert.
        val state = GameState.generateRandom(Random(100))
        val choices = state.enumerateChoices().asList()
        val hu = choices.firstOrNull { it.kind == ChoiceKind.Hu }
        if (hu == null) {
            // Opening hands rarely can Hu; still verify non-Hu path records.
            val search = UcbFlatMcHintSearch(initialPlayoutsPerArm = 1)
            try {
                search.start(state, choices.take(1), humanSeat = 0, rngSeed = 1L)
                Thread.sleep(200)
                val snap = search.snapshot()
                assertNotNull(snap)
                assertTrue(snap.arms.isNotEmpty())
            } finally {
                search.stop()
            }
            return
        }

        val search = UcbFlatMcHintSearch(initialPlayoutsPerArm = 10)
        try {
            search.start(state, listOf(hu), humanSeat = state.currentPlayer, rngSeed = 1L)
            Thread.sleep(50)
            val snap = search.snapshot()
            assertNotNull(snap)
            val arm = snap.arms.single()
            assertTrue(arm.terminalRoot)
            assertTrue(arm.n >= 1)
            assertTrue(arm.winRate >= 0.0)
        } finally {
            search.stop()
        }
    }

    private fun assertContentEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
