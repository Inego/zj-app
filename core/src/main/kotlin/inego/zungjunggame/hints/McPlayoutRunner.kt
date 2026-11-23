package inego.zungjunggame.hints

import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engines.PlayingEngine

/**
 * One finished playout outcome from the human seat's perspective.
 *
 * @param scoreDelta settlement for [humanSeat] (−1390…+1440 typical)
 * @param won whether [humanSeat] won the hand
 * @param handValue winning pattern score when [won], else 0
 */
data class McPlayoutResult(
    val scoreDelta: Int,
    val won: Boolean,
    val handValue: Int,
)

/**
 * Runs a single DefaultEngine-style playout on a working [GameState].
 *
 * Call [runToTerminal] after the root choice is already applied on [work] and the
 * unknown tiles have been determinized. Uses [PlayingEngine.selectIndex] so
 * playouts match the engine's deterministic tie-breaking.
 *
 * Reuses one [ChoiceList] across decisions to avoid per-step allocations (critical
 * on mobile, where GC pressure hurts MC throughput far more than on desktop).
 */
class McPlayoutRunner(
    private val engine: PlayingEngine,
) {
    private val choices = ChoiceList()

    /**
     * Play [work] to terminal with [engine] for every seat.
     * Mutates [work]. Does not copy or determinize.
     */
    fun runToTerminal(work: GameState, humanSeat: Int): McPlayoutResult {
        var guard = 0
        while (!work.isTerminal()) {
            if (++guard > MAX_DECISIONS) break
            work.enumerateChoicesInto(choices)
            if (choices.isEmpty()) break
            if (work.isTrivialPassOnlyReaction(choices)) {
                work.applyChoice(choices[0])
                continue
            }
            val index = engine.selectIndex(work, choices)
            check(index in 0 until choices.size) {
                "${engine.name()} returned out-of-range index $index"
            }
            work.applyChoice(choices[index])
        }

        val deltas = work.computeScoreDeltas()
        val handScore = work.handScore
        val won = handScore > 0 && work.currentPlayer == humanSeat
        return McPlayoutResult(
            scoreDelta = deltas[humanSeat],
            won = won,
            handValue = if (won) handScore else 0,
        )
    }

    /**
     * Result when the root choice already ended the hand (no engine loop).
     */
    fun terminalResult(state: GameState, humanSeat: Int): McPlayoutResult {
        check(state.isTerminal())
        val deltas = state.computeScoreDeltas()
        val handScore = state.handScore
        val won = handScore > 0 && state.currentPlayer == humanSeat
        return McPlayoutResult(
            scoreDelta = deltas[humanSeat],
            won = won,
            handValue = if (won) handScore else 0,
        )
    }

    companion object {
        /** Safety cap: a normal ZJ hand cannot need this many decisions. */
        private const val MAX_DECISIONS = 512
    }
}
