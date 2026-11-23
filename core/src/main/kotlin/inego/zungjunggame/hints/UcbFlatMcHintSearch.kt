package inego.zungjunggame.hints

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.DefaultEngine
import inego.zungjunggame.engines.PlayingEngine
import inego.zungjunggame.platform.newRandom
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * UCB flat (multi-armed bandit) Monte Carlo search for the HINT button.
 *
 * Lifecycle:
 * 1. [start] snapshots the live board + legal choices, spawns one daemon worker.
 * 2. Worker applies each root choice, warm-starts [INITIAL_PLAYOUTS_PER_ARM] times
 *    (or one synthetic sample for terminal roots), then forever picks the UCB1
 *    arm by average score delta and runs more playouts.
 * 3. [snapshot] returns a frozen view for the UI (safe across threads).
 * 4. [stop] requests cancel and joins the worker.
 *
 * Single worker only (no multi-core fan-out — keeps phone battery/thermals in
 * check). Pure game logic — never touches GL (only [Gdx.app]'s type, to refuse
 * Web targets).
 *
 * The worker exists only where the platform has preemptive threads: the caller
 * gates [start] on `platform.McHints`. On WasmGC `Thread.start` is a cooperative
 * fiber, and this loop has no suspension point, so a worker there freezes the
 * browser tab instead of searching — see [workerSupported], which refuses Web
 * targets even if that gate is ever opened by mistake.
 */
class UcbFlatMcHintSearch(
    private val engine: PlayingEngine = DefaultEngine(),
    private val explorationC: Double = DEFAULT_EXPLORATION_C,
    private val initialPlayoutsPerArm: Int = INITIAL_PLAYOUTS_PER_ARM,
) {
    @Volatile
    private var stopRequested: Boolean = false

    private var worker: Thread? = null

    private val publishLock = Any()
    private var published: McHintSnapshot? = null

    /** Working buffers owned by the worker thread (created on start). */
    private var choices: Array<Choice> = emptyArray()
    private var arms: Array<McHintArmStats> = emptyArray()
    private var rootAfterChoice: Array<GameState> = emptyArray()
    private var totalPlayouts: Int = 0

    val isRunning: Boolean
        get() = worker?.isAlive == true

    /**
     * Begin (or restart) search from [liveState] for [humanSeat].
     * [rootChoices] must be the current legal choices (copied immediately).
     */
    fun start(
        liveState: GameState,
        rootChoices: List<Choice>,
        humanSeat: Int,
        rngSeed: Long = newRandom().nextLong(),
    ) {
        stop()
        if (rootChoices.isEmpty() || !workerSupported()) {
            synchronized(publishLock) { published = null }
            return
        }

        stopRequested = false
        val rootCopy = liveState.copy()
        val choiceArr = rootChoices.toTypedArray()
        val armArr = Array(choiceArr.size) { McHintArmStats() }
        val afterArr = Array(choiceArr.size) { GameState() }
        val determinizer = UnknownTileDeterminizer()
        // Clone so the worker's rank scratch/cache never races the UI engine.
        val playout = McPlayoutRunner(engine.clone())
        val work = GameState()
        val rng = Random(rngSeed)

        // Prepare post-choice roots once (on caller thread — small, deterministic apply).
        val terminalScorer = McPlayoutRunner(engine)
        for (i in choiceArr.indices) {
            afterArr[i].copyFrom(rootCopy)
            afterArr[i].applyChoice(choiceArr[i])
            if (afterArr[i].isTerminal()) {
                armArr[i].terminalRoot = true
                val r = terminalScorer.terminalResult(afterArr[i], humanSeat)
                armArr[i].record(r.scoreDelta, r.won, r.handValue)
            }
        }

        choices = choiceArr
        arms = armArr
        rootAfterChoice = afterArr
        totalPlayouts = armArr.count { it.terminalRoot }
        publishSnapshot()

        worker = Thread(
            {
                runSearch(
                    humanSeat = humanSeat,
                    determinizer = determinizer,
                    playout = playout,
                    work = work,
                    rng = rng,
                )
            },
            "ucb-mc-hint",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Request stop and wait briefly for the worker to exit. */
    fun stop() {
        stopRequested = true
        val t = worker
        if (t != null) {
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            worker = null
        }
        stopRequested = false
    }

    /** Latest published stats, or null if never started / cleared. */
    fun snapshot(): McHintSnapshot? = synchronized(publishLock) { published }

    fun clearSnapshot() {
        synchronized(publishLock) { published = null }
    }

    /**
     * False on Web targets, where this loop must never run.
     *
     * The caller gates [start] on `platform.McHints`; this is the belt-and-braces
     * check behind it. On TeaVM the "thread" is a cooperative fiber (WasmGC) that
     * yields only at a suspension point, and this playout loop has none, so a
     * mistaken opt-in would freeze the browser tab. Degrading to no search costs
     * the MC labels; the classic rank highlight stays.
     */
    private fun workerSupported(): Boolean = workerSupportedOn(Gdx.app?.type)

    private fun runSearch(
        humanSeat: Int,
        determinizer: UnknownTileDeterminizer,
        playout: McPlayoutRunner,
        work: GameState,
        rng: Random,
    ) {
        val nArms = arms.size
        if (nArms == 0) return

        // Warm-start: initial playouts per non-terminal arm.
        for (i in 0 until nArms) {
            if (stopRequested) break
            if (arms[i].terminalRoot) continue
            repeat(initialPlayoutsPerArm) {
                if (stopRequested) return
                runOnePlayout(i, humanSeat, determinizer, playout, work, rng)
            }
        }
        publishSnapshot()
        var lastPublishNs = System.nanoTime()

        // UCB flat: always sample the non-terminal arm with highest UCB score.
        // Terminal roots keep their single exact sample and still compete on avgΔ.
        while (!stopRequested) {
            val arm = selectUcbArm()
            if (arm < 0) break
            runOnePlayout(arm, humanSeat, determinizer, playout, work, rng)
            // Throttle by wall clock, not playout count: a fixed "every N playouts"
            // is meaningless across desktop vs phone throughput. UI only reads ~1 Hz.
            val now = System.nanoTime()
            if (now - lastPublishNs >= PUBLISH_MIN_INTERVAL_NS) {
                publishSnapshot()
                lastPublishNs = now
            }
        }
        publishSnapshot()
    }

    private fun runOnePlayout(
        armIndex: Int,
        humanSeat: Int,
        determinizer: UnknownTileDeterminizer,
        playout: McPlayoutRunner,
        work: GameState,
        rng: Random,
    ) {
        work.copyFrom(rootAfterChoice[armIndex])
        determinizer.determinize(work, humanSeat, rng)
        val r = playout.runToTerminal(work, humanSeat)
        arms[armIndex].record(r.scoreDelta, r.won, r.handValue)
        totalPlayouts++
    }

    /**
     * UCB1: avgDelta + C * sqrt(ln(N) / n). Unsampled arms first.
     * Terminal roots are skipped (exact sample already recorded).
     */
    private fun selectUcbArm(): Int {
        val nArms = arms.size
        val nTotal = totalPlayouts.coerceAtLeast(1)
        val logN = ln(nTotal.toDouble())

        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until nArms) {
            val arm = arms[i]
            if (arm.terminalRoot) continue
            val n = arm.n
            val score = if (n == 0) {
                Double.POSITIVE_INFINITY
            } else {
                arm.avgDelta() + explorationC * sqrt(logN / n)
            }
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun publishSnapshot() {
        val armSnaps = List(arms.size) { i ->
            val a = arms[i]
            McHintArmSnapshot(
                choice = choices[i],
                n = a.n,
                avgDelta = a.avgDelta(),
                winRate = a.winRate(),
                avgHandValueWon = a.avgHandValueWon(),
                terminalRoot = a.terminalRoot,
            )
        }
        val best = computeBestIndices(arms)
        val snap = McHintSnapshot(
            arms = armSnaps,
            totalPlayouts = totalPlayouts,
            bestIndices = best,
        )
        synchronized(publishLock) {
            published = snap
        }
    }

    companion object {
        /**
         * Half the documented ZJ score-delta span (−1390…+1440 → 2830 / 2).
         * Classic UCB1 exploration scale when rewards are un-normalized.
         */
        const val DEFAULT_EXPLORATION_C: Double = 1415.0

        const val INITIAL_PLAYOUTS_PER_ARM: Int = 10

        private const val JOIN_TIMEOUT_MS = 2_000L

        /**
         * Min wall-clock gap between snapshot publishes.
         * Snapshot build allocates; doing it after every playout steals time from
         * search on every device. UI refreshes ~1×/s ([Main] MC_HINT_UI_REFRESH_SEC),
         * so a few hundred ms keeps labels fresh without coupling to playouts/sec.
         */
        private const val PUBLISH_MIN_INTERVAL_NS = 200_000_000L // 200 ms

        /**
         * Whether a playout worker may run on a platform whose libGDX app type is
         * [appType]. Web (TeaVM js / wasm) is the one that must not: see
         * [workerSupported]. A null type is a headless test JVM, which does have
         * preemptive threads.
         */
        internal fun workerSupportedOn(appType: ApplicationType?): Boolean =
            appType != ApplicationType.WebGL

        /**
         * Indices sharing the maximum average delta (exact rational compare).
         * Empty when every arm has n == 0.
         */
        fun computeBestIndices(arms: Array<McHintArmStats>): IntArray {
            var bestI = -1
            val tied = ArrayList<Int>(4)
            for (i in arms.indices) {
                val a = arms[i]
                if (a.n == 0) continue
                if (bestI < 0) {
                    bestI = i
                    tied.clear()
                    tied.add(i)
                    continue
                }
                val b = arms[bestI]
                // compare a.sum/a.n vs b.sum/b.n via cross-multiply
                val cmp = a.sumDelta * b.n.toLong() - b.sumDelta * a.n.toLong()
                when {
                    cmp > 0L -> {
                        bestI = i
                        tied.clear()
                        tied.add(i)
                    }
                    cmp == 0L -> tied.add(i)
                }
            }
            return tied.toIntArray()
        }
    }
}
