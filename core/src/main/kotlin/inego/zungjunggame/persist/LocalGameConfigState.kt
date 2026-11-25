package inego.zungjunggame.persist

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.HintMode
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
 * v2: appends the bot level id (v1 migrates as [BotLevel.DEFAULT]).
 * v3: appends the hint mode id (v2 migrates as [HintMode.DEFAULT]).
 * v4: appends the `fastBots` flag (v3 migrates as `false`).
 *
 * The stored value is the committed config, so a cold start without a board
 * still offers the last live game's rules. Cancel keeps an in-memory draft only.
 */
object LocalGameConfigSchema : Schema<LocalGameConfig> {
    override val version: Int = 4

    override fun encode(value: LocalGameConfig): ByteArray = RecordWriter()
        .field(value.rules.rounds?.toString().orEmpty())
        .boolean(value.rules.allowChickenHands)
        .field(value.botLevel.id)
        .field(value.hintMode.id)
        .boolean(value.fastBots)
        .build()

    override fun decode(bytes: ByteArray): LocalGameConfig? = try {
        val reader = RecordReader(bytes)
        val rawRounds = reader.field()
        val rounds = if (rawRounds.isEmpty()) null else rawRounds.toIntOrNull() ?: return null
        val chicken = reader.boolean()
        val botLevel = BotLevel.fromId(reader.field())
        val hintMode = HintMode.fromId(reader.field())
        val fastBots = reader.boolean()
        if (!reader.atEnd()) return null
        if (rounds != null && rounds <= 0) return null
        LocalGameConfig(
            rules = GameConfig(rounds = rounds, allowChickenHands = chicken),
            botLevel = botLevel,
            hintMode = hintMode,
            fastBots = fastBots,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Step migrations (`baseVersion = 1`):
     * - v1→v2 appends [BotLevel.DEFAULT]
     * - v2→v3 appends [HintMode.DEFAULT]
     * - v3→v4 appends `fastBots = false`
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
        Migration { fromVersion, bytes ->
            if (fromVersion != 2) {
                MigrationResult.Drop
            } else {
                MigrationResult.Migrated(
                    bytes + RecordWriter().field(HintMode.DEFAULT.id).build(),
                )
            }
        },
        Migration { fromVersion, bytes ->
            if (fromVersion != 3) {
                MigrationResult.Drop
            } else {
                MigrationResult.Migrated(
                    bytes + RecordWriter().boolean(false).build(),
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
