package inego.zungjunggame.session.board

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.PlayingEngine
import kotlin.random.Random

/**
 * Applies one decision for a non-human seat (or a scripted force).
 *
 * Free play uses [EngineNonHumanPicker] (the live game's policy via
 * [BoardController]). Tutorials may install a fixed / constrained picker without
 * changing the turn stepper loop in [NonHumanTurnStepper].
 */
fun interface NonHumanPicker {
    /**
     * Apply exactly one choice for the current decision (if possible).
     * @return true if [GameState.applyChoice] ran
     */
    fun applyOne(state: GameState): Boolean
}

/**
 * Free-play non-human policy: trivial pass-only shortcut, else the host's live
 * [PlayingEngine] pick among [BoardController.allowedChoices] (identity gate
 * unless a lesson gate is installed).
 *
 * [engine] is a provider, not a value: the host resolves the level from the
 * live game every decision, so Start and restore need no picker reinstall.
 */
class EngineNonHumanPicker(
    private val board: BoardController,
    private val engine: () -> PlayingEngine,
    private val rng: () -> Random,
) : NonHumanPicker {
    override fun applyOne(state: GameState): Boolean =
        board.tryApplyEngineOrTrivialPass(state, engine(), rng())
}
