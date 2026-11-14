package inego.zungjunggame.persist

import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.state.Migration
import inego.zungjunggame.state.MigrationResult
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * The player's last matchmaking "Find game" selection.
 *
 * The hub's settings are always on screen, so nothing here describes whether
 * they were visible; this is purely the values the queue request rides.
 */
data class FindGamePreferences(
    val minOtherHumans: Int = DEFAULT_MIN_OTHER_HUMANS,
    val rounds: QueueRounds = DEFAULT_ROUNDS,
    val chicken: QueueChicken = DEFAULT_CHICKEN,
) {
    companion object {
        const val DEFAULT_MIN_OTHER_HUMANS = 1
        /** Widest `minOtherHumans` the hub slider exposes. */
        const val MAX_MIN_OTHER_HUMANS = 3
        val DEFAULT_ROUNDS = QueueRounds.Either
        val DEFAULT_CHICKEN = QueueChicken.Either
        val DEFAULT: FindGamePreferences = FindGamePreferences()
    }
}

/**
 * Versioned codec for [FindGamePreferences].
 *
 * v1: `minOtherHumans` (0..3), the [QueueRounds] name, the [QueueChicken] name,
 * then `settingsOpen`. An unknown enum name or an out-of-range slider value is
 * corrupt data and falls back to the key's default.
 * v2: `settingsOpen` is gone — the hub's settings are always on screen, so
 * nothing restores it. The three fields above are unchanged, so v1 payloads
 * migrate by re-encoding what they already said.
 */
object FindGamePreferencesSchema : Schema<FindGamePreferences> {
    override val version: Int = 2

    /**
     * v1 → v2: the hub always shows its settings, so the drawer's open flag is
     * discarded and the matchmaking selection it travelled with is kept.
     */
    override val migrations: List<Migration> = listOf(
        Migration { _, bytes ->
            val reader = RecordReader(bytes)
            try {
                val minOtherHumans = reader.int()
                val roundsName = reader.field()
                val chickenName = reader.field()
                reader.boolean() // settingsOpen: read to stay in step, then dropped
                val rounds = QueueRounds.entries.firstOrNull { it.name == roundsName }
                val chicken = QueueChicken.entries.firstOrNull { it.name == chickenName }
                if (minOtherHumans !in 0..FindGamePreferences.MAX_MIN_OTHER_HUMANS ||
                    rounds == null || chicken == null
                ) {
                    MigrationResult.Drop
                } else {
                    MigrationResult.Migrated(
                        encode(FindGamePreferences(minOtherHumans, rounds, chicken)),
                    )
                }
            } catch (_: IllegalArgumentException) {
                MigrationResult.Drop
            }
        },
    )

    override fun encode(value: FindGamePreferences): ByteArray = RecordWriter()
        .int(value.minOtherHumans)
        .field(value.rounds.name)
        .field(value.chicken.name)
        .build()

    override fun decode(bytes: ByteArray): FindGamePreferences? = try {
        val reader = RecordReader(bytes)
        val minOtherHumans = reader.int()
        val roundsName = reader.field()
        val chickenName = reader.field()
        if (!reader.atEnd()) return null
        if (minOtherHumans !in 0..FindGamePreferences.MAX_MIN_OTHER_HUMANS) return null
        val rounds = QueueRounds.entries.firstOrNull { it.name == roundsName } ?: return null
        val chicken = QueueChicken.entries.firstOrNull { it.name == chickenName } ?: return null
        FindGamePreferences(minOtherHumans = minOtherHumans, rounds = rounds, chicken = chicken)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted matchmaking preferences: namespace `online`, name `find`. */
val FindGamePreferencesKey: StateKey<FindGamePreferences> = StateKey(
    namespace = "online",
    name = "find",
    schema = FindGamePreferencesSchema,
    default = { FindGamePreferences.DEFAULT },
)
