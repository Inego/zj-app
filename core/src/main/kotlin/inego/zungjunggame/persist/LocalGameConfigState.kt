package inego.zungjunggame.persist

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.state.Migration
import inego.zungjunggame.state.MigrationResult
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned codec for the last **committed** local New Game [LocalGameConfig].
 *
 * v1: `rounds` (empty field = unlimited) then `allowChickenHands`.
 * v2: appends the bot level id. A v1 value is migrated as [BotLevel.DEFAULT], so
 *     the last setup survives the update instead of resetting.
 *
 * The stored value is the committed config, so a cold start without a board
 * still offers the last live game's rules. Cancel keeps an in-memory draft only.
 */
object LocalGameConfigSchema : Schema<LocalGameConfig> {
    override val version: Int = 2

    override fun encode(value: LocalGameConfig): ByteArray = RecordWriter()
        .field(value.rules.rounds?.toString().orEmpty())
        .boolean(value.rules.allowChickenHands)
        .field(value.botLevel.id)
        .build()

    override fun decode(bytes: ByteArray): LocalGameConfig? = try {
        val reader = RecordReader(bytes)
        val rawRounds = reader.field()
        val rounds = if (rawRounds.isEmpty()) null else rawRounds.toIntOrNull() ?: return null
        val chicken = reader.boolean()
        val botLevel = BotLevel.fromId(reader.field())
        if (!reader.atEnd()) return null
        if (rounds != null && rounds <= 0) return null
        LocalGameConfig(
            rules = GameConfig(rounds = rounds, allowChickenHands = chicken),
            botLevel = botLevel,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * v1 had no bot level. The new field is the payload's tail, so the old bytes
     * are a valid prefix: append the level every v1 game played and decode it as
     * a v2 value.
     */
    override val migrations: List<Migration> = listOf(
        Migration { fromVersion, bytes ->
            if (fromVersion != 1) {
                MigrationResult.Drop
            } else {
                MigrationResult.Migrated(
                    bytes + RecordWriter().field(BotLevel.DEFAULT.id).build(),
                )
            }
        },
    )
}

/**
 * Persisted local New Game config: namespace `game`, name `prefs` — the same key
 * v1 wrote, so an install's last setup is still here after the update.
 */
val LocalGameConfigKey: StateKey<LocalGameConfig> = StateKey(
    namespace = "game",
    name = "prefs",
    schema = LocalGameConfigSchema,
    default = { LocalGameConfig() },
)
