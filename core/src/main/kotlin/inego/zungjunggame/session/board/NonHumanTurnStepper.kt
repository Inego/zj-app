package inego.zungjunggame.session.board

import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.ReactionContext

/**
 * Paces non-human turns: wait for tile animations, skip the next idle frame
 * after an apply until that motion has been seen, then apply via [picker].
 *
 * Every timed decision draws from [timing]: a bot's own turn, and the answers
 * to a discard. When no opponent can take the tile the table spends one dwell,
 * the slowest of their checks; otherwise each opponent that can take it spends
 * its own think time, and the rest pass at once. A window resolves when its
 * slowest answer is in, as online play does.
 *
 * Call [tryStep] only after the table has reconciled the latest state
 * (so [isAnimating] is accurate).
 */
class NonHumanTurnStepper(
    /** True when the human must act (interactive discard / claim / panel). */
    private val isHumanInteractiveTurn: (GameState) -> Boolean,
    /** True while discard/deal/claim motion is in flight. */
    private val isAnimating: () -> Boolean,
    /** Injectable policy; free play = [EngineNonHumanPicker]. */
    var picker: NonHumanPicker,
    /** Simulated time each timed seat spends on its answer. */
    private val timing: BotTiming,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Seat that is the local human; used so a claim panel is not held behind bot bids. */
    private val isHumanSeat: (Int) -> Boolean = { false },
    /** When true, bots skip simulated think/dwell delays and wait only for animations. */
    private val fastBots: () -> Boolean = { false },
) {
    private val idleGate = AnimationIdleGate()
    private val choicesBuf = ChoiceList()

    private var decisionKey: DecisionKey? = null
    private var decisionUntilMs: Long = 0L

    /** The open discard window: its context and when each seat's answer is due. */
    private var reactionWindow: ReactionWindow? = null

    /** A bot's own decision; the wall makes a kong supplement a new stint. */
    private data class DecisionKey(val player: Int, val wall: Int)

    /**
     * One discard window's simulated clock. A seat the simulation does not
     * time — the discarder, and the local human — has [UNTIMED] and never
     * holds the window.
     */
    private class ReactionWindow(
        val context: ReactionContext,
        val dueAtMs: LongArray,
    )

    companion object {
        private const val UNTIMED = Long.MIN_VALUE
    }

    /**
     * After a state change (human apply, deal, or this stepper's own apply),
     * skip the next idle [tryStep] so render can start tile motion first.
     * Same contract as the former Main `pauseAiUntilNextIdle` flag.
     */
    var pauseUntilNextIdle: Boolean
        get() = idleGate.pauseUntilNextIdle
        set(value) {
            idleGate.pauseUntilNextIdle = value
        }

    /**
     * True while this pacer still owes the loop a frame.
     *
     * Under `setContinuousRendering(false)` a frame is presented only when something
     * asks for one, and this pacer consumes frames in two places that nothing else
     * covers:
     *
     *  - the **idle-frame skip** in [tryStep]. It exists so the table can start tile
     *    motion before the next apply, which means the apply is *deferred to a frame
     *    that must exist*. Nothing used to request it; the board used to be pinned
     *    open by an unrelated always-on clock, which supplied the frame by accident.
     *  - the **simulated decision time**. [BotTiming] is wall-clock, so a deadline
     *    arrives on its own but cannot wake the loop: without a request, the bot
     *    simply never moves until the player touches something.
     *
     * Bounded, unlike the clock this replaced: it is true only while a non-human
     * turn is actually outstanding, so it costs frames across one bot decision or
     * one discard window and then goes quiet. A seat being active was the
     * unbounded version.
     */
    fun needsFrame(state: GameState?, active: Boolean): Boolean =
        hasNonHumanWork(state, active)

    /** A non-human decision is outstanding and the engine expects it to advance. */
    private fun hasNonHumanWork(state: GameState?, active: Boolean): Boolean =
        state != null && active && !state.isTerminal() && !isHumanInteractiveTurn(state)
    /**
     * Advance non-human play when the board is idle.
     *
     * - [DecisionType.ActOnHand]: sample one decision time, then one picker
     *   decision, then wait for animation.
     * - [DecisionType.ReactToDiscard]: sample every timed seat's answer once,
     *   then apply each as its own time elapses, in walk order. The window
     *   resolves when the last timed seat is in; a local human's panel is never
     *   held behind bot bids.
     *
     * @return true if at least one choice was applied this call
     */
    fun tryStep(state: GameState?, active: Boolean): Boolean {
        if (state == null || !active || state.isTerminal()) {
            clearTiming()
            return false
        }
        if (state.decisionType != DecisionType.ReactToDiscard) clearReactionWindow()
        if (!idleGate.allow(boardIdle = !isAnimating())) return false
        // The window opens once the discard has settled: its seats start
        // reading the tile then, and keep reading while the human decides.
        return when (state.decisionType) {
            DecisionType.ReactToDiscard -> {
                noteReactionWindow(state)
                if (isHumanInteractiveTurn(state)) false else stepReactions(state)
            }

            DecisionType.ActOnHand -> {
                if (isHumanInteractiveTurn(state)) {
                    clearDecision()
                    false
                } else {
                    stepActOnHand(state)
                }
            }
        }
    }

    private fun stepActOnHand(state: GameState): Boolean {
        if (!decisionElapsed(state)) return false
        val applied = picker.applyOne(state)
        if (applied) finishApplied() else clearDecision()
        return applied
    }

    private fun stepReactions(state: GameState): Boolean {
        var applied = false
        var guard = 0
        while (!state.isTerminal() &&
            state.decisionType == DecisionType.ReactToDiscard &&
            guard++ < 16
        ) {
            if (isHumanInteractiveTurn(state)) break
            // A human still to bid keeps its panel off the serial walk's clock:
            // the earlier seats' bids are invisible, so they may run ahead.
            if (!humanStillToBid(state) && !reactionDue(state)) break
            if (!picker.applyOne(state)) break
            applied = true
        }

        val closed = state.decisionType != DecisionType.ReactToDiscard
        if (closed) clearReactionWindow()
        if (applied && (closed || isHumanInteractiveTurn(state))) idleGate.armAfterApply()
        return applied
    }

    /** True when the seat the engine is on has finished its simulated reaction time. */
    private fun reactionDue(state: GameState): Boolean {
        if (fastBots()) return true
        val window = reactionWindow ?: return true
        val dueAt = window.dueAtMs[state.currentPlayer]
        return dueAt == UNTIMED || nowMs() >= dueAt
    }

    /**
     * Schedule every seat's answer when the window opens, after the discard
     * settles.
     *
     * A seat that cannot take the tile only confirms that. When no seat can,
     * the table spends one dwell — the slowest of the opponents' checks —
     * before play moves on. When someone can, every seat that can spends its
     * own think time, and the seats that cannot pass at once. The discarder
     * and the local human are untimed and never hold the window.
     */
    private fun noteReactionWindow(state: GameState) {
        if (fastBots()) {
            clearDecision()
            clearReactionWindow()
            return
        }
        val context = ReactionContext.fromState(state) ?: return
        if (reactionWindow?.context == context) return
        clearDecision()

        val canReact = BooleanArray(NUM_PLAYERS) { seat ->
            seat != context.sourceSeat && hasRealReaction(state, context, seat)
        }
        val anyoneCanReact = canReact.any { it }
        val now = nowMs()
        val dwellUntilMs = if (anyoneCanReact) 0L else now + tableDwellMs()
        val dueAtMs = LongArray(NUM_PLAYERS) { seat ->
            when {
                seat == context.sourceSeat || isHumanSeat(seat) -> UNTIMED
                !anyoneCanReact -> dwellUntilMs
                canReact[seat] -> now + timing.thinkMs()
                else -> now
            }
        }
        reactionWindow = ReactionWindow(context, dueAtMs)
    }

    /** The slowest of the opponents' glances; the table's pause when nobody can claim. */
    private fun tableDwellMs(): Long = (1 until NUM_PLAYERS).maxOf { timing.dwellMs() }

    private fun hasRealReaction(state: GameState, context: ReactionContext, seat: Int): Boolean {
        state.enumerateReactions(context, seat, choicesBuf)
        return !state.isTrivialPassOnlyReaction(choicesBuf)
    }

    /**
     * True when a local human still owes a non-trivial bid in this window.
     * Independent of [GameState.currentPlayer]: local collection is serial.
     */
    private fun humanStillToBid(state: GameState): Boolean {
        if (state.decisionType != DecisionType.ReactToDiscard) return false
        val ctx = ReactionContext.fromState(state) ?: return false
        for (step in 1 until NUM_PLAYERS) {
            val seat = (state.lastDiscarder + step) % NUM_PLAYERS
            if (state.hasPendingReaction[seat]) continue
            if (!isHumanSeat(seat)) continue
            state.enumerateReactions(ctx, seat, choicesBuf)
            return !state.isTrivialPassOnlyReaction(choicesBuf)
        }
        return false
    }

    private fun decisionElapsed(state: GameState): Boolean {
        if (fastBots()) {
            clearDecision()
            return true
        }
        val key = DecisionKey(state.currentPlayer, state.liveWallSize)
        if (key != decisionKey) {
            decisionKey = key
            decisionUntilMs = nowMs() + timing.thinkMs()
        }
        return nowMs() >= decisionUntilMs
    }

    private fun finishApplied() {
        clearDecision()
        idleGate.armAfterApply()
    }

    private fun clearDecision() {
        decisionKey = null
        decisionUntilMs = 0L
    }

    private fun clearReactionWindow() {
        reactionWindow = null
    }

    private fun clearTiming() {
        clearDecision()
        clearReactionWindow()
    }
}
