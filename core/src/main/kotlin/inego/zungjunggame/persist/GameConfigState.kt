package inego.zungjunggame.persist

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned codec for the last **committed** New Game [GameConfig].
 *
 * v1: `rounds` (empty field = unlimited) then `allowChickenHands`.
 *
 * The stored value is the committed config, so a cold start without a board
 * still offers the last live game's rules. Cancel keeps an in-memory draft only.
 */
object GameConfigSchema : Schema<GameConfig> {
    override val version: Int = 1

    override fun encode(value: GameConfig): ByteArray = RecordWriter()
        .field(value.rounds?.toString().orEmpty())
        .boolean(value.allowChickenHands)
        .build()

    override fun decode(bytes: ByteArray): GameConfig? = try {
        val reader = RecordReader(bytes)
        val rawRounds = reader.field()
        val rounds = if (rawRounds.isEmpty()) null else rawRounds.toIntOrNull() ?: return null
        val chicken = reader.boolean()
        if (!reader.atEnd()) return null
        if (rounds != null && rounds <= 0) return null
        GameConfig(rounds = rounds, allowChickenHands = chicken)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted New Game config: namespace `game`, name `prefs`. */
val GameConfigKey: StateKey<GameConfig> = StateKey(
    namespace = "game",
    name = "prefs",
    schema = GameConfigSchema,
    default = { GameConfig.DEFAULT },
)
