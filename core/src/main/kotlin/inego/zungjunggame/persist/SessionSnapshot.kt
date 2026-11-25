package inego.zungjunggame.persist

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.engine.HintMode
import inego.zungjunggame.engine.LocalGameConfig

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
    /** Bot strength of this local game; a resumed game keeps the level it started with. */
    val botLevel: BotLevel = BotLevel.DEFAULT,
    /** Hint assistance mode of this local game; a resumed game keeps the mode it started with. */
    val hintMode: HintMode = HintMode.DEFAULT,
    /** Whether non-human turns skip simulated thinking delays; preserved across resume. */
    val fastBots: Boolean = false,
) {
    val gameConfig: LocalGameConfig
        get() = LocalGameConfig(
            rules = GameConfig(
                rounds = rounds,
                allowChickenHands = game.allowChickenHands,
            ),
            botLevel = botLevel,
            hintMode = hintMode,
            fastBots = fastBots,
        )
}
