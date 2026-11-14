package inego.zungjunggame.hints

import inego.zungjunggame.engine.Choice

/**
 * Running stats for one root choice (bandit arm) under UCB flat MC.
 *
 * Score deltas are from the human seat's perspective (ZJ settlement range
 * roughly −1390…+1440). [sumWinHandValue] only accumulates when that seat wins.
 */
class McHintArmStats {
    var n: Int = 0
        private set
    var sumDelta: Long = 0L
        private set
    var wins: Int = 0
        private set
    var sumWinHandValue: Long = 0L
        private set

    /** True when the root choice ends the hand immediately (e.g. Hu). */
    var terminalRoot: Boolean = false

    fun avgDelta(): Double = if (n == 0) 0.0 else sumDelta.toDouble() / n

    fun winRate(): Double = if (n == 0) 0.0 else wins.toDouble() / n

    /** Average hand value over won hands only; 0 if none won. */
    fun avgHandValueWon(): Double =
        if (wins == 0) 0.0 else sumWinHandValue.toDouble() / wins

    fun record(delta: Int, won: Boolean, handValue: Int) {
        n += 1
        sumDelta += delta.toLong()
        if (won) {
            wins += 1
            sumWinHandValue += handValue.toLong()
        }
    }
}

/** Immutable snapshot of one arm for UI / tests (thread-safe to pass around). */
data class McHintArmSnapshot(
    val choice: Choice,
    val n: Int,
    val avgDelta: Double,
    val winRate: Double,
    val avgHandValueWon: Double,
    val terminalRoot: Boolean,
)

/** Full search snapshot published to the main thread. */
data class McHintSnapshot(
    val arms: List<McHintArmSnapshot>,
    val totalPlayouts: Int,
    /**
     * Indices into [arms] that currently share the best average score delta
     * (empty when no samples yet).
     */
    val bestIndices: IntArray,
) {
    fun bestChoices(): List<Choice> = bestIndices.map { arms[it].choice }
}
