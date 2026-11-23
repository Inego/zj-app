package inego.zungjunggame.session.hosts

import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.engines.PlayingEngine
import inego.zungjunggame.persist.SessionSnapshot
import inego.zungjunggame.session.DealResult
import inego.zungjunggame.session.board.EngineNonHumanPicker
import inego.zungjunggame.session.GameSession
import inego.zungjunggame.session.LiveHand
import inego.zungjunggame.session.board.NonHumanTurnStepper
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.allowsFreePlayPersist
import inego.zungjunggame.session.allowsScoreTable
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.stack.SessionKind

/**
 * Free-play session host: multi-hand [inego.zungjunggame.session.GameSession] chrome, New Game setup,
 * hand-end / score table UI, and desktop MC hints.
 *
 * Tutorial lessons stay in [inego.zungjunggame.tutorial.TutorialHost]. The
 * composition root wires the host's own seams through constructor lambdas, and the
 * screen kind is **derived** from the stack floor by
 * [inego.zungjunggame.session.SessionRegistry] — the host reads it and never sets it. Board apply for the
 * human is still decided by the app layer (tutorial vs free-play commit paths);
 * this host owns post-apply chrome and free-play assist.
 *
 * A lesson keeps its own live board, so this session is left alone when the
 * tutorial opens or closes: its board and the `session.freeplay` key survive. The
 * mid-lesson save is kept when this host starts a game so Tutorial can resume.
 *
 * Render surfaces are injected as narrow ports
 * ([FreePlayScoreTablePort] / [FreePlayBreakdownPort] / [FreePlaySetupPort])
 * plus the [stopHint] callback; the concrete overlays live in the composition
 * root and the presentation logic in `features/board/FreePlayPresenter`.
 * Table restore/reset is published as [tableHandApply] for the frame snapshot.
 *
 * The host never decides a destination: opening or closing the menu, the ledger,
 * the setup card, or the board is a route the app pushes, and each surface comes
 * back when the one above it pops.
 */
class FreePlayHost(
    sessionSlot: StateSlot<SessionSnapshot>,
    gameConfigSlot: StateSlot<LocalGameConfig>,
    /**
     * The committed New Game config to open with. The cold start reads
     * `game.prefs` once (`AppState.game`) and hands the value in; no owner reads
     * it during the boot replay.
     */
    initialSetupDraft: LocalGameConfig,
    private val board: BoardController,
    /**
     * Advice policy: hints and Ctrl+Space AI assist always play this engine, so
     * coaching quality does not follow the opponents' level. Bot seats use
     * [engineForLevel] instead.
     */
    private val engine: PlayingEngine,
    /** The policy the bot seats play for a given [BotLevel]. */
    private val engineForLevel: (BotLevel) -> PlayingEngine,
    private val nonHumanStepper: NonHumanTurnStepper,
    private val scoreOverlay: FreePlayScoreTablePort,
    private val breakdown: FreePlayBreakdownPort,
    private val newGameSetup: FreePlaySetupPort,
    private val sessionKind: () -> SessionKind,
    /** Confirm abandon mid-hand before dealing the next hand. */
    private val requestConfirmNextHand: () -> Unit,
    /** Confirm abandon in-progress free-play before opening New Game setup. */
    private val requestConfirmNewGame: () -> Unit,
    /** Drop mid-lesson host + save before free-play setup Start. */
    private val clearTutorialForFreePlayStart: () -> Unit,
    /**
     * Park a live online table before free-play setup Start so the new
     * deal is what the table shows. The running seat stays on the server.
     */
    private val leaveOnlineForFreePlayStart: () -> Unit,
    /**
     * Tutorial AI gate: true when non-human seats may step (lesson still
     * [inego.zungjunggame.tutorial.TutorialDirector.Status.Running]).
     */
    private val allowTutorialAiStep: () -> Boolean,
    /**
     * Stop the free-play MC hint worker / clear its refresh clock. Supplied by
     * the composition root (owned by
     * [inego.zungjunggame.features.board.FreePlayPresenter]).
     */
    private val stopHint: () -> Unit,
    humanSeat: Int = 0,
    /**
     * Lazy persist gate for the owned [inego.zungjunggame.session.GameSession], evaluated per call so it
     * tracks the live [SessionKind] (free-play only). Defaults to allowing
     * writes for standalone hosts.
     */
    persistAllowed: () -> Boolean = { true },
) : Disposable {
    /** Multi-hand free-play lifecycle (also the [inego.zungjunggame.session.LiveHand] when not in tutorial). */
    val session = GameSession(
        sessionSlot = sessionSlot,
        gameConfigSlot = gameConfigSlot,
        humanSeat = humanSeat,
        persistAllowed = persistAllowed,
    )

    /**
     * In-memory New Game setup draft for this process.
     *
     * Seeded from the persisted config the boot read (`AppState.game`), or from
     * the restored/started game so setup matches the live board. Cancel keeps
     * edits here only (not persisted); Start and session restore write through
     * [GameSession].
     */
    var setupDraft: LocalGameConfig = initialSetupDraft
        private set

    /**
     * Consume-once table apply for the last deal. The composition root copies
     * this onto the frame's table inputs; the widget applies when the id changes.
     */
    var tableHandApply: TableHandApply = TableHandApply.None
        private set
    var tableHandApplyId: Long = 0L
        private set

    private fun publishTableApply(apply: TableHandApply) {
        tableHandApplyId++
        tableHandApply = apply
    }

    /** The deal's own shuffle cue, queued beside the board's apply cues. */
    private val pendingSoundCues = ArrayDeque<SoundCue>()

    /**
     * Drain this frame's local cues: the board's paced transitions plus a shuffle
     * for a fresh deal. The app plays them; the host only describes what happened.
     */
    fun consumePendingSoundCues(): List<SoundCue> {
        val deal = if (pendingSoundCues.isEmpty()) {
            emptyList()
        } else {
            pendingSoundCues.toList().also { pendingSoundCues.clear() }
        }
        return board.consumePendingSoundCues() + deal
    }

    /**
     * Apply at most one queued local presentation step when the board is idle —
     * the same queue, idle gate and pacing online play uses, so a resolved
     * reaction window and the hand end it may cause are two moments here too.
     */
    fun tryPresent(boardIdle: Boolean): Boolean = board.tryPresent(boardIdle)

    /**
     * True while a queued step still has to be applied or its idle pause spent.
     * Read after [tryPresent], the way [inego.zungjunggame.session.online.OnlinePlayHost.needsPresentFrame]
     * is.
     */
    fun needsPresentFrame(): Boolean = board.needsPresentFrame()

    /** Free-play non-human picker (restored when leaving tutorial). */
    fun engineNonHumanPicker(): EngineNonHumanPicker =
        EngineNonHumanPicker(board) { botEngine() }

    /**
     * The policy the bot seats play: the live game's level, resolved per decision
     * so a restored game resumes at the level it was started with.
     */
    fun botEngine(): PlayingEngine = engineForLevel(session.gameConfig.botLevel)

    /** Persist free-play snapshot only when [SessionKind] allows it. */
    fun persist() {
        if (!sessionKind().allowsFreePlayPersist()) return
        session.persist()
    }

    /** Open the session score table from a hand-end Scores button (or menu / T). */
    fun openScoreTable(liveHand: LiveHand) {
        if (!sessionKind().allowsScoreTable(liveHand.active)) return
        if (sessionKind() == SessionKind.FreePlay) {
            session.maybeRecordFinishedHand()
        }
        // The route port pushes the score-table route; its component shows the overlay.
        scoreOverlay.show()
    }

    fun showNewGameSetup() {
        breakdown.hide()
        scoreOverlay.hide()
        // Always dismissible: Cancel / Esc / click outside (see [dismissNewGameSetup]).
        // The setup component opens on this process draft when the route kept none.
        newGameSetup.show()
    }

    /**
     * Close setup without starting a game. Takes the card's draft and closes the
     * card in one step; keeps the value in memory for the next open this process,
     * and keeps the prior draft when the player never edited it. Does not write
     * prefs (Cancel is not commit).
     */
    fun dismissNewGameSetup() {
        newGameSetup.consumeDraft()?.let { setupDraft = it }
    }

    /** Apply a [inego.zungjunggame.session.DealResult] to the table / setup UI. */
    fun applyDealResult(result: DealResult) {
        when (result) {
            DealResult.Inactive -> Unit
            DealResult.NeedsNewGameSetup -> showNewGameSetup()
            DealResult.GameCapReached -> breakdown.hide()
            is DealResult.Dealt -> {
                breakdown.hide()
                stopHint()
                publishTableApply(
                    if (result.restored) TableHandApply.Restored else TableHandApply.Reset,
                )
                // A fresh deal shuffles; a restore just re-shows an existing wall.
                if (!result.restored) pendingSoundCues += SoundCue.QuickShuffle
                // Deal animation / restore settle first; AI resumes when tiles idle.
                nonHumanStepper.pauseUntilNextIdle = true
            }
        }
    }

    /**
     * Cold-start free-play restore from [snapshot], the value the boot read once
     * (`AppState.session`).
     * @return true when a live board was restored
     */
    fun tryRestore(snapshot: SessionSnapshot?): Boolean {
        if (snapshot == null) return false
        session.applySnapshot(snapshot)
        setupDraft = session.gameConfig
        applyDealResult(DealResult.Dealt(restored = true))
        return true
    }

    /**
     * New Game setup Start: take the card's draft and close it in one step, drop
     * the live tutorial runner (keep mid-lesson save) and any online table, enter
     * free-play, deal a fresh wall. An unedited card falls back to this process's
     * draft ([setupDraft]).
     */
    fun startFromSetup() {
        val config = newGameSetup.consumeDraft() ?: setupDraft
        setupDraft = config
        clearTutorialForFreePlayStart()
        leaveOnlineForFreePlayStart()
        applyDealResult(session.startGame(config))
    }

    /**
     * When tiles are idle and it is not the human's turn, advance computer play
     * via [nonHumanStepper].
     *
     * Call only after the board render so its animation state reflects the
     * latest reconcile.
     *
     * @return true when a choice was applied. Motion has already reconciled this
     * frame, so the caller owes another present for the flight to start. That
     * present is not [needsAiFrame]: the apply that hands the turn to the human
     * makes [needsAiFrame] false on this same frame.
     */
    fun tryStepAi(liveHand: LiveHand): Boolean {
        // Tutorial: only step non-humans while the lesson is still Running.
        if (sessionKind() == SessionKind.Tutorial) {
            if (!allowTutorialAiStep()) return false
        }
        if (!nonHumanStepper.tryStep(liveHand.state, liveHand.active)) return false
        stopHint()
        return true
    }

    /**
     * True while a bot decision is outstanding, so the app must keep presenting.
     *
     * A frame here is what lets [tryStepAi] run at all: it skips an idle frame after
     * each apply so the table can start tile motion, and that skip has to land on a
     * frame that exists. It also carries the think window to its deadline, which is
     * wall-clock and cannot wake the loop by itself. False as soon as the human owes
     * the decision, so a thinking player costs nothing.
     */
    fun needsAiFrame(liveHand: LiveHand): Boolean {
        return !(sessionKind() == SessionKind.Tutorial && !allowTutorialAiStep()) && nonHumanStepper.needsFrame(liveHand.state, liveHand.active)
    }

    /** Force the default engine to pick for the human's current decision. AI continues after anim. */
    fun aiAssistHuman(liveHand: LiveHand) {
        // Free-play assist must not bypass the director on a lesson.
        if (sessionKind() == SessionKind.Tutorial) return
        val state = liveHand.state ?: return
        if (!TableViewState.fromGameState(state, liveHand.humanSeat).isHumanInteractiveTurn) return
        if (!board.tryApplyEngineOrTrivialPass(state, engine)) return
        onHumanBoardApplied()
    }

    /** Shared post-apply chrome for human / assist commits. */
    fun onHumanBoardApplied() {
        stopHint()
        nonHumanStepper.pauseUntilNextIdle = true
    }

    /**
     * Next hand immediately if the board is finished; confirm abandon mid-hand.
     * On the last hand of a finite game, opens New Game setup instead.
     * No-op while in tutorial (Main routes tutorial New Game separately when needed).
     */
    fun requestNextHand(liveHand: LiveHand) {
        if (sessionKind() == SessionKind.Tutorial) return
        val state = liveHand.state
        if (!liveHand.active || state == null) {
            showNewGameSetup()
            return
        }
        session.maybeRecordFinishedHand()
        if (session.isGameComplete() || (state.isTerminal() && session.isLastHandOfGame())) {
            showNewGameSetup()
            return
        }
        if (state.isTerminal()) {
            applyDealResult(session.dealHand())
        } else {
            requestConfirmNextHand()
        }
    }

    /**
     * Menu / last-hand CTA: open New Game setup. Confirms first only when a
     * game is still in progress (scores would be abandoned). A finished finite
     * game skips the prompt — there is nothing mid-game left to protect.
     * No-op in tutorial or online — those contexts leave via Exit to main menu.
     */
    fun requestNewGame(liveHand: LiveHand) {
        if (sessionKind() != SessionKind.FreePlay) return
        if (!liveHand.active || liveHand.state == null) {
            showNewGameSetup()
            return
        }
        session.maybeRecordFinishedHand()
        if (session.isGameComplete()) {
            showNewGameSetup()
            return
        }
        requestConfirmNewGame()
    }

    /**
     * Park an in-progress local game and return to the root menu. Persist while
     * [SessionKind.FreePlay] still allows it.
     *
     * The move itself is the caller's (the app pops back to the floor); the host
     * only stops the session chrome, so no destination is decided here.
     */
    fun parkToMenu() {
        if (sessionKind() != SessionKind.FreePlay) return
        persist()
    }

    /**
     * Adopt the local session for a context entry: a parked in-memory game or a
     * saved snapshot becomes live, and no game exists otherwise.
     *
     * Where that lands — the local play menu, or the board above it — is the
     * caller's business ([LiveHand.active] answers which), so the host never
     * decides a destination.
     */
    fun enterLocalContext() {
        if (sessionKind() != SessionKind.Menu) return
        if (!session.active) tryRestore(session.persistedSnapshot())
    }

    /** After shared confirm Yes for abandon mid-hand. */
    fun onConfirmNextHand() {
        applyDealResult(session.dealHand())
    }

    /** After shared confirm Yes for new game mid-run. */
    fun onConfirmNewGame() {
        showNewGameSetup()
    }

    override fun dispose() {
        stopHint()
    }
}
