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
 * Everything New Game setup decides for one local game.
 *
 * [rules] is the Zung Jung game configuration. [botLevel] is a property of the
 * local table, not of the rules, so it stays out of [GameConfig]: that type is
 * also the online hub's matchmaking draft, where a bot level would be
 * meaningless.
 */
data class LocalGameConfig(
    val rules: GameConfig = GameConfig.DEFAULT,
    val botLevel: BotLevel = BotLevel.DEFAULT,
)
