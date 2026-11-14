package inego.zungjunggame.engines

import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.GameState
import kotlin.random.Random

/** Result of a policy decision: index into the current legal [ChoiceList]. */
data class EngineDecision(
    val choiceIndex: Int = 0,
)

/**
 * Policy interface for playing a full game.
 *
 * Ported from zj-self-play `engines/playing_engine.h`.
 *
 * Hot path: [select] only (table AI, auto-play, self-play). Must stay cheap —
 * heuristic scoring, no search/playouts.
 *
 * [rank] is the same evaluation without RNG: all co-best indices. Used by HINT
 * (and later by a budgeted search that prunes roots). Implementing [rank] once
 * and deriving [select] avoids a second scoring pass.
 */
interface PlayingEngine {
    fun name(): String

    /**
     * All co-best legal choice indices into [choices] (ascending, no RNG).
     * Non-empty when [choices] is non-empty. Same scoring work as [select].
     */
    fun rank(state: GameState, choices: ChoiceList): IntArray

    /**
     * Select one legal choice index into [choices] (must be non-empty).
     * Default: [rank] then RNG among ties. Override only if you need a leaner
     * path that still matches [rank]'s co-best set.
     */
    fun selectIndex(state: GameState, choices: ChoiceList, rng: Random): Int {
        val best = rank(state, choices)
        check(best.isNotEmpty()) { "${name()} rank() returned empty for non-empty choices" }
        return if (best.size == 1) best[0] else best[rng.nextInt(best.size)]
    }

    fun select(state: GameState, choices: ChoiceList, rng: Random): EngineDecision =
        EngineDecision(selectIndex(state, choices, rng))

    /** Create an independent copy (for multi-threaded matches). */
    fun clone(): PlayingEngine
}
