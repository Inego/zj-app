package inego.zungjunggame.session.online

import inego.zungjunggame.audio.ClaimSoundKind
import inego.zungjunggame.audio.ClaimStake
import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.audio.SoundCues
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.board.AnimationIdleGate
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.table.DiscardOrigin

/**
 * One seat's claim bid on a reaction window that has resolved.
 *
 * Transport-neutral, like [PresentationTransition]: a seat and the bid as the rules
 * name it (`Chow` / `Pung` / `Kong` / `Hu`), so a wire event and a local apply
 * report the same participants without either producer fabricating the other's
 * type. Claims are called aloud — this is table state, not a leak.
 *
 * [kind] is carried, not consumed: the stake rule ([inego.zungjunggame.audio.SoundCues.stakeOf])
 * reads only the seats, and this is where "I was outbid by a Hu" stays
 * distinguishable from "outbid by a Pung" for whoever reads it next.
 */
data class ClaimBidder(val seat: Int, val kind: String)

/**
 * Semantic transition describing a discrete visible change on the table.
 *
 * Transport-neutral by design: it names seats and roles, never a wire type, so
 * every host that produces table events feeds the same sequencer.
 */
sealed class PresentationTransition {
    data class Draw(val seat: Int) : PresentationTransition()
    data class Discard(
        val seat: Int,
        val slotIndex: Int? = null,
        val isDrawn: Boolean = false,
    ) : PresentationTransition()

    /**
     * A meld claim was awarded. [bidders] is every seat that bid on the window it
     * closed, so the viewer knows whether it was contested without inferring it.
     */
    data class Claim(
        val seat: Int,
        val sourceSeat: Int,
        val kind: String,
        val bidders: List<ClaimBidder>,
    ) : PresentationTransition()
    data class DeclareKong(val seat: Int) : PresentationTransition()

    /** A reaction window resolved to a Hu — not a hand end; that is `HandEnd`. */
    data class ReactionHu(
        val winnerSeat: Int,
        val sourceSeat: Int,
        val bidders: List<ClaimBidder>,
    ) : PresentationTransition()
    data class HandEnd(val winnerSeat: Int? = null, val winSource: Int? = null) : PresentationTransition()
    data class SnapshotSync(val snapshot: TableViewState) : PresentationTransition()
}

/**
 * One frame of table events from a producer: what the table looks like now, which
 * hand it is, and the transitions this update announces, in order.
 *
 * This is the transport-neutral input [PresentationSequencer] paces. Online builds
 * it from a wire snapshot ([toPresentationFrame]); local play builds it from the
 * apply it just committed ([inego.zungjunggame.session.board.LocalEventEmitter]).
 *
 * One [PresentationTransition] per moment, so a frame that resolves a Hu *and*
 * ends the hand carries both and the queue gives them two steps.
 */
data class PresentationFrame(
    /** The table as this update leaves it, already reconciled with any open prompt. */
    val viewState: TableViewState,
    /** 1-based hand number; a change is a deal, not a move. */
    val handNumber: Int,
    /** What this update announced, in order. Empty is a state update with nothing to show. */
    val transitions: List<PresentationTransition>,
)

/**
 * A discrete presentation step applied when the table is idle.
 */
data class PresentationStep(
    val transition: PresentationTransition,
    val targetState: TableViewState,
)

/**
 * The one presentation pipeline: it turns a producer's [PresentationFrame] into
 * paced, sequential visual tile animations.
 *
 * Both modes submit frames — online from a server snapshot
 * ([inego.zungjunggame.session.online.toPresentationFrame]), local from an applied
 * choice ([inego.zungjunggame.session.board.LocalEventEmitter]) — so a moment is
 * queued and cued identically whichever host produced it.
 *
 * Playback uses the same idle gate as local [inego.zungjunggame.session.board.NonHumanTurnStepper]: one step
 * per settled board. The gate skips the next idle frame only until that step's
 * motion has been seen, so a draw submitted as the discard lands sounds with it.
 * Queue depth does not change flight duration.
 *
 * ```text
 * A producer's frame
 *     ├── Authoritative client model: update immediately
 *     └── Presentation sequencer: animate in order
 *                                   ↓
 *                             PlayTableView
 * ```
 */
class PresentationSequencer(
    private val maxQueuedSteps: Int = MAX_QUEUED_STEPS,
) {
    companion object {
        const val MAX_QUEUED_STEPS = 30
    }

    /** Currently displayed table view state for [inego.zungjunggame.render.table.PlayTableView]. */
    var displayedViewState: TableViewState? = null
        private set

    /** Currently displayed actionable decision (only latched once preceding steps finish). */
    var displayedDecision: ActionableDecision? = null
        private set

    private var pendingDecisionPrompt: ActionableDecision? = null

    /**
     * A prompt-open cue held until the steps it interrupts have drained, so the
     * event that opened the prompt always sounds first. Set by [cuePromptOpen]
     * and released by [latchPendingPromptCue], the cue's counterpart to
     * [pendingDecisionPrompt]'s latch.
     */
    private var pendingPromptCue: SoundCue? = null

    private val queue = ArrayDeque<PresentationStep>()
    private val idleGate = AnimationIdleGate()

    /**
     * The wire snapshot the last online enqueue consumed. It is the *diff baseline*
     * for event-less snapshots ([toPresentationFrame]) and nothing else; the paced
     * pipeline below only ever reads [PresentationFrame].
     */
    private var previousReceivedSnapshot: SeatSnapshotWire? = null

    /** Hand number of the last frame submitted, for the deal boundary. */
    private var previousHandNumber: Int? = null

    /**
     * Sound cues for the transitions this sequencer applied (and a shuffle when a
     * new hand is synced). Drained once by the frame loop, so `session/` stays
     * GL-free and only describes what happened; the app plays it.
     */
    private val pendingSoundCues = ArrayDeque<SoundCue>()

    /** Drain the cues produced since the last call, in application order. */
    fun consumePendingSoundCues(): List<SoundCue> {
        if (pendingSoundCues.isEmpty()) return emptyList()
        val drained = pendingSoundCues.toList()
        pendingSoundCues.clear()
        return drained
    }

    /** Pending discard origin slot to prefer in TileBoard on the next render. */
    var pendingDiscardOrigin: DiscardOrigin? = null
        private set

    fun consumePendingDiscardOrigin(): DiscardOrigin? {
        val origin = pendingDiscardOrigin
        pendingDiscardOrigin = null
        return origin
    }

    /** Number of steps not yet applied to [displayedViewState]. */
    val queueSize: Int
        get() = queue.size

    /**
     * True when nothing is queued and the post-apply pause has been consumed.
     * A pending prompt may be latched in this state.
     */
    val isCaughtUp: Boolean
        get() = queue.isEmpty() && !idleGate.pauseUntilNextIdle

    /** Reset sequencer state (e.g. on leave or reconnect). */
    fun clear() {
        queue.clear()
        displayedViewState = null
        displayedDecision = null
        pendingDecisionPrompt = null
        pendingPromptCue = null
        pendingDiscardOrigin = null
        pendingSoundCues.clear()
        idleGate.reset()
        previousReceivedSnapshot = null
        previousHandNumber = null
    }

    /**
     * Ingest an authoritative snapshot received from the server.
     *
     * The wire → [PresentationFrame] translation is [toPresentationFrame]'s; this is
     * only the online *policy* around it — a first or new-hand snapshot syncs the
     * display directly, an overflowing batch catches up — before the frame is paced
     * through the same pipeline local play uses.
     *
     * [isOpeningDeal] marks this connection's first snapshot as the match's opening
     * deal because the attach itself started it
     * ([inego.zungjunggame.session.online.OnlinePlayHost.runningAtAttach] false). A join
     * to a table that was already running, or a reconnect, rematerializes a position
     * instead and passes false.
     */
    fun enqueueSnapshot(
        snapshot: SeatSnapshotWire,
        isWaitingForOthers: Boolean = false,
        isOpeningDeal: Boolean = false,
    ) {
        val prev = previousReceivedSnapshot
        val newHand = prev != null && prev.handNumber != snapshot.handNumber

        if (displayedViewState == null || prev == null || newHand) {
            // First snapshot or new hand: sync displayed state directly without transit delay.
            queue.clear()
            idleGate.reset()
            displayedViewState = snapshot.toTableViewState(null, isWaitingForOthers)
            previousReceivedSnapshot = snapshot
            previousHandNumber = snapshot.handNumber
            // The old hand's panel is gone with it: a cue still held for it must
            // not blip into the new deal.
            pendingPromptCue = null
            // A new hand is a fresh deal, and so is the opening deal an attaching
            // player started — it has no previous hand to differ from. A join to a
            // running table rematerializes a position and is silent.
            if (newHand || isOpeningDeal) pendingSoundCues += SoundCue.QuickShuffle
            return
        }

        if (queue.size >= maxQueuedSteps) {
            fastForward(snapshot, isWaitingForOthers)
            return
        }

        submit(snapshot.toPresentationFrame(prev))
        previousReceivedSnapshot = snapshot
    }

    /**
     * Pace every transition [frame] announces, one step each, in order.
     *
     * The transport-neutral entry: a producer hands over what happened and this
     * decides when it is shown. Steps carry the frame's table state as their
     * target, with the waiting flag cleared — an open prompt latches separately,
     * after the queue it was waiting behind has drained.
     *
     * A change of [PresentationFrame.handNumber] is a deal rather than a move:
     * nothing from the last hand animates across it, so the queue is dropped and
     * the display snapped — and the deal's own first moves still pace normally.
     */
    fun submit(frame: PresentationFrame) {
        val prevHand = previousHandNumber
        previousHandNumber = frame.handNumber
        if (prevHand != null && prevHand != frame.handNumber) {
            queue.clear()
            idleGate.reset()
            displayedViewState = frame.viewState
            pendingPromptCue = null
        }
        if (frame.transitions.isEmpty()) return
        val targetState = frame.viewState.copy(isWaitingForOthers = false)
        for (transition in frame.transitions) {
            queue.add(PresentationStep(transition, targetState))
        }
    }

    /**
     * Cue the opening of a decision prompt, edge-triggered: the producer says the
     * prompt just opened for the viewer, and [SoundCues.promptOpen] decides
     * Hu-sting vs attention blip for every producer alike.
     *
     * The cue is held, not sounded: a prompt opens because a discard or draw was
     * just applied, and local play cues it *inside* that apply, before its step has
     * been presented. [latchPendingPromptCue] releases it once the queue drains, so
     * the event sounds first. A producer that is already caught up when it cues
     * (online's prompt latch, a resync) latches at once and is not delayed.
     */
    fun cuePromptOpen(isClaimPanel: Boolean, offersHu: Boolean) {
        pendingPromptCue = SoundCues.promptOpen(isClaimPanel, offersHu)
        if (isCaughtUp) latchPendingPromptCue()
    }

    /**
     * Enqueue or update an actionable decision prompt from the server.
     * Latches to [displayedDecision] only when the sequencer is caught up.
     */
    fun enqueuePrompt(decision: ActionableDecision) {
        pendingDecisionPrompt = decision
        if (isCaughtUp) latchPendingPrompt()
    }

    /** Clear any open prompt (e.g. when human submits a choice). */
    fun clearPrompt(isWaitingForOthers: Boolean = false) {
        pendingDecisionPrompt = null
        pendingPromptCue = null
        displayedDecision = null
        displayedViewState = displayedViewState?.copy(
            isHumanDiscardTurn = false,
            isHumanClaimTurn = false,
            isHumanActOnHandPanel = false,
            isWaitingForOthers = isWaitingForOthers,
        )
    }

    /**
     * Apply at most one queued step when the board is idle, sharing the idle gate with local
     * [inego.zungjunggame.session.board.NonHumanTurnStepper] pacing. Latches a pending
     * prompt and any held claim cue once caught up.
     *
     * @return true if a presentation step was applied
     */
    fun tryStep(boardIdle: Boolean): Boolean {
        if (!idleGate.allow(boardIdle)) {
            if (boardIdle && queue.isEmpty()) {
                latchPendingPrompt()
                latchPendingPromptCue()
            }
            return false
        }
        if (queue.isEmpty()) {
            latchPendingPrompt()
            latchPendingPromptCue()
            return false
        }
        val step = queue.removeFirst()
        applyStep(step)
        idleGate.armAfterApply()
        return true
    }

    /**
     * Fast-forward immediately to the authoritative state (resync, new hand, leave).
     */
    fun fastForward(
        authoritativeSnapshot: SeatSnapshotWire? = null,
        isWaitingForOthers: Boolean = false,
    ) {
        queue.clear()
        pendingPromptCue = null
        pendingDiscardOrigin = null
        idleGate.reset()
        if (authoritativeSnapshot != null) {
            displayedViewState = authoritativeSnapshot.toTableViewState(
                pendingDecisionPrompt,
                isWaitingForOthers,
            )
            previousReceivedSnapshot = authoritativeSnapshot
        }
        // A resync delivers the pending prompt the same way a latch does; cue it
        // on the same edge, so a prompt caught in a queue overflow is not silent.
        val latched = pendingDecisionPrompt
        val previous = displayedDecision
        displayedDecision = latched
        if (latched != null && latched != previous) cueDecisionPrompt(latched)
    }

    private fun latchPendingPrompt() {
        val pending = pendingDecisionPrompt ?: return
        if (displayedDecision == pending) return
        displayedDecision = pending
        displayedViewState = displayedViewState?.copy(
            myChoices = pending.choices,
            isHumanDiscardTurn = pending.isDiscard,
            isHumanClaimTurn = pending.isClaim,
            isHumanActOnHandPanel = pending.isActOnHandPanel,
            isWaitingForOthers = false,
        )
        cueDecisionPrompt(pending)
    }

    /**
     * Sound a held prompt cue now that the steps it was queued behind have
     * drained.
     *
     * A producer only says the prompt opened; this is what places the cue *after*
     * the discard or draw that opened it. [cuePromptOpen] latches immediately when
     * it is called caught up — online's [latchPendingPrompt] and [fastForward] both
     * are — so the cue held here is one a producer cued mid-apply, as local play
     * does.
     */
    private fun latchPendingPromptCue() {
        val cue = pendingPromptCue ?: return
        pendingPromptCue = null
        pendingSoundCues += cue
    }

    /**
     * The prompt-open cue for a latched decision, in the producer's own terms.
     * Online knows the panel from the prompt's [ActionableDecision.isClaim]; local
     * play reads it off its engine. Both land in [cuePromptOpen], so
     * Hu-vs-attention is decided once by [SoundCues.promptOpen]. [offersHu] is
     * passed regardless of panel kind: a self-drawn Hu is an `ActOnHand` prompt,
     * not a claim.
     */
    private fun cueDecisionPrompt(decision: ActionableDecision) {
        cuePromptOpen(
            isClaimPanel = decision.isClaim,
            offersHu = decision.choices.asList().any { it.kind == ChoiceKind.Hu },
        )
    }

    private fun applyStep(step: PresentationStep) {
        displayedViewState = step.targetState
        val transition = step.transition
        if (transition is PresentationTransition.Discard) {
            pendingDiscardOrigin = if (transition.isDrawn) {
                DiscardOrigin.drawn(transition.seat)
            } else if (transition.slotIndex != null) {
                DiscardOrigin.hand(transition.seat, transition.slotIndex)
            } else {
                null
            }
        }
        soundCueFor(transition, step.targetState)?.let { pendingSoundCues += it }
    }

    /**
     * The cue a transition reads as, from the viewer's seat. `session/` owns the
     * mapping from its own transition vocabulary; the banding and who/from-whom
     * logic is [SoundCues]'.
     */
    private fun soundCueFor(
        transition: PresentationTransition,
        state: TableViewState,
    ): SoundCue? {
        val mySeat = state.mySeat
        return when (transition) {
            is PresentationTransition.Draw -> SoundCues.draw(transition.seat, mySeat)
            is PresentationTransition.Discard -> SoundCues.discard(transition.seat, mySeat)
            is PresentationTransition.Claim -> SoundCues.claim(
                claimKindOf(transition.kind),
                transition.seat,
                transition.sourceSeat,
                mySeat,
                SoundCues.stakeOf(transition.seat, transition.bidders, mySeat),
            )
            is PresentationTransition.DeclareKong ->
                SoundCues.claim(ClaimSoundKind.Kong, transition.seat, -1, mySeat, ClaimStake.None)

            // A Hu window is its own moment: the sting stands alone and the win band
            // arrives later, with the hand end that settles the hand.
            is PresentationTransition.ReactionHu -> SoundCue.Sting(
                SoundCues.stakeOf(transition.winnerSeat, transition.bidders, mySeat),
            )
            is PresentationTransition.HandEnd -> {
                val winner = transition.winnerSeat
                if (winner == null || state.handScore <= 0) {
                    SoundCue.ExhaustiveDraw
                } else {
                    SoundCues.win(
                        state.handScore,
                        winner,
                        transition.winSource ?: -1,
                        mySeat,
                    )
                }
            }
            is PresentationTransition.SnapshotSync -> null
        }
    }

    private fun claimKindOf(kind: String): ClaimSoundKind = when (kind.lowercase()) {
        "chow" -> ClaimSoundKind.Chow
        "kong" -> ClaimSoundKind.Kong
        else -> ClaimSoundKind.Pung
    }
}
