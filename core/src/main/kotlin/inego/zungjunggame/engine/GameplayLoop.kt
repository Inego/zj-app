package inego.zungjunggame.engine

import inego.zungjunggame.engines.PlayingEngine
import kotlin.random.Random

/**
 * Result of playing one full game to terminal.
 *
 * [scoreDeltas] is settlement for each seat (sum of opponent payments).
 * [handScore] is the winning pattern score, or 0 on a wall draw.
 * [winnerSeat] is the winning seat, or -1 on a draw.
 * [decisions] is the number of engine decisions made.
 */
data class GameResult(
    val scoreDeltas: IntArray,
    val handScore: Int,
    val winnerSeat: Int,
    val decisions: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameResult) return false
        return scoreDeltas.contentEquals(other.scoreDeltas) &&
            handScore == other.handScore &&
            winnerSeat == other.winnerSeat &&
            decisions == other.decisions
    }

    override fun hashCode(): Int {
        var result = scoreDeltas.contentHashCode()
        result = 31 * result + handScore
        result = 31 * result + winnerSeat
        result = 31 * result + decisions
        return result
    }
}

/**
 * Core gameplay loop: deal, enumerate legal choices, ask engines, apply, repeat.
 *
 * Ported from the play_game body in zj-self-play `modes/engine_match_mode.cpp`.
 * Not wired to any UI/frontend.
 */
object GameplayLoop {
    /**
     * Play one full game with [engines] seated by [seatToEngine]
     * (`seatToEngine[seat] = engineIndex`).
     *
     * @param initialRng used for the initial deal shuffle
     */
    fun playGame(
        engines: List<PlayingEngine>,
        seatToEngine: IntArray = IntArray(NUM_PLAYERS) { it % engines.size },
        initialRng: Random = Random(System.nanoTime()),
        eastSeat: Int = 0,
        allowChickenHands: Boolean = false,
    ): GameResult {
        require(engines.isNotEmpty()) { "at least one engine required" }
        require(seatToEngine.size == NUM_PLAYERS) {
            "seatToEngine must have $NUM_PLAYERS entries"
        }

        val state = GameState.generateRandom(
            rng = initialRng,
            eastSeat = eastSeat,
            allowChickenHands = allowChickenHands,
        )
        var decisions = 0

        while (!state.isTerminal()) {
            val choices = state.enumerateChoices()
            if (choices.isEmpty()) break

            val engineIndex = seatToEngine[state.currentPlayer]
            require(engineIndex in engines.indices) {
                "seatToEngine[${state.currentPlayer}] = $engineIndex out of range"
            }
            val engine = engines[engineIndex]
            val decision = engine.select(state, choices)
            require(decision.choiceIndex in 0 until choices.size) {
                "${engine.name()} returned out-of-range choice index ${decision.choiceIndex}"
            }

            state.applyChoice(choices[decision.choiceIndex])
            ++decisions
        }

        val handScore = state.handScore
        val winnerSeat = if (handScore > 0) {
            if (state.winnerSeat >= 0) state.winnerSeat else state.currentPlayer
        } else {
            -1
        }
        return GameResult(
            scoreDeltas = state.computeScoreDeltas(),
            handScore = handScore,
            winnerSeat = winnerSeat,
            decisions = decisions,
        )
    }

    /**
     * Convenience: all four seats use the same engine instance (shared policy, not clone).
     */
    fun playSoloEngineGame(
        engine: PlayingEngine,
        initialRng: Random = Random(System.nanoTime()),
    ): GameResult = playGame(
        engines = listOf(engine),
        seatToEngine = IntArray(NUM_PLAYERS) { 0 },
        initialRng = initialRng,
    )
}
