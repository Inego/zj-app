package inego.zungjunggame.engines

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.GameState

/** Result of a policy decision: index into the current legal [ChoiceList]. */
data class EngineDecision(
    val choiceIndex: Int = 0,
)

/**
 * Policy interface for playing a full game.
 *
 * Ported from zj-self-play `engines/playing_engine.h`.
 *
 * Hot path: [select] / [selectIndex] only (table AI, auto-play, self-play). Must
 * stay cheap — heuristic scoring, no search/playouts.
 *
 * [rank] returns all co-best indices (no RNG), used by HINT (and later by a
 * budgeted search that prunes roots). [selectIndex] / [select] pick one move
 * deterministically from [rank]'s co-best set with no RNG, using the same
 * canonical tie order as `zj-server`'s Shadow Ranked bot (`Pass`, then `Pung` /
 * `Kong` / lowest-first `Chow` on reactions; `ConcealedKong` before `Keep`, and
 * ascending tile id with `Discard` before `PromoteKong` on hand actions).
 */
interface PlayingEngine {
    fun name(): String

    /**
     * All co-best legal choice indices into [choices] (ascending, no RNG).
     * Non-empty when [choices] is non-empty. Same scoring work as [select].
     */
    fun rank(state: GameState, choices: ChoiceList): IntArray

    /**
     * Select one legal choice index into [choices] (must be non-empty),
     * deterministically with no RNG.
     *
     * Default: [rank], breaking ties by [canonicalTieOrder] so the pick is
     * independent of [inego.zungjunggame.engine.PlayerState.activeConcealedTiles]
     * cache order and matches `zj-server`'s Shadow Ranked bot. Override only if
     * you need a leaner path that picks the exact same index.
     */
    fun selectIndex(state: GameState, choices: ChoiceList): Int {
        val best = rank(state, choices)
        check(best.isNotEmpty()) { "${name()} rank() returned empty for non-empty choices" }
        if (best.size == 1) return best[0]
        var winner = best[0]
        var winnerOrder = canonicalTieOrder(choices[winner])
        for (i in 1 until best.size) {
            val idx = best[i]
            val order = canonicalTieOrder(choices[idx])
            if (order < winnerOrder) {
                winner = idx
                winnerOrder = order
            }
        }
        return winner
    }

    fun select(state: GameState, choices: ChoiceList): EngineDecision =
        EngineDecision(selectIndex(state, choices))

    /** Create an independent copy (for multi-threaded matches). */
    fun clone(): PlayingEngine

    companion object {
        /**
         * Canonical tie-break order matching `zj-rules` enumeration order:
         * - `ActOnHand`: `Hu` < `ConcealedKong` < `Keep` < `Discard(t)` < `PromoteKong(t)`
         *   in ascending tile id `t` (independent of swap-remove cache order).
         * - `ReactToDiscard`: `Pass` < `Hu` < `Pung` < `Kong` < `Chow(first)` in
         *   ascending `first`.
         */
        internal fun canonicalTieOrder(choice: Choice): Int = when (choice.kind) {
            ChoiceKind.Pass -> 0
            ChoiceKind.Hu -> 1
            ChoiceKind.ConcealedKong -> 2
            ChoiceKind.Keep -> 3
            ChoiceKind.Discard -> 100 + choice.payload * 2
            ChoiceKind.PromoteKong -> 100 + choice.payload * 2 + 1
            ChoiceKind.Pung -> 200 + choice.payload
            ChoiceKind.Kong -> 300 + choice.payload
            ChoiceKind.Chow -> 400 + choice.payload
        }
    }
}
