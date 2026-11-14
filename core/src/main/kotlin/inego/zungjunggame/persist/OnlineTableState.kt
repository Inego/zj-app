package inego.zungjunggame.persist

import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned codec for the last live online table this client was seated at.
 *
 * v1: the table id, or an empty field when there is none. Blank is the "no
 * table" spelling rather than null, because `StateKey` values are non-null and a
 * blank id is never a valid table.
 */
object OnlineTableIdSchema : Schema<String> {
    override val version: Int = 1

    override fun encode(value: String): ByteArray = RecordWriter()
        .field(value)
        .build()

    override fun decode(bytes: ByteArray): String? = try {
        val reader = RecordReader(bytes)
        val id = reader.field()
        if (!reader.atEnd()) return null
        id
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted last online table id: namespace `online`, name `table`. */
val OnlineTableIdKey: StateKey<String> = StateKey(
    namespace = "online",
    name = "table",
    schema = OnlineTableIdSchema,
    default = { "" },
)
