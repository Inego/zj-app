package inego.zungjunggame.session.board

import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.PlayingEngine

/**
 * Single apply-choice path for human input and engine picks.
 *
 * Flow:
 * 1. `legal = state.enumerateChoices()`
 * 2. `allowed = gate.filter(legal)` (free play: [ChoiceGate.Companion.Identity])
 * 3. Commit only if the requested action is among [allowed]
 * 4. Always [inego.zungjunggame.engine.GameState.applyChoice] with the engine's [inego.zungjunggame.engine.Choice] instance
 * 5. Hand the applied choice to [LocalEventEmitter], which narrates it as the
 *    same presentation transitions the online client receives on the wire
 *
 * Does not own UI, hints, or animation pacing — callers clear chrome after a
 * successful apply, and the emitter's sequencer does the pacing.
 */
class BoardController(
    /** Mutable so a tutorial director can install / clear a gate without rebuilding Main. */
    var gate: ChoiceGate = ChoiceGate.Identity,
    /**
     * Narrates each apply as presentation transitions for the shared
     * [inego.zungjunggame.session.online.PresentationSequencer].
     *
     * Defaults to a headless pipeline: it still classifies every apply, so the
     * emitted transitions are the same either way, but no frame loop presents them
     * and nothing drains its cues. That is right for a run with no table — a
     * headless lesson, a script — and wrong for play, so the composition root
     * supplies the sequencer the frame loop actually drives.
     */
    private val emitter: LocalEventEmitter = LocalEventEmitter.headless(),
) {
    /** Engine-legal choices for the current decision (ungated). */
    fun legalChoices(state: GameState): List<Choice> =
        state.enumerateChoices().asList()

    /** Apply at most one paced presentation step when the board is idle. */
    fun tryPresent(boardIdle: Boolean): Boolean = emitter.tryPresent(boardIdle)

    /** True while a queued presentation step still owes a frame. */
    fun needsPresentFrame(): Boolean = emitter.needsPresentFrame()

    /** Drain the cues the paced steps produced. GL-free; the app plays them. */
    fun consumePendingSoundCues(): List<SoundCue> = emitter.consumePendingSoundCues()

    /**
     * The single commit: apply [choice], then hand it to the emitter. Every local
     * apply — human, engine, auto-assist — funnels through here, so the
     * presentation stream has one owner.
     */
    private fun commit(state: GameState, choice: Choice) {
        val before = emitter.capture(state)
        state.applyChoice(choice)
        emitter.emit(before, choice, state)
    }

    /** Legal choices after [gate] (what the human path may commit). */
    fun allowedChoices(state: GameState): List<Choice> =
        gate.filter(legalChoices(state))

    /**
     * Apply [requested] if it matches an **allowed** choice (kind + payload).
     * Uses the allowed engine [Choice] object, not a caller-built one.
     */
    fun tryApply(state: GameState, requested: Choice): Boolean {
        val match = allowedChoices(state).firstOrNull { matches(it, requested) } ?: return false
        commit(state, match)
        return true
    }

    /**
     * Apply a discard of [tileType] if that discard is allowed.
     */
    fun tryApplyDiscard(state: GameState, tileType: Int): Boolean {
        if (tileType < 0) return false
        val match = allowedChoices(state).firstOrNull {
            it.kind == ChoiceKind.Discard && it.tile() == tileType
        } ?: return false
        commit(state, match)
        return true
    }

    /**
     * Pass-only shortcut when the engine offers only Pass; otherwise one
     * [inego.zungjunggame.engines.PlayingEngine] pick among **allowed** choices.
     *
     * Free play (identity gate): same behavior as the former Main helpers.
     * Gated human auto-assist: cannot pick a choice the lesson forbids.
     */
    fun tryApplyEngineOrTrivialPass(
        state: GameState,
        engine: PlayingEngine,
    ): Boolean {
        val legal = state.enumerateChoices()
        if (legal.isEmpty()) return false

        if (state.isTrivialPassOnlyReaction(legal)) {
            val pass = allowedChoices(state).firstOrNull { it.kind == ChoiceKind.Pass }
                ?: return false
            commit(state, pass)
            return true
        }

        val allowed = allowedChoices(state)
        if (allowed.isEmpty()) return false
        val list = toChoiceList(allowed)
        val decision = engine.select(state, list)
        val index = decision.choiceIndex.coerceIn(0, list.size - 1)
        commit(state, list[index])
        return true
    }

    companion object {
        /** Kind + payload equality (same as free-play [sameChoice] in Main). */
        fun matches(a: Choice, b: Choice): Boolean =
            a.kind == b.kind && a.payload == b.payload

        fun toChoiceList(choices: List<Choice>): ChoiceList {
            val list = ChoiceList()
            for (c in choices) list.pushBack(c)
            return list
        }
    }
}
