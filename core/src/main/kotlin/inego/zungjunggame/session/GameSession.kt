package inego.zungjunggame.session

import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.persist.SessionSnapshot
import inego.zungjunggame.platform.newRandom
import inego.zungjunggame.state.StateSlot
import kotlin.random.Random

/**
 * Multi-hand free-play lifecycle: config, scores, east rotation, deal, terminal
 * recording, finite-game completion, and snapshot persist/restore.
 *
 * Implements [LiveHand] so [inego.zungjunggame.Main] can read the board through
 * a façade shared with the tutorial runner.
 *
 * **Persist policy:** [persist] writes the `session.freeplay` key only
 * while [persistAllowed] returns true. The composition root derives that lazily
 * from the active [SessionKind] (free-play only).
 *
 * **Independent of the tutorial.** A lesson owns its own live board and its own
 * mid-lesson save (`session.tutorial`), so entering or leaving the tutorial leaves
 * this session — the live board and the `session.freeplay` key — alone. Mid-lesson
 * state and campaign progress ([inego.zungjunggame.tutorial.TutorialProgress]) are
 * kept when starting free-play, so Tutorial resumes where it left off.
 *
 * Does not own libGDX UI ([inego.zungjunggame.render.table.PlayTableView], overlays)
 * or AI pacing. Callers apply [DealResult] / flags to the table and directors.
 */
class GameSession(
    private val sessionSlot: StateSlot<SessionSnapshot>,
    /** Last committed New Game config; this session owns when it is written. */
    private val gameConfigSlot: StateSlot<GameConfig>,
    override val humanSeat: Int = 0,
    /**
     * Lazy persist gate. Evaluated per call so it can track the live
     * [SessionKind] without being captured eagerly. Defaults to allowing
     * standalone sessions to write.
     */
    private val persistAllowed: () -> Boolean = { true },
) : LiveHand {
    private var _state: GameState? = null

    /** Live hand, or null before the first deal / after a wipe. */
    override val state: GameState? get() = _state

    /**
     * True after setup Start or a successful session restore.
     * Also exposed as [LiveHand.active].
     */
    var gameActive: Boolean = false
        private set

    override val active: Boolean get() = gameActive

    /** Rules for the current game (set on Start / restore). */
    var gameConfig: GameConfig = GameConfig.DEFAULT
        private set

    /** Multi-hand ZJ score history (absolute seats 0..3). */
    val scores = SessionScoreTable()

    /**
     * Seat that will be East on the next deal. Rotates after every hand
     * (counter-clockwise: 0→1→2→3→0).
     */
    var nextEastSeat: Int = 0
        private set

    /** True after the current finished hand was appended to [scores]. */
    var currentHandRecorded: Boolean = false
        private set

    /** Seed used for the last deal. */
    var dealSeed: Long = System.nanoTime()
        private set

    /** RNG for engine picks this hand (re-seeded on deal / restore). */
    override var choiceRng: Random = Random(System.nanoTime())
        private set


    /** Finite game finished every configured hand. */
    fun isGameComplete(): Boolean {
        val max = gameConfig.maxHands ?: return false
        return scores.handCount >= max
    }

    /**
     * True when the hand currently on the board is the last hand of a finite game
     * (used for the hand-end CTA while that hand is still showing).
     */
    fun isLastHandOfGame(): Boolean {
        val max = gameConfig.maxHands ?: return false
        val handNum =
            if (currentHandRecorded) scores.handCount
            else scores.handCount + 1
        return handNum >= max
    }

    /**
     * Current hand number in the game (1-based). Counts finished hands as
     * complete; mid-hand is the next index after the last recorded total.
     */
    fun currentHandNumber(): Int {
        val n =
            if (currentHandRecorded) scores.handCount
            else scores.handCount + 1
        return n.coerceAtLeast(1)
    }

    /**
     * Begin a brand-new game from setup: clear scores, pick a random starting
     * East, deal hand 1.
     *
     * @return [DealResult.Dealt] for the first hand, or a terminal outcome if
     *   the config cannot deal (should not happen for a fresh start).
     */
    fun startGame(config: GameConfig): DealResult {
        gameConfig = config
        gameConfigSlot.set(config)
        scores.clear()
        // Do not record whatever was on the board from a previous game.
        currentHandRecorded = true
        // A fresh game picks a random dealer, so the human's first wind is
        // random every game instead of always East. newRandom(), not
        // Random.Default: that one constructs ThreadLocalRandom, whose setSeed
        // throws on TeaVM.
        nextEastSeat = newRandom().nextInt(NUM_PLAYERS)
        gameActive = true
        return dealHand()
    }

    /**
     * Deal the next hand in the current game (or the first after [startGame]).
     * Records a finished board first when applicable.
     *
     * @return what the caller should do with the table / setup UI.
     */
    fun dealHand(): DealResult {
        if (!gameActive) return DealResult.Inactive
        if (isGameComplete()) {
            // Finite game already finished — open setup instead of over-dealing.
            return DealResult.NeedsNewGameSetup
        }
        maybeRecordFinishedHand()
        // After recording the last hand, do not deal past the cap.
        if (isGameComplete()) {
            persist()
            return DealResult.GameCapReached
        }
        dealSeed = System.nanoTime()
        choiceRng = Random(dealSeed xor CHOICE_RNG_MIX)
        _state = GameState.generateRandom(
            Random(dealSeed),
            eastSeat = nextEastSeat,
            allowChickenHands = gameConfig.allowChickenHands,
        )
        // Next hand: dealer / East advances to the next seat.
        nextEastSeat = (nextEastSeat + 1) % NUM_PLAYERS
        currentHandRecorded = false
        persist()
        return DealResult.Dealt(restored = false)
    }

    /**
     * Append this hand's ZJ settlement once when the board is terminal.
     * @return true if a new score row was recorded
     */
    fun maybeRecordFinishedHand(): Boolean {
        if (!gameActive) return false
        val st = _state ?: return false
        if (!st.isTerminal() || currentHandRecorded) return false
        scores.recordTerminal(st)
        currentHandRecorded = true
        persist()
        return true
    }

    /**
     * The stored board in the `session.freeplay` key, or null when none is saved.
     *
     * A cold start does not come through here: the composition root reads the key
     * once (`AppState`) and hands the value to
     * [inego.zungjunggame.session.hosts.FreePlayHost.tryRestore]. This is the mid-run
     * reader, for a menu that offers to resume a saved game.
     */
    fun persistedSnapshot(): SessionSnapshot? = sessionSlot.get()

    /** Adopt [snapshot] as the live board (cold start, or a resume). */
    fun applySnapshot(snapshot: SessionSnapshot) {
        scores.restore(snapshot.scoreRows)
        _state = snapshot.game
        gameConfig = snapshot.gameConfig
        gameConfigSlot.set(gameConfig)
        gameActive = true
        nextEastSeat = ((snapshot.nextEastSeat % NUM_PLAYERS) + NUM_PLAYERS) % NUM_PLAYERS
        currentHandRecorded = snapshot.currentHandRecorded
        dealSeed = snapshot.dealSeed
        choiceRng = Random(dealSeed xor CHOICE_RNG_MIX)
        // Caller should prepare the table for a restored hand (no deal flight).
    }

    fun captureSnapshot(): SessionSnapshot? {
        if (!gameActive) return null
        val st = _state ?: return null
        return SessionSnapshot(
            scoreRows = scores.rows().map { row ->
                // Deep-copy arrays so later mutations cannot corrupt the payload.
                row.copy(
                    deltas = row.deltas.copyOf(),
                    runningTotals = row.runningTotals.copyOf(),
                )
            },
            game = st.copy(),
            nextEastSeat = nextEastSeat,
            currentHandRecorded = currentHandRecorded,
            dealSeed = dealSeed,
            rounds = gameConfig.rounds,
        )
    }

    /**
     * Write the `session.freeplay` key when [persistAllowed] and a live snapshot
     * exist. No-op during tutorial / menu / online or when inactive.
     */
    fun persist() {
        if (!persistAllowed()) return
        val snapshot = captureSnapshot() ?: return
        sessionSlot.set(snapshot)
    }

    /**
     * End free-play in memory and clear the `session.freeplay` key — an explicit
     * wipe of the saved game. Nothing in the app takes this path implicitly: the
     * tutorial keeps a local game, it does not end one. Does **not** consult
     * [persistAllowed] (always clears disk).
     */
    fun clearGame() {
        gameActive = false
        _state = null
        scores.clear()
        currentHandRecorded = true
        nextEastSeat = humanSeat
        sessionSlot.clear()
    }

    companion object {
        /** Mix into [dealSeed] so AI RNG is not identical to the deal shuffle. */
        private val CHOICE_RNG_MIX = 0x9E3779B97F4A7C15UL.toLong()
    }
}

/**
 * Outcome of [GameSession.dealHand] / [GameSession.startGame] so the UI layer
 * can update the table or open setup without the session owning libGDX widgets.
 */
sealed class DealResult {
    /** No active game — ignore. */
    data object Inactive : DealResult()

    /** Finite game already complete before deal — open New Game setup. */
    data object NeedsNewGameSetup : DealResult()

    /** Cap hit after recording the last hand — mark board complete, no deal. */
    data object GameCapReached : DealResult()

    /**
     * A live [GameSession.state] is ready.
     * @param restored true when loaded from snapshot (instant layout, no deal flight)
     */
    data class Dealt(val restored: Boolean) : DealResult()
}
