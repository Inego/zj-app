package inego.zungjunggame.persist

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.HandScoreRow

/**
 * Full session that survives app exit / process death (desktop and Android):
 * score history, the in-progress (or just-finished) hand, deal bookkeeping,
 * and game config.
 */
data class SessionSnapshot(
    val scoreRows: List<HandScoreRow>,
    val game: GameState,
    val nextEastSeat: Int,
    val currentHandRecorded: Boolean,
    val dealSeed: Long,
    /** Finite rounds, or `null` for unlimited. */
    val rounds: Int? = GameConfig.DEFAULT_ROUNDS,
) {
    val gameConfig: GameConfig
        get() = GameConfig(
            rounds = rounds,
            allowChickenHands = game.allowChickenHands,
        )
}
