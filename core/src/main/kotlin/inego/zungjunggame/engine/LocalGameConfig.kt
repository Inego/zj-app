package inego.zungjunggame.engine

/**
 * Opponent strength for the bot seats of a local game.
 *
 * [id] is the persisted spelling: a stored preference or save names the level by
 * this string, so an enum constant may be renamed without losing the value.
 */
enum class BotLevel(
    /** Stable codec id, not the enum name. */
    val id: String,
) {
    /**
     * Weakest beginner policy (~840 Elo): one-pair tunnel vision and
     * shiny-button calling. Never passes a legal Hu.
     */
    NOVICE("novice"),

    /**
     * Intermediate beginner policy (~983 Elo): myopic greedy meld reading with
     * eager calling. Never passes a legal Hu.
     */
    BEGINNER("beginner"),

    /** The full default policy (1200 Elo). */
    STANDARD("standard"),
    ;

    companion object {
        /** What an install that never chose a level plays, and what an unknown id falls back to. */
        val DEFAULT: BotLevel = STANDARD

        /** The level [id] names, or [DEFAULT] when this build does not know it. */
        fun fromId(id: String?): BotLevel = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Hint assistance mode for a local game.
 *
 * [id] is the persisted spelling: a stored preference or save names the mode by
 * this string, so an enum constant may be renamed without losing the value.
 */
enum class HintMode(
    /** Stable codec id, not the enum name. */
    val id: String,
) {
    /** Hints disabled: no HINT button, H key is inert. */
    NONE("none"),

    /** Standard bot advice (`DefaultEngine.rank` + desktop UCB flat MC). */
    STANDARD_BOT("standard"),

    /**
     * Omniscient "God guidance" search: perfect-information SP-MCTS + pattern
     * seekers against the table's deterministic bots, continuously improving
     * per-option best scores and pattern breakdowns at 1 Hz.
     */
    OMNISCIENT("omniscient"),
    ;

    companion object {
        /** What an install that never chose a hint mode plays, and what an unknown id falls back to. */
        val DEFAULT: HintMode = STANDARD_BOT

        /** The mode [id] names, or [DEFAULT] when this build does not know it. */
        fun fromId(id: String?): HintMode = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Everything New Game setup decides for one local game.
 *
 * [rules] is the Zung Jung game configuration. [botLevel], [hintMode], and
 * [fastBots] are properties of the local table, not of the rules, so they stay
 * out of [GameConfig]: that type is also the online hub's matchmaking draft,
 * where local bot/hint settings would be meaningless.
 */
data class LocalGameConfig(
    val rules: GameConfig = DEFAULT_RULES,
    val botLevel: BotLevel = BotLevel.DEFAULT,
    val hintMode: HintMode = HintMode.DEFAULT,
    val fastBots: Boolean = false,
) {
    companion object {
        /** Default rules for a new install's local New Game setup: 4 rounds (16 hands), patternless hands allowed. */
        val DEFAULT_RULES: GameConfig = GameConfig(
            rounds = GameConfig.DEFAULT_ROUNDS,
            allowChickenHands = true,
        )
    }
}
