package inego.zungjunggame.session.board

import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.ReactionResolution
import inego.zungjunggame.engine.reactionBidRank
import inego.zungjunggame.engine.resolveReactions
import inego.zungjunggame.session.online.ClaimBidder
import inego.zungjunggame.session.online.PresentationFrame
import inego.zungjunggame.session.online.PresentationSequencer
import inego.zungjunggame.session.online.PresentationTransition
import inego.zungjunggame.session.table.TableViewState

/**
 * The local analogue of the server's event production: it turns an applied
 * [Choice] into the [PresentationTransition]s the online client would have
 * received on the wire, and hands them to [PresentationSequencer].
 *
 * Beside [BoardController] rather than inside it — the controller is the apply
 * gate, this is the narration of what the gate applied. It is why the two hosts
 * can share one pipeline: the server states what happened at the table and the
 * client decides what it sounds like, and locally the table state *is* the engine.
 *
 * An emitter working from the applied [Choice] sees what a structural diff could
 * not: the window's kind, the tile it was taken from, and whether the seat drew a
 * supplement off a kong. It also needs no guard on [DecisionType] to know a
 * window closed — the window's bids are read *before* the apply and resolved with
 * the engine's own [resolveReactions], exactly as the server resolves its own.
 *
 * The engine settles nothing: a Hu resolved here is one apply, and the emitter
 * says both what happened (the window outcome) and that the hand is over. Two
 * transitions in one [PresentationFrame], so the sequencer's queue — not one
 * frame's worth of racing cues — gives them the two separate moments.
 */
class LocalEventEmitter(
    /** The shared presentation pipeline. Online play owns its own instance. */
    private val sequencer: PresentationSequencer,
    /** The local human's seat: whose stake and whose claim panel this is. */
    private val mySeat: () -> Int,
    /** 1-based hand number, so a deal is recognised as a deal. */
    private val handNumber: () -> Int,
) {

    /**
     * The facts an apply is narrated against, read while the board still holds
     * them. The engine resolves a window *inside* the apply and then clears it,
     * so a losing bid is only visible here.
     */
    class PreApply internal constructor(
        /** Whose decision this was — the seat whose choice is being applied. */
        val actorSeat: Int,
        /** Who the open window was about: the discarder, or a kong promoter. */
        val sourceSeat: Int,
        /** True when the actor was answering a reaction window. */
        val isWindowOpen: Boolean,
        /** Whether the actor held a parked drawn tile, for the discard's origin. */
        val actorHadDrawnTile: Boolean,
        /** Bids already submitted in the open window, by seat. */
        val submitted: Map<Int, Choice>,
        /** True when the viewer already had a cue-worthy prompt open before this apply. */
        val viewerPromptWasOpen: Boolean,
    )

    /** Read the pre-apply facts [emit] needs. Call before [GameState.applyChoice]. */
    fun capture(state: GameState): PreApply {
        val seat = mySeat()
        val isWindowOpen = state.decisionType == DecisionType.ReactToDiscard
        val submitted = HashMap<Int, Choice>(NUM_PLAYERS)
        if (isWindowOpen) {
            for (s in 0 until NUM_PLAYERS) {
                if (state.hasPendingReaction[s]) submitted[s] = state.pendingReactions[s]
            }
        }
        return PreApply(
            actorSeat = state.currentPlayer,
            sourceSeat = state.lastDiscarder,
            isWindowOpen = isWindowOpen,
            actorHadDrawnTile = state.lastDrawnPlayer == state.currentPlayer && state.lastDrawnTile >= 0,
            submitted = submitted,
            viewerPromptWasOpen = viewerPromptWasOpen(state, seat),
        )
    }

    /**
     * Whether the viewer's open decision is one this emitter would have cued. That
     * is what makes the next apply a *continuation* of an open prompt rather than
     * its opening: declining a concealed kong keeps the same Hu panel up, and it
     * must not re-sting.
     *
     * A pass-only reaction is not a cue-worthy prompt — no panel is shown — and an
     * `ActOnHand` prompt is only cue-worthy when it offers Hu.
     */
    private fun viewerPromptWasOpen(state: GameState, seat: Int): Boolean {
        if (state.isTerminal() || state.currentPlayer != seat) return false
        val choices = state.enumerateChoices()
        return when (state.decisionType) {
            DecisionType.ReactToDiscard -> !state.isTrivialPassOnlyReaction(choices)
            DecisionType.ActOnHand -> choices.asList().any { it.kind == ChoiceKind.Hu }
        }
    }

    /**
     * Narrate the apply of [choice] against [before], and pace whatever it
     * announced. Call after [GameState.applyChoice].
     */
    fun emit(before: PreApply, choice: Choice, state: GameState) {
        val transitions = if (before.isWindowOpen) {
            windowOutcome(before, choice, state)
        } else {
            handOutcome(before, choice)
        }
        if (transitions.isNotEmpty()) {
            sequencer.submit(
                PresentationFrame(
                    viewState = TableViewState.fromGameState(state, mySeat()),
                    handNumber = handNumber(),
                    transitions = transitions,
                ),
            )
        }
        cuePromptIfOpened(before, state)
    }

    /** Apply at most one paced step when the board is idle. */
    fun tryPresent(boardIdle: Boolean): Boolean = sequencer.tryStep(boardIdle)

    /** True while a queued step still has to be applied or its idle pause spent. */
    fun needsPresentFrame(): Boolean = !sequencer.isCaughtUp

    /** Drain the cues the paced steps produced. GL-free; the app plays them. */
    fun consumePendingSoundCues(): List<SoundCue> = sequencer.consumePendingSoundCues()

    /**
     * The reaction window this apply answered, if the apply closed it.
     *
     * A window collects one choice per seat after the discarder and resolves on
     * the last of them, so an apply only decides anything when it was the final
     * outstanding bid — the same condition the engine's own walk uses. Anything
     * earlier is a bid on record and announces nothing.
     */
    private fun windowOutcome(
        before: PreApply,
        choice: Choice,
        state: GameState,
    ): List<PresentationTransition> {
        if (before.submitted.size + 1 < NUM_PLAYERS - 1) return emptyList()
        val submitted = HashMap(before.submitted)
        submitted[before.actorSeat] = choice
        return when (val resolution = resolveReactions(before.sourceSeat, submitted)) {
            ReactionResolution.AllPass ->
                // An empty wall ends the hand without a draw: the hand end is the
                // whole announcement, and it is the only one.
                if (state.isTerminal()) {
                    listOf(PresentationTransition.HandEnd())
                } else {
                    listOf(PresentationTransition.Draw(state.currentPlayer))
                }
            is ReactionResolution.Winning -> {
                val bidders = bidders(submitted)
                if (resolution.choice.kind == ChoiceKind.Hu) {
                    listOf(
                        PresentationTransition.ReactionHu(resolution.seat, before.sourceSeat, bidders),
                        // The same apply ends the hand: locally there is no
                        // settlement broadcast to say so, and the queue is what
                        // keeps the sting and the win band off one frame.
                        PresentationTransition.HandEnd(resolution.seat, before.sourceSeat),
                    )
                } else {
                    listOf(
                        PresentationTransition.Claim(
                            seat = resolution.seat,
                            sourceSeat = before.sourceSeat,
                            kind = resolution.choice.kind.name,
                            bidders = bidders,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The hand action this apply made.
     *
     * A `Keep` declines a concealed kong and changes nothing anyone sees, so it
     * announces nothing; every other hand action is its own event.
     */
    private fun handOutcome(before: PreApply, choice: Choice): List<PresentationTransition> =
        when (choice.kind) {
            ChoiceKind.Discard -> listOf(
                PresentationTransition.Discard(before.actorSeat, isDrawn = before.actorHadDrawnTile),
            )
            ChoiceKind.ConcealedKong, ChoiceKind.PromoteKong ->
                listOf(PresentationTransition.DeclareKong(before.actorSeat))
            // A self-draw Hu had no window to contest, so the hand end is all
            // there is to report.
            ChoiceKind.Hu -> listOf(PresentationTransition.HandEnd(before.actorSeat))
            else -> emptyList()
        }

    /**
     * Every seat that bid on the window, not only the winner: a losing bid never
     * becomes a meld, so the award alone cannot say the window was contested.
     * Rank comes from the engine's own [reactionBidRank], so "a bid" means the
     * same thing here as it does in the resolver.
     */
    private fun bidders(submitted: Map<Int, Choice>): List<ClaimBidder> =
        submitted.entries
            .filter { reactionBidRank(it.value.kind) > 0 }
            .sortedBy { it.key }
            .map { ClaimBidder(it.key, it.value.kind.name) }

    /**
     * The prompt that just opened for the viewer, if any.
     *
     * Edge-triggered on the prompt *opening*, not on every apply while it stays
     * open: [PreApply.viewerPromptWasOpen] is the prompt that was already showing, so
     * a continuation — declining a concealed kong, say — does not re-cue. A
     * reaction walk that reaches [mySeat] with a real choice opens a claim panel;
     * a self-drawn Hu opens an `ActOnHand` panel. Hu-vs-attention is
     * [inego.zungjunggame.audio.SoundCues.promptOpen], the same call online makes.
     * The sequencer *holds* the cue until the discard or draw it answers has
     * finished displaying, so the prompt's blip follows that event instead of
     * sounding with it.
     */
    private fun cuePromptIfOpened(before: PreApply, state: GameState) {
        val seat = mySeat()
        if (state.isTerminal() || state.currentPlayer != seat) return
        if (before.viewerPromptWasOpen) return
        val choices = state.enumerateChoices()
        val isClaimPanel = state.decisionType == DecisionType.ReactToDiscard &&
            !state.isTrivialPassOnlyReaction(choices)
        val offersHu = choices.asList().any { it.kind == ChoiceKind.Hu }
        if (!isClaimPanel && !offersHu) return
        sequencer.cuePromptOpen(isClaimPanel = isClaimPanel, offersHu = offersHu)
    }

    companion object {
        /**
         * An emitter for a run with no table: it classifies every apply the same
         * way, but paces into a sequencer no frame loop presents and nobody drains.
         * The right answer where there is nothing to show and nothing to hear.
         */
        fun headless(): LocalEventEmitter = LocalEventEmitter(
            sequencer = PresentationSequencer(),
            mySeat = { 0 },
            handNumber = { 1 },
        )
    }
}