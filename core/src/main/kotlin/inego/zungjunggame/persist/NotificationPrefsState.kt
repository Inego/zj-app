package inego.zungjunggame.persist

import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.net.NotificationPrefsScale
import inego.zungjunggame.state.Migration
import inego.zungjunggame.state.MigrationResult
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned codec for [NotificationPrefs].
 *
 * v1: the email opt-in flag, then the immediate count per day.
 * v2: the email opt-in flag, then the digest interval in hours.
 * v3: v2 plus the BCP 47 mail language (empty field = no language stored).
 * v4: v3 plus the report time zone (empty field = no zone stored).
 */
object NotificationPrefsSchema : Schema<NotificationPrefs> {
    override val version: Int = 4

    override fun encode(value: NotificationPrefs): ByteArray =
        RecordWriter()
            .boolean(value.emailOptIn)
            .int(value.digestIntervalHours)
            .field(value.language.orEmpty())
            .field(value.timeZone.orEmpty())
            .build()

    override fun decode(bytes: ByteArray): NotificationPrefs? = try {
        val reader = RecordReader(bytes)
        val emailOptIn = reader.boolean()
        val digestIntervalHours = NotificationPrefsScale.coerce(reader.int())
        val language = reader.field().ifEmpty { null }
        val timeZone = reader.field().ifEmpty { null }
        when {
            !reader.atEnd() -> null
            else -> NotificationPrefs(
                emailOptIn = emailOptIn,
                digestIntervalHours = digestIntervalHours,
                language = language,
                timeZone = timeZone,
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    override val migrations: List<Migration> = listOf(
        Migration { _, bytes ->
            // v1's immediate count has no equivalent cadence; keep the opt-in and
            // start the player on the once-a-day default.
            try {
                val reader = RecordReader(bytes)
                val emailOptIn = reader.boolean()
                MigrationResult.Migrated(
                    RecordWriter()
                        .boolean(emailOptIn)
                        .int(NotificationPrefs.DEFAULT.digestIntervalHours)
                        .build(),
                )
            } catch (_: IllegalArgumentException) {
                MigrationResult.Drop
            }
        },
        Migration { _, bytes ->
            // v2 has no language; an empty field is the new "not stored".
            try {
                val reader = RecordReader(bytes)
                val emailOptIn = reader.boolean()
                val digestIntervalHours = reader.int()
                MigrationResult.Migrated(
                    RecordWriter()
                        .boolean(emailOptIn)
                        .int(digestIntervalHours)
                        .field("")
                        .build(),
                )
            } catch (_: IllegalArgumentException) {
                MigrationResult.Drop
            }
        },
        Migration { _, bytes ->
            // v3 has no time zone; an empty field is the new "not stored".
            try {
                val reader = RecordReader(bytes)
                val emailOptIn = reader.boolean()
                val digestIntervalHours = reader.int()
                val language = reader.field()
                MigrationResult.Migrated(
                    RecordWriter()
                        .boolean(emailOptIn)
                        .int(digestIntervalHours)
                        .field(language)
                        .field("")
                        .build(),
                )
            } catch (_: IllegalArgumentException) {
                MigrationResult.Drop
            }
        },
    )
}

/**
 * Persisted notification settings: namespace `notification`, name `prefs`.
 *
 * The Settings card is a view of [NotificationPrefs] and is rendered the moment a
 * cold start restores the stack, so the last server-confirmed value must survive a
 * restart: without this key a restored Settings layer shows the [NotificationPrefs.DEFAULT]
 * until the boot read answers — a visible off→on flip. The card reads the save at
 * construction and the boot read ([inego.zungjunggame.net.NotificationPrefsClient.refresh])
 * reconciles it if the account changed elsewhere. Cleared on sign-out.
 */
val NotificationPrefsKey: StateKey<NotificationPrefs> = StateKey(
    namespace = "notification",
    name = "prefs",
    schema = NotificationPrefsSchema,
    default = { NotificationPrefs.DEFAULT },
)
