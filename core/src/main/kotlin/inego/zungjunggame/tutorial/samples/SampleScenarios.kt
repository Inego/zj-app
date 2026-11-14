package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.random.Random

/**
 * Shared board helpers for tutorial fixtures and headless probes
 * (not campaign lessons).
 */
object SampleScenarios {

    data class PungPosition(
        val state: GameState,
        val tile: Int,
        val discarder: Int,
    )

    /**
     * Search fixed deals for: non-human discards a tile human holds ≥2 of,
     * and it is human's turn to react with Pung available.
     */
    fun buildPungPosition(humanSeat: Int = 0, maxSeeds: Int = 5_000): PungPosition {
        for (seed in 0 until maxSeeds) {
            val wall = canonicalWall(Random(seed.toLong()))
            for (east in 0 until NUM_PLAYERS) {
                if (east == humanSeat) continue
                val state = GameState.fromWallOrder(wall, eastSeat = east)
                if (state.currentPlayer != east) continue
                val discards = state.enumerateChoices().asList()
                    .filter { it.kind == ChoiceKind.Discard }
                for (discard in discards) {
                    val tile = discard.tile()
                    if (state.players[humanSeat].concealed[tile] < 2) continue
                    val trial = state.copy()
                    trial.applyChoice(discard)
                    if (trial.decisionType != DecisionType.ReactToDiscard) continue
                    // Advance reaction to humanSeat (others pass).
                    var guard = 0
                    while (
                        trial.decisionType == DecisionType.ReactToDiscard &&
                        trial.currentPlayer != humanSeat &&
                        guard++ < 8
                    ) {
                        val pass = trial.enumerateChoices().asList()
                            .firstOrNull { it.kind == ChoiceKind.Pass } ?: break
                        trial.applyChoice(pass)
                    }
                    if (trial.currentPlayer != humanSeat) continue
                    if (trial.decisionType != DecisionType.ReactToDiscard) continue
                    val canPung = trial.enumerateChoices().asList()
                        .any { it.kind == ChoiceKind.Pung && it.tile() == tile }
                    if (!canPung) continue
                    return PungPosition(
                        state = trial,
                        tile = tile,
                        discarder = east,
                    )
                }
            }
        }
        error("could not find pung demo position within $maxSeeds seeds")
    }

    fun canonicalWall(rng: Random): IntArray {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        for (i in WALL_SIZE - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}
