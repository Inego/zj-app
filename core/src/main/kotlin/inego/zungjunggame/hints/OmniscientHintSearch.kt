package inego.zungjunggame.hints

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.Deficiency
import inego.zungjunggame.engine.DeficiencyFast
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Scoring
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engines.DefaultEngine
import inego.zungjunggame.engines.PlayingEngine
import inego.zungjunggame.platform.SliceScheduler
import inego.zungjunggame.platform.newRandom
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** One declared meld in a winning hand snapshot for [OmniscientHintArmSnapshot]. */
data class OmniscientWinningMeld(
    /** Tile types in ascending display order (3 for chow/pung, 4 for kong). */
    val tiles: List<Int>,
    /** True for a concealed kong (outer two tiles rendered face-down, matching the table). */
    val concealedKong: Boolean = false,
)

/**
 * Immutable snapshot of the human seat's winning hand at the terminal state of
 * an arm's best known line.
 */
data class OmniscientWinningHand(
    /** Standing concealed tiles in ascending tile-type order, excluding [winningTile] when separated. */
    val concealedTiles: List<Int>,
    /** Declared melds (open chows/pungs/kongs and concealed kongs) in exposure order. */
    val melds: List<OmniscientWinningMeld>,
    /** Completing tile (self-drawn or claimed discard), or `-1` if not uniquely identified. */
    val winningTile: Int = -1,
    /** Absolute seat that supplied [winningTile] on a discard/rob win, or `-1` for self-draw. */
    val winSourceSeat: Int = -1,
)

/** Immutable per-option snapshot produced by [OmniscientHintSearch]. */
data class OmniscientHintArmSnapshot(
    val choice: Choice,
    /**
     * Best known terminal outcome for the human seat under this option:
     * - `> 0`: highest Zung Jung winning hand score for the human seat,
     * - `0`: exhaustive draw when [hasOutcome] is true, or unprobed when [hasOutcome] is false,
     * - `< 0`: smallest loss (signed Zung Jung payment delta for the human seat, e.g. `-10`, `-25`).
     */
    val bestScore: Int,
    /** Fewest human choices (including this root option) to reach [bestScore], or 0 when unprobed. */
    val bestSteps: Int = 0,
    /** Full pattern breakdown for a human win (`bestScore > 0`), or null on loss/draw/unprobed. */
    val bestDetails: HandScoreDetails?,
    /** Winning hand tiles for a human win (`bestScore > 0`), or null on loss/draw/unprobed. */
    val bestWinningHand: OmniscientWinningHand? = null,
    val playouts: Int,
    /** True when every branch under this root option has been proven exact. */
    val solved: Boolean,
    /** True once at least one terminal outcome (win, draw, or loss) has been observed. */
    val hasOutcome: Boolean = playouts > 0 || solved,
    /** Absolute seat that won in the best outcome (`humanSeat` on win, opponent seat on loss, `-1` on draw/unprobed). */
    val bestWinnerSeat: Int = -1,
    /** Absolute discarder seat for a discard/rob win in the best outcome, or `-1` for self-draw/draw/unprobed. */
    val bestWinSourceSeat: Int = -1,
    /** Winning opponent's total hand score when [bestScore] `< 0`, or `0` otherwise. */
    val bestOpponentHandScore: Int = 0,
)

/** Immutable full search snapshot published by [OmniscientHintSearch] to the UI thread. */
data class OmniscientHintSnapshot(
    val arms: List<OmniscientHintArmSnapshot>,
    val totalPlayouts: Int,
    /**
     * Indices into [arms] sharing the best [OmniscientHintArmSnapshot.bestScore]
     * (highest win `> 0`, then draw `0`, then smallest loss `< 0`),
     * tie-broken by the fewest [OmniscientHintArmSnapshot.bestSteps].
     */
    val bestIndices: IntArray,
    /** Monotonic revision incremented on each publish so the UI thread detects updates cleanly. */
    val revision: Long = 0L,
    /** Live search throughput in playout evaluations per second for the current turn (0 if not yet measured). */
    val evalsPerSec: Float = 0f,
) {
    fun bestChoices(): List<Choice> = bestIndices.map { arms[it].choice }

    fun armFor(choice: Choice): OmniscientHintArmSnapshot? =
        arms.firstOrNull { it.choice.kind == choice.kind && it.choice.payload == choice.payload }

    fun armForDiscard(tileType: Int): OmniscientHintArmSnapshot? =
        arms.firstOrNull { it.choice.kind == ChoiceKind.Discard && it.choice.payload == tileType }
}

/**
 * Perfect-information "Omniscient" hint solver for local play.
 *
 * Uses Root-Fair Single-Player MCTS (SP-MCTS) with:
 * - Macro-step compression (tree nodes exist only at non-trivial human choices;
 *   deterministic opponent choices along each edge are cached and replayed
 *   directly via [GameState.applyChoice]).
 * - Instant Phase 0 warm-start (1 `DefaultEngine` baseline + 1 probe per
 *   feasible Pattern Seeker per root option) so useful scores appear well
 *   before the first 1 s tick.
 * - Score-hunting rollout portfolio (score-pushing `DefaultEngine` that
 *   declines low `Hu` below the current best, goal-directed Pattern Seekers,
 *   and exploratory playouts).
 * - Cross-turn tree reuse: because opponents are deterministic, the next human
 *   turn matches one of the previous root arm's post-macro states and inherits
 *   its explored subtree, best scores, breakdowns, and `solved` flags.
 *
 * Execution model:
 * - Desktop & Android ([workerSupported] == true): single background daemon
 *   worker, publishing at Phase 0 and ~1 Hz and waking the UI via [onPublish].
 * - Web / WasmGC ([workerSupported] == false): cooperative frame-sliced mode —
 *   no thread is spawned; the caller advances [stepFrameSlice] for a short
 *   per-frame budget (~10 ms) so the browser tab stays responsive.
 */
class OmniscientHintSearch(
    /**
     * Optional wake callback invoked right after a fresh snapshot is published
     * (e.g. `Gdx.graphics?.requestRendering()` so non-continuous rendering
     * repaints at 1 Hz without busy-spinning).
     */
    private val onPublish: () -> Unit = {},
) {
    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var hoveredChoice: Choice? = null

    private var worker: Thread? = null

    /** Active session when running in cooperative sliced mode (Web/WasmGC). */
    private var slicedSession: SearchSession? = null

    /** Cancellation handle when [slicedSession] is pumped via [SliceScheduler]. */
    private var cancelSliceLoop: (() -> Unit)? = null

    private val publishLock = Any()
    private var published: OmniscientHintSnapshot? = null
    private var publishRevision: Long = 0L

    /** Tree retained from the last finished/stopped turn for 0 ms cross-turn re-rooting. */
    private var retainedTurn: RetainedTurn? = null

    val isRunning: Boolean
        get() = worker?.isAlive == true || (slicedSession?.allSolved == false)

    /**
     * Hint the solver that the player is currently hovering [choice] so its root
     * arm receives extra search samples. Safe to call from the UI thread.
     */
    fun setHoveredChoice(choice: Choice?) {
        hoveredChoice = choice
    }

    /**
     * Begin (or restart) omniscient search from [liveState] for [humanSeat] against
     * deterministic [opponentEngine].
     */
    fun start(
        liveState: GameState,
        rootChoices: List<Choice>,
        humanSeat: Int,
        opponentEngine: PlayingEngine,
        rngSeed: Long = newRandom().nextLong(),
    ) {
        stop()
        if (rootChoices.isEmpty()) {
            synchronized(publishLock) { published = null }
            return
        }

        stopRequested = false
        val rootCopy = liveState.copy()
        val choiceArr = rootChoices.toTypedArray()
        val oppClone = opponentEngine.clone()
        val oppName = opponentEngine.name()
        val reused = tryReuseSubtree(retainedTurn, rootCopy, choiceArr, humanSeat, oppName, opponentEngine)

        val armNodes: Array<Node>
        val armStates: Array<GameState>
        if (reused != null) {
            armNodes = reused.armNodes
            armStates = reused.armStates
        } else {
            val initOpp = opponentEngine.clone()
            val tmpChoices = ChoiceList()
            val tapeBuf = ArrayList<Choice>(32)
            armNodes = Array(choiceArr.size) { Node(choiceArr[it]) }
            armStates = Array(choiceArr.size) { GameState() }
            for (i in choiceArr.indices) {
                armStates[i].copyFrom(rootCopy)
                val tape = macroAdvance(armStates[i], choiceArr[i], humanSeat, initOpp, tmpChoices, tapeBuf)
                val arm = armNodes[i]
                arm.macroChoices = tape
                if (armStates[i].isTerminal()) {
                    initTerminalNode(arm, armStates[i], humanSeat)
                }
            }
        }

        retainedTurn = RetainedTurn(
            humanSeat = humanSeat,
            opponentEngineName = oppName,
            armNodes = armNodes,
            armStates = armStates,
        )

        publishSnapshot(armNodes)

        val session = SearchSession(
            rootCopy = rootCopy,
            armNodes = armNodes,
            armStates = armStates,
            humanSeat = humanSeat,
            opponentEngine = oppClone,
            rng = Random(rngSeed),
        )

        if (workerSupported()) {
            worker = Thread(
                { runWorkerSession(session) },
                "omniscient-hint",
            ).apply {
                isDaemon = true
                start()
            }
        } else {
            slicedSession = session
            val startLoop = SliceScheduler.startLoop
            if (startLoop != null) {
                cancelSliceLoop = startLoop { advanceSlice(FRAME_SLICE_BUDGET_NS) }
            }
        }
    }

    /**
     * Advance the cooperative frame-sliced search when no [SliceScheduler] loop
     * is installed (fallback render-loop mode). Returns `false` immediately when
     * slices are already driven by [SliceScheduler] so the render loop stays idle
     * between 1 Hz snapshot publishes.
     */
    fun stepFrameSlice(budgetNs: Long = FRAME_SLICE_BUDGET_NS): Boolean {
        if (cancelSliceLoop != null) return false
        return advanceSlice(budgetNs)
    }

    private fun advanceSlice(budgetNs: Long): Boolean {
        val session = slicedSession ?: return false
        if (session.allSolved) {
            slicedSession = null
            cancelSliceLoop = null
            return false
        }
        val deadline = System.nanoTime() + budgetNs
        do {
            if (!session.stepOne()) break
        } while (System.nanoTime() < deadline)

        val now = System.nanoTime()
        if (session.allSolved) {
            publishSnapshot(session.armNodes, session.currentEvalsPerSec(now))
            slicedSession = null
            cancelSliceLoop = null
            return false
        }
        if (now - session.lastPublishNs >= PUBLISH_MIN_INTERVAL_NS) {
            publishSnapshot(session.armNodes, session.currentEvalsPerSec(now))
            session.lastPublishNs = now
        }
        return true
    }

    /** Request stop and join briefly. Retains the tree for possible cross-turn reuse. */
    fun stop() {
        stopRequested = true
        cancelSliceLoop?.invoke()
        cancelSliceLoop = null
        slicedSession = null
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

    /** Stop and discard any retained search tree + published snapshot (e.g. new deal / exit). */
    fun reset() {
        stop()
        retainedTurn = null
        hoveredChoice = null
        synchronized(publishLock) { published = null }
    }

    fun snapshot(): OmniscientHintSnapshot? = synchronized(publishLock) { published }

    fun clearSnapshot() {
        synchronized(publishLock) { published = null }
    }

    private fun workerSupported(): Boolean = workerSupportedOn(Gdx.app?.type)

    // ── Shared single-step search session (threaded & frame-sliced) ─────────

    private fun runWorkerSession(session: SearchSession) {
        while (!stopRequested && session.stepOne()) {
            val now = System.nanoTime()
            if (now - session.lastPublishNs >= PUBLISH_MIN_INTERVAL_NS) {
                publishSnapshot(session.armNodes, session.currentEvalsPerSec(now))
                session.lastPublishNs = now
            }
        }
        publishSnapshot(session.armNodes, session.currentEvalsPerSec(System.nanoTime()))
    }

    private inner class SearchSession(
        rootCopy: GameState,
        val armNodes: Array<Node>,
        private val armStates: Array<GameState>,
        private val humanSeat: Int,
        private val opponentEngine: PlayingEngine,
        private val rng: Random,
    ) {
        private val humanEngine = DefaultEngine()
        private val tmpChoices = ChoiceList()
        private val tmpChoices2 = ChoiceList()
        private val tapeBuf = ArrayList<Choice>(32)
        private val work = GameState()
        private val liveCounts = IntArray(NUM_TILE_TYPES)
        private val seekers = detectFeasibleSeekers(rootCopy, humanSeat, liveCounts)
        private val path = ArrayList<Node>(20)

        private var phase0ArmIdx: Int = 0
        /** `-1` runs the deterministic DefaultEngine baseline; `0 until seekers.size` runs each seeker. */
        private var phase0SeekerIdx: Int = -1
        private var phase0Done: Boolean = armNodes.isEmpty()

        private var armCursor: Int = 0
        private var iter: Int = 0
        var allSolved: Boolean = armNodes.isEmpty()
            private set

        var lastPublishNs: Long = System.nanoTime()
        private val sessionStartNs: Long = lastPublishNs
        private var sessionPlayouts: Int = 0
        private var lastRateNs: Long = lastPublishNs
        private var lastRatePlayouts: Int = 0
        private var lastMeasuredRate: Float = 0f

        fun currentEvalsPerSec(nowNs: Long): Float {
            val deltaNs = nowNs - lastRateNs
            val deltaPlayouts = sessionPlayouts - lastRatePlayouts
            if (deltaNs >= RATE_WINDOW_MIN_NS && deltaPlayouts > 0) {
                lastMeasuredRate = deltaPlayouts.toFloat() * 1_000_000_000f / deltaNs.toFloat()
                lastRateNs = nowNs
                lastRatePlayouts = sessionPlayouts
            } else if (lastMeasuredRate == 0f && sessionPlayouts > 0) {
                val totalNs = nowNs - sessionStartNs
                if (totalNs >= 1_000_000L) {
                    lastMeasuredRate = sessionPlayouts.toFloat() * 1_000_000_000f / totalNs.toFloat()
                }
            }
            return lastMeasuredRate
        }

        /** Run one probe (Phase 0) or one SP-MCTS iteration. Returns false once all arms are solved. */
        fun stepOne(): Boolean {
            if (allSolved) return false
            if (!phase0Done) {
                if (stepPhase0()) return true
                // Phase 0 just finished; publish immediately so baseline scores appear before the first 1 s tick.
                val now = System.nanoTime()
                publishSnapshot(armNodes, currentEvalsPerSec(now))
                lastPublishNs = now
            }
            return stepMcts()
        }

        private fun stepPhase0(): Boolean {
            val nArms = armNodes.size
            while (phase0ArmIdx < nArms) {
                val arm = armNodes[phase0ArmIdx]
                if (arm.solved || (phase0SeekerIdx == -1 && arm.playouts > 0)) {
                    phase0ArmIdx++
                    phase0SeekerIdx = -1
                    continue
                }
                work.copyFrom(armStates[phase0ArmIdx])
                if (phase0SeekerIdx == -1) {
                    val basePacked = runPlayout(
                        state = work,
                        humanSeat = humanSeat,
                        opponentEngine = opponentEngine,
                        humanEngine = humanEngine,
                        tmpChoices = tmpChoices,
                        seeker = null,
                        minTargetScore = 0,
                        minTargetSteps = Int.MAX_VALUE,
                        initialSteps = 1,
                        eps = 0.0,
                        skipLowHuProb = 0.0,
                        rng = rng,
                    )
                    recordRootProbe(arm, unpackScore(basePacked), unpackSteps(basePacked), work, humanSeat)
                    sessionPlayouts++
                    phase0SeekerIdx = 0
                    if (seekers.isEmpty()) {
                        phase0ArmIdx++
                        phase0SeekerIdx = -1
                    }
                    return true
                }
                if (phase0SeekerIdx < seekers.size) {
                    val targetWin = if (arm.bestScore > 0) arm.bestScore else 0
                    val targetSteps = if (arm.bestScore > 0) arm.bestSteps else Int.MAX_VALUE
                    val packed = runPlayout(
                        state = work,
                        humanSeat = humanSeat,
                        opponentEngine = opponentEngine,
                        humanEngine = humanEngine,
                        tmpChoices = tmpChoices,
                        seeker = seekers[phase0SeekerIdx],
                        minTargetScore = targetWin,
                        minTargetSteps = targetSteps,
                        initialSteps = 1,
                        eps = 0.0,
                        skipLowHuProb = 1.0,
                        rng = rng,
                    )
                    recordRootProbe(arm, unpackScore(packed), unpackSteps(packed), work, humanSeat)
                    sessionPlayouts++
                    phase0SeekerIdx++
                    if (phase0SeekerIdx >= seekers.size) {
                        phase0ArmIdx++
                        phase0SeekerIdx = -1
                    }
                    return true
                }
                phase0ArmIdx++
                phase0SeekerIdx = -1
            }
            phase0Done = true
            return false
        }

        private fun stepMcts(): Boolean {
            val nArms = armNodes.size
            val curIter = iter++
            val bonusHoverIdx = if (curIter % 3 == 0) hoveredArmIndex(armNodes) else -1
            val armIdx = if (bonusHoverIdx >= 0) {
                bonusHoverIdx
            } else {
                pickRoundRobinArm(armNodes, armCursor)
            }
            if (armIdx < 0) {
                allSolved = true
                return false
            }
            if (bonusHoverIdx < 0) {
                armCursor = (armIdx + 1) % nArms
            }

            path.clear()
            var cur = armNodes[armIdx]
            path.add(cur)
            work.copyFrom(armStates[armIdx])
            val scale = max(if (cur.bestScore == NO_OUTCOME) 0.0 else abs(cur.bestScore).toDouble(), 100.0)

            while (!cur.solved) {
                if (!cur.expanded) {
                    initNodeChildren(cur, work, humanEngine, tmpChoices2)
                    if (cur.solved) break
                }
                val children = cur.children ?: break
                val childChoices = cur.childChoices ?: break
                if (cur.expandedCount < children.size) {
                    val idx = cur.expandedCount++
                    val c = childChoices[idx]
                    val child = Node(c)
                    child.macroChoices = macroAdvance(work, c, humanSeat, opponentEngine, tmpChoices, tapeBuf)
                    if (work.isTerminal()) {
                        child.terminal = true
                        child.solved = true
                    }
                    children[idx] = child
                    path.add(child)
                    cur = child
                    break
                }
                val nextIdx = selectChildSpMcts(cur, scale)
                if (nextIdx < 0) {
                    cur.solved = true
                    break
                }
                val next = children[nextIdx] ?: break
                next.macroChoices?.let { replayTape(work, it) }
                path.add(next)
                cur = next
            }

            // Each node along [path] represents one human decision (root arm = 1, child = 2, ...).
            val stepsInTree = path.size
            val outcomeScore: Int
            val totalSteps: Int
            if (work.isTerminal()) {
                outcomeScore = terminalHumanOutcome(work, humanSeat)
                totalSteps = stepsInTree
            } else {
                val r = rng.nextDouble()
                val rootBest = armNodes[armIdx].bestScore
                val targetScore = if (rootBest > 0) rootBest else 0
                val targetSteps = if (rootBest > 0) armNodes[armIdx].bestSteps else Int.MAX_VALUE
                val packed = when {
                    seekers.isNotEmpty() && r < 0.35 -> {
                        val sk = seekers[rng.nextInt(seekers.size)]
                        runPlayout(work, humanSeat, opponentEngine, humanEngine, tmpChoices, sk, targetScore, targetSteps, stepsInTree, 0.12, 1.0, rng)
                    }
                    r < 0.80 -> {
                        runPlayout(work, humanSeat, opponentEngine, humanEngine, tmpChoices, null, targetScore, targetSteps, stepsInTree, 0.20, 0.75, rng)
                    }
                    else -> {
                        runPlayout(work, humanSeat, opponentEngine, humanEngine, tmpChoices, null, targetScore, targetSteps, stepsInTree, 0.35, 0.85, rng)
                    }
                }
                outcomeScore = unpackScore(packed)
                totalSteps = unpackSteps(packed)
            }

            val reward = computePlayoutReward(outcomeScore, totalSteps, work, humanSeat)
            var capturedDetails: HandScoreDetails? = null
            var capturedHand: OmniscientWinningHand? = null
            var capturedWinner = -1
            var capturedWinSource = -1
            var capturedOpponentHandScore = 0
            var captured = false

            for (p in path.indices) {
                val node = path[p]
                // Steps from this node's own human choice to the terminal outcome (root p=0 => totalSteps).
                val stepsFromNode = max(1, totalSteps - p)
                node.visits++
                node.playouts++
                node.sumReward += reward
                node.sumRewardSq += reward * reward
                if (isBetterOutcome(outcomeScore, stepsFromNode, node.bestScore, node.bestSteps)) {
                    node.bestScore = outcomeScore
                    node.bestSteps = stepsFromNode
                    if (outcomeScore > 0) {
                        if (!captured) {
                            capturedWinner = humanSeat
                            capturedWinSource = terminalWinSourceSeat(work)
                            capturedDetails = cloneDetails(Scoring.computeHandScoreDetails(work))
                            capturedHand = captureWinningHand(work, humanSeat)
                            captured = true
                        }
                        node.bestWinnerSeat = capturedWinner
                        node.bestWinSourceSeat = capturedWinSource
                        node.bestOpponentHandScore = 0
                        node.bestDetails = capturedDetails
                        node.bestWinningHand = capturedHand
                    } else if (outcomeScore < 0) {
                        if (!captured) {
                            capturedWinner = terminalWinnerSeat(work)
                            capturedWinSource = terminalWinSourceSeat(work)
                            capturedOpponentHandScore = work.handScore
                            captured = true
                        }
                        node.bestWinnerSeat = capturedWinner
                        node.bestWinSourceSeat = capturedWinSource
                        node.bestOpponentHandScore = capturedOpponentHandScore
                        node.bestDetails = null
                        node.bestWinningHand = null
                    } else {
                        node.bestWinnerSeat = -1
                        node.bestWinSourceSeat = -1
                        node.bestOpponentHandScore = 0
                        node.bestDetails = null
                        node.bestWinningHand = null
                    }
                }
            }
            if (cur.solved) {
                propagateSolved(path)
            }
            sessionPlayouts++
            return true
        }
    }

    private fun initTerminalNode(node: Node, terminalState: GameState, humanSeat: Int) {
        node.terminal = true
        node.solved = true
        val sc = terminalHumanOutcome(terminalState, humanSeat)
        node.bestScore = sc
        node.bestSteps = if (sc != NO_OUTCOME) 1 else Int.MAX_VALUE
        node.visits = 1
        node.playouts = 1
        applyOutcomeMetadata(node, sc, terminalState, humanSeat)
    }

    private fun recordRootProbe(arm: Node, outcomeScore: Int, steps: Int, terminalState: GameState, humanSeat: Int) {
        val reward = computePlayoutReward(outcomeScore, steps, terminalState, humanSeat)
        arm.visits++
        arm.playouts++
        arm.sumReward += reward
        arm.sumRewardSq += reward * reward
        if (isBetterOutcome(outcomeScore, steps, arm.bestScore, arm.bestSteps)) {
            arm.bestScore = outcomeScore
            arm.bestSteps = steps
            applyOutcomeMetadata(arm, outcomeScore, terminalState, humanSeat)
        }
    }

    private fun applyOutcomeMetadata(
        node: Node,
        outcomeScore: Int,
        terminalState: GameState,
        humanSeat: Int,
    ) {
        when {
            outcomeScore > 0 -> {
                node.bestWinnerSeat = humanSeat
                node.bestWinSourceSeat = terminalWinSourceSeat(terminalState)
                node.bestOpponentHandScore = 0
                node.bestDetails = cloneDetails(Scoring.computeHandScoreDetails(terminalState))
                node.bestWinningHand = captureWinningHand(terminalState, humanSeat)
            }
            outcomeScore < 0 && outcomeScore != NO_OUTCOME -> {
                node.bestWinnerSeat = terminalWinnerSeat(terminalState)
                node.bestWinSourceSeat = terminalWinSourceSeat(terminalState)
                node.bestOpponentHandScore = terminalState.handScore
                node.bestDetails = null
                node.bestWinningHand = null
            }
            else -> {
                node.bestWinnerSeat = -1
                node.bestWinSourceSeat = -1
                node.bestOpponentHandScore = 0
                node.bestDetails = null
                node.bestWinningHand = null
            }
        }
    }

    private fun hoveredArmIndex(armNodes: Array<Node>): Int {
        val hov = hoveredChoice ?: return -1
        for (i in armNodes.indices) {
            val c = armNodes[i].humanChoice
            if (c.kind == hov.kind && c.payload == hov.payload && !armNodes[i].solved) {
                return i
            }
        }
        return -1
    }

    private fun pickRoundRobinArm(armNodes: Array<Node>, cursor: Int): Int {
        val n = armNodes.size
        for (k in 0 until n) {
            val cand = (cursor + k) % n
            if (!armNodes[cand].solved) return cand
        }
        return -1
    }

    private fun publishSnapshot(armNodes: Array<Node>, evalsPerSec: Float = 0f) {
        var total = 0
        var maxScore = NO_OUTCOME
        var minStepsAtMax = Int.MAX_VALUE
        var allProbed = armNodes.isNotEmpty()
        val list = ArrayList<OmniscientHintArmSnapshot>(armNodes.size)
        for (arm in armNodes) {
            total += arm.playouts
            val hasOutcome = arm.bestScore != NO_OUTCOME
            if (!hasOutcome) allProbed = false
            val score = if (hasOutcome) arm.bestScore else 0
            val steps = if (hasOutcome && arm.bestSteps < Int.MAX_VALUE) arm.bestSteps else 0
            if (hasOutcome && isBetterOutcome(arm.bestScore, arm.bestSteps, maxScore, minStepsAtMax)) {
                maxScore = arm.bestScore
                minStepsAtMax = arm.bestSteps
            }
            list.add(
                OmniscientHintArmSnapshot(
                    choice = arm.humanChoice,
                    bestScore = score,
                    bestSteps = steps,
                    bestDetails = arm.bestDetails,
                    bestWinningHand = arm.bestWinningHand,
                    playouts = arm.playouts,
                    solved = arm.solved,
                    hasOutcome = hasOutcome,
                    bestWinnerSeat = arm.bestWinnerSeat,
                    bestWinSourceSeat = arm.bestWinSourceSeat,
                    bestOpponentHandScore = arm.bestOpponentHandScore,
                ),
            )
        }
        val hasBest = maxScore != NO_OUTCOME && (maxScore > 0 || allProbed)
        val bestIndices = if (!hasBest) {
            IntArray(0)
        } else {
            val hits = ArrayList<Int>(4)
            for (i in armNodes.indices) {
                if (armNodes[i].bestScore == maxScore && armNodes[i].bestSteps == minStepsAtMax) {
                    hits.add(i)
                }
            }
            IntArray(hits.size) { hits[it] }
        }
        val snap = synchronized(publishLock) {
            publishRevision += 1L
            OmniscientHintSnapshot(list, total, bestIndices, publishRevision, evalsPerSec).also {
                published = it
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        snap
        onPublish()
    }

    // ── Tree reuse across turns ─────────────────────────────────────────────

    private class RetainedTurn(
        val humanSeat: Int,
        val opponentEngineName: String,
        val armNodes: Array<Node>,
        val armStates: Array<GameState>,
    )

    private class ReusedRoot(
        val armNodes: Array<Node>,
        val armStates: Array<GameState>,
    )

    private fun tryReuseSubtree(
        prev: RetainedTurn?,
        liveState: GameState,
        rootChoices: Array<Choice>,
        humanSeat: Int,
        opponentEngineName: String,
        opponentEngine: PlayingEngine,
    ): ReusedRoot? {
        if (prev == null) return null
        if (prev.humanSeat != humanSeat || prev.opponentEngineName != opponentEngineName) return null

        var matchedParent: Node? = null
        for (i in prev.armStates.indices) {
            if (statesMatch(prev.armStates[i], liveState)) {
                matchedParent = prev.armNodes[i]
                break
            }
        }
        val parent = matchedParent ?: return null
        val prevChildren = parent.children ?: return null
        val prevChoices = parent.childChoices ?: return null

        val armNodes = Array(rootChoices.size) { Node(rootChoices[it]) }
        val armStates = Array(rootChoices.size) { GameState() }
        var initOpp: PlayingEngine? = null
        val tmpChoices = ChoiceList()
        val tapeBuf = ArrayList<Choice>(32)

        var anyReused = false
        for (i in rootChoices.indices) {
            val rc = rootChoices[i]
            var found: Node? = null
            for (j in 0 until parent.expandedCount) {
                val pc = prevChoices[j]
                if (pc.kind == rc.kind && pc.payload == rc.payload) {
                    found = prevChildren[j]
                    break
                }
            }
            if (found != null && found.macroChoices != null) {
                armNodes[i] = found
                armStates[i].copyFrom(liveState)
                replayTape(armStates[i], found.macroChoices!!)
                anyReused = true
            } else {
                val opp = initOpp ?: opponentEngine.clone().also { initOpp = it }
                armStates[i].copyFrom(liveState)
                val tape = macroAdvance(armStates[i], rc, humanSeat, opp, tmpChoices, tapeBuf)
                val arm = armNodes[i]
                arm.macroChoices = tape
                if (armStates[i].isTerminal()) {
                    initTerminalNode(arm, armStates[i], humanSeat)
                }
            }
        }
        if (!anyReused) return null
        return ReusedRoot(armNodes, armStates)
    }

    // ── Tree structures & helpers ───────────────────────────────────────────

    private class Node(
        val humanChoice: Choice,
    ) {
        var macroChoices: Array<Choice>? = null
        var terminal: Boolean = false
        var solved: Boolean = false
        var expanded: Boolean = false

        var visits: Int = 0
        var playouts: Int = 0
        var bestScore: Int = NO_OUTCOME
        var bestSteps: Int = Int.MAX_VALUE
        var bestWinnerSeat: Int = -1
        var bestWinSourceSeat: Int = -1
        var bestOpponentHandScore: Int = 0
        var bestDetails: HandScoreDetails? = null
        var bestWinningHand: OmniscientWinningHand? = null
        var sumReward: Double = 0.0
        var sumRewardSq: Double = 0.0

        var childChoices: Array<Choice>? = null
        var children: Array<Node?>? = null
        var expandedCount: Int = 0
    }

    private enum class SeekerKind {
        THIRTEEN_TERMINALS,
        NINE_GATES,
        PURE_ONE_SUIT,
        MIXED_ONE_SUIT,
        ALL_TRIPLETS,
        CONCEALED_TRIPLETS,
        TERMINALS_HONORS,
        SEVEN_PAIRS,
        DRAGONS_WINDS,
    }

    private class Seeker(val kind: SeekerKind, val suit: Int = -1)

    private fun macroAdvance(
        state: GameState,
        humanChoice: Choice,
        humanSeat: Int,
        opponentEngine: PlayingEngine,
        tmpChoices: ChoiceList,
        tapeBuf: ArrayList<Choice>,
    ): Array<Choice> {
        tapeBuf.clear()
        tapeBuf.add(humanChoice)
        state.applyChoice(humanChoice)

        var guard = 0
        while (!state.isTerminal() && ++guard < MAX_STEPS) {
            state.enumerateChoicesInto(tmpChoices)
            val n = tmpChoices.size
            if (n == 0) break

            if (state.isTrivialPassOnlyReaction(tmpChoices)) {
                val c = tmpChoices[0]
                tapeBuf.add(c)
                state.applyChoice(c)
                continue
            }

            if (state.currentPlayer == humanSeat && n >= 2) {
                break
            }

            val picked = if (n == 1) {
                tmpChoices[0]
            } else {
                tmpChoices[opponentEngine.selectIndex(state, tmpChoices)]
            }
            tapeBuf.add(picked)
            state.applyChoice(picked)
        }
        return tapeBuf.toTypedArray()
    }

    private fun replayTape(state: GameState, tape: Array<Choice>) {
        for (i in tape.indices) {
            state.applyChoice(tape[i])
        }
    }

    private fun initNodeChildren(
        node: Node,
        stateAtNode: GameState,
        humanEngine: DefaultEngine,
        tmpChoices: ChoiceList,
    ) {
        stateAtNode.enumerateChoicesInto(tmpChoices)
        val n = tmpChoices.size
        if (n == 0 || stateAtNode.isTerminal()) {
            node.terminal = true
            node.solved = true
            node.expanded = true
            return
        }

        val ordered = ArrayList<Choice>(n)
        val used = BooleanArray(n)

        for (i in 0 until n) {
            if (tmpChoices[i].kind == ChoiceKind.Hu) {
                ordered.add(tmpChoices[i])
                used[i] = true
            }
        }
        if (ordered.size < n) {
            val ranked = humanEngine.rank(stateAtNode, tmpChoices)
            for (idx in ranked) {
                if (idx in 0 until n && !used[idx]) {
                    ordered.add(tmpChoices[idx])
                    used[idx] = true
                }
            }
        }
        for (i in 0 until n) {
            if (!used[i]) ordered.add(tmpChoices[i])
        }

        node.childChoices = ordered.toTypedArray()
        node.children = arrayOfNulls(ordered.size)
        node.expanded = true
    }

    private fun selectChildSpMcts(parent: Node, scale: Double): Int {
        val children = parent.children ?: return -1
        val logN = ln(max(1, parent.visits).toDouble())
        var bestIdx = -1
        var bestVal = Double.NEGATIVE_INFINITY

        for (i in 0 until parent.expandedCount) {
            val c = children[i] ?: continue
            if (c.solved) continue
            val nc = max(1, c.visits).toDouble()
            val mean = c.sumReward / nc
            val meanNorm = mean / scale
            val maxNorm = if (c.bestScore == NO_OUTCOME) 0.0 else c.bestScore / scale
            val variance = max(0.0, (c.sumRewardSq / nc) - mean * mean) / (scale * scale)

            val ucb = 0.50 * maxNorm +
                0.50 * meanNorm +
                0.70 * sqrt(logN / nc) +
                0.50 * sqrt((variance + 0.05) / nc)

            if (ucb > bestVal) {
                bestVal = ucb
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun propagateSolved(path: List<Node>) {
        for (i in path.lastIndex downTo 0) {
            val n = path[i]
            if (n.solved) continue
            val children = n.children ?: continue
            if (n.expanded && n.expandedCount == children.size) {
                var allSolved = true
                for (ch in children) {
                    if (ch == null || !ch.solved) {
                        allSolved = false
                        break
                    }
                }
                if (allSolved) {
                    n.solved = true
                    n.children = null
                    n.childChoices = null
                }
            }
        }
    }

    // ── Pattern Seekers & playout policies ──────────────────────────────────

    private fun detectFeasibleSeekers(
        state: GameState,
        humanSeat: Int,
        liveCounts: IntArray,
    ): List<Seeker> {
        val ps = state.players[humanSeat]
        val openMelds = Deficiency.countOpenMelds(ps)
        val concealed = openMelds == 0

        liveCounts.fill(0)
        val wall = state.wall
        val liveWall = state.liveWallSize
        for (i in 0 until liveWall) {
            val t = wall[i]
            if (t in 0 until NUM_TILE_TYPES) liveCounts[t]++
        }
        for (p in 0 until NUM_PLAYERS) {
            val c = state.players[p].concealed
            for (t in 0 until NUM_TILE_TYPES) liveCounts[t] += c[t]
        }

        val my = ps.concealed
        val suitTotals = IntArray(3)
        var honorTotal = 0
        var thUnique = 0
        var thTotal = 0
        var pairOrMore = 0
        var honorPairs = 0

        for (t in 0 until NUM_TILE_TYPES) {
            val n = my[t]
            if (t < 27) {
                suitTotals[t / 9] += n
            } else {
                honorTotal += n
                if (n >= 2) honorPairs++
            }
            if (n >= 2) pairOrMore++
            if (Tiles.isTerminalOrHonor(t)) {
                thTotal += n
                if (n > 0) thUnique++
            }
        }

        val pk = ps.pungsKongs
        var hasOpenSimpleMeld = false
        var hasOpenChow = false
        var hasOpenHonor = false
        var openSuitMask = 0
        for (t in 0 until NUM_TILE_TYPES) {
            if (pk[t] != 0) {
                if (t < 27) {
                    suitTotals[t / 9] += 3
                    openSuitMask = openSuitMask or (1 shl (t / 9))
                } else {
                    honorTotal += 3
                    honorPairs++
                    hasOpenHonor = true
                }
                pairOrMore++
                if (Tiles.isSimple(t)) hasOpenSimpleMeld = true else thTotal += 3
            }
        }
        val chows = ps.openChows
        for (ci in chows.indices) {
            if (chows[ci] > 0) {
                hasOpenChow = true
                hasOpenSimpleMeld = true
                val s = ci / 7
                suitTotals[s] += 3 * chows[ci]
                openSuitMask = openSuitMask or (1 shl s)
            }
        }

        val seekers = ArrayList<Seeker>(10)

        if (concealed && thUnique >= 5) {
            var allLive = true
            for (t in TERMINAL_HONORS) {
                if (liveCounts[t] == 0) {
                    allLive = false
                    break
                }
            }
            if (allLive) seekers.add(Seeker(SeekerKind.THIRTEEN_TERMINALS))
        }

        for (s in 0..2) {
            val otherSuitsLocked = (openSuitMask and (1 shl s).inv()) != 0
            if (!otherSuitsLocked && !hasOpenHonor && suitTotals[s] >= 6) {
                seekers.add(Seeker(SeekerKind.PURE_ONE_SUIT, s))
                if (concealed && suitTotals[s] >= 7) {
                    val base = s * 9
                    var ngLive = liveCounts[base] >= 3 && liveCounts[base + 8] >= 3
                    var r = 1
                    while (r <= 7 && ngLive) {
                        if (liveCounts[base + r] < 1) ngLive = false
                        r++
                    }
                    if (ngLive) seekers.add(Seeker(SeekerKind.NINE_GATES, s))
                }
            }
            if (!otherSuitsLocked && suitTotals[s] + honorTotal >= 8) {
                seekers.add(Seeker(SeekerKind.MIXED_ONE_SUIT, s))
            }
        }

        if (!hasOpenChow && pairOrMore >= 2) {
            seekers.add(Seeker(SeekerKind.ALL_TRIPLETS))
            if (concealed && pairOrMore >= 3) {
                seekers.add(Seeker(SeekerKind.CONCEALED_TRIPLETS))
            }
        }
        if (!hasOpenSimpleMeld && thTotal >= 5) {
            seekers.add(Seeker(SeekerKind.TERMINALS_HONORS))
        }
        if (concealed && pairOrMore >= 2) {
            seekers.add(Seeker(SeekerKind.SEVEN_PAIRS))
        }
        if (honorTotal >= 4 && honorPairs >= 1) {
            seekers.add(Seeker(SeekerKind.DRAGONS_WINDS))
        }

        return seekers
    }

    private fun runPlayout(
        state: GameState,
        humanSeat: Int,
        opponentEngine: PlayingEngine,
        humanEngine: DefaultEngine,
        tmpChoices: ChoiceList,
        seeker: Seeker?,
        minTargetScore: Int,
        minTargetSteps: Int,
        initialSteps: Int,
        eps: Double,
        skipLowHuProb: Double,
        rng: Random,
    ): Long {
        var steps = initialSteps
        var guard = 0
        while (!state.isTerminal() && ++guard < MAX_STEPS) {
            state.enumerateChoicesInto(tmpChoices)
            val n = tmpChoices.size
            if (n == 0) break

            if (state.isTrivialPassOnlyReaction(tmpChoices)) {
                state.applyChoice(tmpChoices[0])
                continue
            }

            val isHumanDecision = state.currentPlayer == humanSeat
            val picked = when {
                n == 1 -> tmpChoices[0]
                isHumanDecision -> {
                    if (seeker != null) {
                        pickSeekerHuman(state, tmpChoices, humanSeat, seeker, minTargetScore, minTargetSteps, steps, eps, rng)
                    } else {
                        pickDefaultHuman(state, tmpChoices, humanEngine, minTargetScore, minTargetSteps, steps, eps, skipLowHuProb, rng)
                    }
                }
                else -> tmpChoices[opponentEngine.selectIndex(state, tmpChoices)]
            }
            if (isHumanDecision) steps++
            state.applyChoice(picked)
        }
        val outcome = terminalHumanOutcome(state, humanSeat)
        return packOutcome(outcome, if (outcome != NO_OUTCOME) steps else Int.MAX_VALUE)
    }

    private fun pickDefaultHuman(
        state: GameState,
        choices: ChoiceList,
        humanEngine: DefaultEngine,
        minTargetScore: Int,
        minTargetSteps: Int,
        stepsSoFar: Int,
        eps: Double,
        skipLowHuProb: Double,
        rng: Random,
    ): Choice {
        val n = choices.size
        var bestHuIdx = -1
        var bestHuScore = -1
        for (i in 0 until n) {
            val c = choices[i]
            if (c.kind == ChoiceKind.Hu && c.huScore() > bestHuScore) {
                bestHuScore = c.huScore()
                bestHuIdx = i
            }
        }
        if (bestHuIdx >= 0) {
            val strictlyBetter = isBetterOutcome(bestHuScore, stepsSoFar + 1, minTargetScore, minTargetSteps)
            if (bestHuScore > minTargetScore || n == 1 || (strictlyBetter && rng.nextDouble() < 0.60) || rng.nextDouble() >= skipLowHuProb) {
                return choices[bestHuIdx]
            }
            if (state.decisionType == DecisionType.ReactToDiscard) {
                return Choice.pass()
            }
            val pick = rng.nextInt(n - 1)
            var cur = 0
            for (i in 0 until n) {
                if (choices[i].kind == ChoiceKind.Hu) continue
                if (cur == pick) return choices[i]
                cur++
            }
        }

        if (eps > 0.0 && n > 1 && rng.nextDouble() < eps) {
            return choices[rng.nextInt(n)]
        }
        return choices[humanEngine.selectIndex(state, choices)]
    }

    private fun pickSeekerHuman(
        state: GameState,
        choices: ChoiceList,
        humanSeat: Int,
        seeker: Seeker,
        minTargetScore: Int,
        minTargetSteps: Int,
        stepsSoFar: Int,
        eps: Double,
        rng: Random,
    ): Choice {
        val n = choices.size
        var bestHuIdx = -1
        var bestHuScore = -1
        for (i in 0 until n) {
            val c = choices[i]
            if (c.kind == ChoiceKind.Hu && c.huScore() > bestHuScore) {
                bestHuScore = c.huScore()
                bestHuIdx = i
            }
        }
        if (bestHuIdx >= 0) {
            val threshold = max(minTargetScore, seekerThreshold(seeker.kind))
            val fasterTie = bestHuScore == minTargetScore && bestHuScore > 0 && stepsSoFar + 1 < minTargetSteps
            if (bestHuScore > minTargetScore || bestHuScore >= threshold || fasterTie || n == 1) {
                return choices[bestHuIdx]
            }
            if (state.decisionType == DecisionType.ReactToDiscard) {
                return Choice.pass()
            }
        }

        if (eps > 0.0 && n > 1 && rng.nextDouble() < eps) {
            val cand = choices[rng.nextInt(n)]
            if (!(requiresConcealed(seeker.kind) && isOpeningClaim(cand.kind)) && cand.kind != ChoiceKind.Hu) {
                return cand
            }
        }

        val counts = state.players[humanSeat].concealed
        val seatWind = state.seatWindTile(humanSeat)

        if (state.decisionType == DecisionType.ReactToDiscard) {
            if (requiresConcealed(seeker.kind)) return Choice.pass()
            val tile = state.lastDiscarded
            var best = Choice.pass()
            var bestVal = 0
            for (i in 0 until n) {
                val c = choices[i]
                val k = c.kind
                if (k == ChoiceKind.Pass || k == ChoiceKind.Hu) continue
                val v = scoreClaimForSeeker(seeker, k, tile, seatWind)
                if (v > bestVal) {
                    bestVal = v
                    best = c
                }
            }
            return best
        }

        var best: Choice? = null
        var bestPriority = Int.MIN_VALUE
        for (i in 0 until n) {
            val c = choices[i]
            when (c.kind) {
                ChoiceKind.Hu -> continue
                ChoiceKind.Keep -> {
                    if (requiresConcealed(seeker.kind) && seeker.kind != SeekerKind.CONCEALED_TRIPLETS) {
                        return c
                    }
                    continue
                }
                ChoiceKind.ConcealedKong, ChoiceKind.PromoteKong -> {
                    if (seeker.kind == SeekerKind.ALL_TRIPLETS ||
                        seeker.kind == SeekerKind.CONCEALED_TRIPLETS ||
                        seeker.kind == SeekerKind.DRAGONS_WINDS ||
                        seeker.kind == SeekerKind.TERMINALS_HONORS
                    ) {
                        return c
                    }
                    continue
                }
                ChoiceKind.Discard -> {
                    val prio = discardPriorityForSeeker(seeker, c.tile(), counts, seatWind)
                    if (prio > bestPriority) {
                        bestPriority = prio
                        best = c
                    }
                }
                else -> continue
            }
        }
        return best ?: choices[0]
    }

    private fun requiresConcealed(k: SeekerKind): Boolean =
        k == SeekerKind.THIRTEEN_TERMINALS ||
            k == SeekerKind.NINE_GATES ||
            k == SeekerKind.SEVEN_PAIRS ||
            k == SeekerKind.CONCEALED_TRIPLETS

    private fun isOpeningClaim(k: ChoiceKind): Boolean =
        k == ChoiceKind.Chow || k == ChoiceKind.Pung || k == ChoiceKind.Kong

    private fun seekerThreshold(k: SeekerKind): Int = when (k) {
        SeekerKind.THIRTEEN_TERMINALS, SeekerKind.NINE_GATES -> 320
        SeekerKind.CONCEALED_TRIPLETS, SeekerKind.PURE_ONE_SUIT -> 80
        else -> 40
    }

    private fun scoreClaimForSeeker(seeker: Seeker, k: ChoiceKind, tile: Int, seatWind: Int): Int =
        when (seeker.kind) {
            SeekerKind.PURE_ONE_SUIT ->
                if (tile < 27 && tile / 9 == seeker.suit) {
                    when (k) {
                        ChoiceKind.Kong -> 30
                        ChoiceKind.Pung -> 25
                        ChoiceKind.Chow -> 20
                        else -> -1
                    }
                } else {
                    -1
                }
            SeekerKind.MIXED_ONE_SUIT ->
                if (tile >= 27 || tile / 9 == seeker.suit) {
                    when (k) {
                        ChoiceKind.Kong -> 30
                        ChoiceKind.Pung -> if (tile >= 31 || tile == seatWind) 35 else 25
                        ChoiceKind.Chow -> 15
                        else -> -1
                    }
                } else {
                    -1
                }
            SeekerKind.ALL_TRIPLETS -> when (k) {
                ChoiceKind.Kong -> 35
                ChoiceKind.Pung -> if (tile >= 27) 32 else 25
                else -> -1
            }
            SeekerKind.TERMINALS_HONORS ->
                if (Tiles.isTerminalOrHonor(tile) && (k == ChoiceKind.Pung || k == ChoiceKind.Kong)) 30 else -1
            SeekerKind.DRAGONS_WINDS -> when {
                tile >= 27 && (k == ChoiceKind.Pung || k == ChoiceKind.Kong) -> if (tile >= 31) 45 else 35
                k == ChoiceKind.Pung -> 15
                k == ChoiceKind.Chow -> 8
                else -> -1
            }
            else -> -1
        }

    /** Higher priority = more eager to discard [t]. */
    private fun discardPriorityForSeeker(
        seeker: Seeker,
        t: Int,
        counts: IntArray,
        seatWind: Int,
    ): Int {
        val cnt = counts[t]
        val isHonor = t >= 27
        val isSimple = Tiles.isSimple(t)
        val suit = if (isHonor) -1 else t / 9
        val rank = if (isHonor) -1 else t % 9

        return when (seeker.kind) {
            SeekerKind.THIRTEEN_TERMINALS -> when {
                isSimple -> 1000 - abs(rank - 4) * 10
                cnt >= 3 -> 500 + cnt * 10
                cnt == 2 -> {
                    var pairs = 0
                    for (th in TERMINAL_HONORS) if (counts[th] >= 2) pairs++
                    if (pairs >= 2) 300 else 10
                }
                else -> 0
            }
            SeekerKind.NINE_GATES -> {
                if (suit != seeker.suit) {
                    if (isHonor) 1000 else 900
                } else {
                    val needed = if (rank == 0 || rank == 8) 3 else 1
                    if (cnt > needed) 500 + (cnt - needed) * 50 else 50 - cnt * 10
                }
            }
            SeekerKind.PURE_ONE_SUIT -> {
                if (suit != seeker.suit) {
                    (if (isHonor) 1000 else 900) - cnt * 20
                } else {
                    var neighbors = 0
                    if (rank > 0 && counts[t - 1] > 0) neighbors++
                    if (rank < 8 && counts[t + 1] > 0) neighbors++
                    if (rank > 1 && counts[t - 2] > 0) neighbors++
                    if (rank < 7 && counts[t + 2] > 0) neighbors++
                    200 - cnt * 60 - neighbors * 25
                }
            }
            SeekerKind.MIXED_ONE_SUIT -> when {
                !isHonor && suit != seeker.suit -> 1000 - cnt * 20
                isHonor -> 300 - cnt * 90 - if (t >= 31 || t == seatWind) 40 else 0
                else -> {
                    var neighbors = 0
                    if (rank > 0 && counts[t - 1] > 0) neighbors++
                    if (rank < 8 && counts[t + 1] > 0) neighbors++
                    220 - cnt * 60 - neighbors * 25
                }
            }
            SeekerKind.ALL_TRIPLETS, SeekerKind.CONCEALED_TRIPLETS -> when (cnt) {
                1 -> 800 - (if (t >= 31 || t == seatWind) 20 else 0) + (if (isSimple) 50 else 0)
                2 -> 200 + (if (isSimple) 30 else 0)
                4 -> 400
                else -> 10
            }
            SeekerKind.TERMINALS_HONORS -> when {
                isSimple -> 1000 - cnt * 15
                cnt == 1 -> 300
                cnt == 2 -> 120
                else -> 10
            }
            SeekerKind.SEVEN_PAIRS -> when (cnt) {
                1 -> 800
                3 -> 700
                4 -> 150
                else -> 10
            }
            SeekerKind.DRAGONS_WINDS -> {
                if (isHonor) {
                    200 - cnt * 90 - if (t >= 31) 80 else 30
                } else {
                    var neighbors = 0
                    if (rank > 0 && counts[t - 1] > 0) neighbors++
                    if (rank < 8 && counts[t + 1] > 0) neighbors++
                    600 - cnt * 70 - neighbors * 35
                }
            }
        }
    }

    private fun terminalWinnerSeat(state: GameState): Int {
        if (state.handScore <= 0) return -1
        return if (state.winnerSeat in 0 until NUM_PLAYERS) state.winnerSeat else state.currentPlayer
    }

    private fun terminalWinSourceSeat(state: GameState): Int {
        if (state.handScore <= 0) return -1
        return when {
            state.winSource in 0 until NUM_PLAYERS -> state.winSource
            state.decisionType == DecisionType.ReactToDiscard &&
                state.lastDiscarder in 0 until NUM_PLAYERS -> state.lastDiscarder
            else -> -1
        }
    }

    /**
     * Terminal outcome for [humanSeat]:
     * - `> 0`: human win hand score,
     * - `0`: exhaustive draw,
     * - `< 0`: human loss (signed Zung Jung payment delta),
     * - [NO_OUTCOME]: non-terminal state.
     */
    private fun terminalHumanOutcome(state: GameState, humanSeat: Int): Int {
        if (!state.isTerminal()) return NO_OUTCOME
        if (state.handScore <= 0) return 0
        val winner = terminalWinnerSeat(state)
        return if (winner == humanSeat) {
            state.handScore
        } else {
            state.computeScoreDeltaForSeat(humanSeat)
        }
    }

    private fun computePlayoutReward(
        outcome: Int,
        steps: Int,
        terminalState: GameState,
        humanSeat: Int,
    ): Double = when {
        outcome > 0 -> outcome.toDouble() + 0.01 / max(1, steps)
        outcome == 0 || outcome == NO_OUTCOME -> computeShaping(terminalState, humanSeat)
        else -> outcome.toDouble() + 0.5 * computeShaping(terminalState, humanSeat) + 0.01 / max(1, steps)
    }

    private fun isBetterOutcome(
        score: Int,
        steps: Int,
        curBestScore: Int,
        curBestSteps: Int,
    ): Boolean {
        if (score == NO_OUTCOME) return false
        if (curBestScore == NO_OUTCOME) return true
        if (score != curBestScore) return score > curBestScore
        return steps < curBestSteps
    }

    private fun packOutcome(score: Int, steps: Int): Long =
        (score.toLong() shl 32) or (steps.toLong() and 0xFFFF_FFFFL)

    private fun unpackScore(packed: Long): Int = (packed ushr 32).toInt()

    private fun unpackSteps(packed: Long): Int = (packed and 0xFFFF_FFFFL).toInt()

    private fun computeShaping(terminalState: GameState, humanSeat: Int): Double {
        val def = max(0, DeficiencyFast.handDeficiencyFast(terminalState.players[humanSeat]))
        return 0.5 / (1.0 + def)
    }

    private fun cloneDetails(d: HandScoreDetails): HandScoreDetails = HandScoreDetails(
        totalScore = d.totalScore,
        uncappedTotal = d.uncappedTotal,
        components = ArrayList(d.components),
    )

    private fun captureWinningHand(state: GameState, winnerSeat: Int): OmniscientWinningHand {
        val ps = state.players[winnerSeat]
        val claimedWin =
            state.decisionType == DecisionType.ReactToDiscard &&
                state.lastDiscarded in 0 until NUM_TILE_TYPES
        val winningTile = when {
            claimedWin -> state.lastDiscarded
            state.lastDrawnPlayer == winnerSeat &&
                state.lastDrawnTile in 0 until NUM_TILE_TYPES &&
                ps.concealed[state.lastDrawnTile] > 0 -> state.lastDrawnTile
            else -> -1
        }

        val concealed = ArrayList<Int>(14)
        var skippedWinningCopy = claimedWin || winningTile < 0
        for (t in 0 until NUM_TILE_TYPES) {
            val count = ps.concealed[t]
            if (count <= 0) continue
            val keep = if (!skippedWinningCopy && t == winningTile) {
                skippedWinningCopy = true
                count - 1
            } else {
                count
            }
            repeat(keep) { concealed.add(t) }
        }

        val melds = ArrayList<OmniscientWinningMeld>(ps.meldCount)
        for (i in 0 until ps.meldCount) {
            when (ps.meldKind[i]) {
                PlayerState.MELD_CHOW -> {
                    val ci = ps.meldValue[i]
                    val first = (ci / 7) * 9 + (ci % 7)
                    melds.add(OmniscientWinningMeld(listOf(first, first + 1, first + 2)))
                }
                PlayerState.MELD_SET -> {
                    val tile = ps.meldValue[i]
                    when (ps.pungsKongs[tile]) {
                        PlayerState.PK_OPEN_PUNG ->
                            melds.add(OmniscientWinningMeld(listOf(tile, tile, tile)))
                        PlayerState.PK_OPEN_KONG ->
                            melds.add(OmniscientWinningMeld(listOf(tile, tile, tile, tile)))
                        PlayerState.PK_CONCEALED_KONG ->
                            melds.add(
                                OmniscientWinningMeld(
                                    listOf(tile, tile, tile, tile),
                                    concealedKong = true,
                                ),
                            )
                    }
                }
            }
        }

        val winSourceSeat = when {
            !claimedWin -> -1
            state.winSource in 0 until NUM_PLAYERS -> state.winSource
            state.lastDiscarder in 0 until NUM_PLAYERS -> state.lastDiscarder
            else -> -1
        }

        return OmniscientWinningHand(
            concealedTiles = concealed,
            melds = melds,
            winningTile = winningTile,
            winSourceSeat = winSourceSeat,
        )
    }

    private fun statesMatch(a: GameState, b: GameState): Boolean {
        if (a.liveWallSize != b.liveWallSize ||
            a.currentPlayer != b.currentPlayer ||
            a.decisionType != b.decisionType ||
            a.lastDiscarded != b.lastDiscarded ||
            a.lastDiscarder != b.lastDiscarder ||
            a.eastSeat != b.eastSeat ||
            a.allowChickenHands != b.allowChickenHands ||
            a.seabedPlayer != b.seabedPlayer ||
            a.handEnded != b.handEnded ||
            a.lastDrawWasKongSupplement != b.lastDrawWasKongSupplement ||
            a.kongRobWindow != b.kongRobWindow
        ) {
            return false
        }
        for (i in 0 until a.liveWallSize) {
            if (a.wall[i] != b.wall[i]) return false
        }
        for (p in 0 until NUM_PLAYERS) {
            val pa = a.players[p]
            val pb = b.players[p]
            if (!pa.concealed.contentEquals(pb.concealed)) return false
            if (!pa.pungsKongs.contentEquals(pb.pungsKongs)) return false
            if (!pa.openChows.contentEquals(pb.openChows)) return false
        }
        return true
    }

    companion object {
        private const val NO_OUTCOME = Int.MIN_VALUE
        private const val MAX_STEPS = 512
        private const val JOIN_TIMEOUT_MS = 500L
        private const val PUBLISH_MIN_INTERVAL_NS = 1_000_000_000L
        private const val RATE_WINDOW_MIN_NS = 250_000_000L
        /** Cooperative per-slice budget (~12 ms) on Web/WasmGC so input and painting stay responsive. */
        const val FRAME_SLICE_BUDGET_NS = 12_000_000L

        private val TERMINAL_HONORS = intArrayOf(
            0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33,
        )

        /** True on platforms with preemptive threads (desktop, Android); false on Web/WasmGC. */
        internal fun workerSupportedOn(type: ApplicationType?): Boolean =
            type != ApplicationType.WebGL
    }
}
