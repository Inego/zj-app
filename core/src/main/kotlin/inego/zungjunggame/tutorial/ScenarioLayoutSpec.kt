package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout

/**
 * How to build the initial [GameState] for a scenario.
 * Author-facing content can later map JSON → these cases; tests may use [Factory].
 */
sealed class ScenarioLayoutSpec {
    /** Opening deal from a fixed wall draw order. */
    data class WallOrder(
        val wall: IntArray,
        val eastSeat: Int = 0,
        val allowChickenHands: Boolean = false,
    ) : ScenarioLayoutSpec() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WallOrder) return false
            return eastSeat == other.eastSeat &&
                allowChickenHands == other.allowChickenHands &&
                wall.contentEquals(other.wall)
        }

        override fun hashCode(): Int {
            var result = wall.contentHashCode()
            result = 31 * result + eastSeat
            result = 31 * result + allowChickenHands.hashCode()
            return result
        }
    }

    /** Mid-hand restore via [GameState.fromLayout]. */
    data class Layout(
        val layout: GameStateLayout,
    ) : ScenarioLayoutSpec()

    /** Programmatic board (sample scenarios / tests). */
    data class Factory(
        val build: () -> GameState,
    ) : ScenarioLayoutSpec()

    fun materialize(): GameState = when (this) {
        is WallOrder -> GameState.fromWallOrder(
            wallOrder = wall,
            eastSeat = eastSeat,
            allowChickenHands = allowChickenHands,
        )
        is Layout -> GameState.fromLayout(layout)
        is Factory -> build()
    }
}
