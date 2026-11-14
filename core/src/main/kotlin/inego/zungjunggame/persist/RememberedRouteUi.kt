package inego.zungjunggame.persist

import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey
import inego.zungjunggame.ui.RouteUiValue

/**
 * The last value of every route that remembers its own UI state, keyed by route.
 *
 * Route UI state normally dies with its entry; this is the durability [Remembered]
 * adds for the routes whose spec opts in. Opaque `type` + `text` pairs, exactly as
 * [inego.zungjunggame.ui.RouteUiState] stores them, so the codec stays the only
 * thing that reads a value.
 *
 * [Remembered]: inego.zungjunggame.ui.stack.LayerSpec.remembersState
 */
data class RememberedRouteUi(val entries: Map<String, RouteUiValue>) {
    companion object {
        val EMPTY: RememberedRouteUi = RememberedRouteUi(emptyMap())
    }
}

/**
 * Versioned codec for [RememberedRouteUi].
 *
 * v1: a count, then `routeKey type text` per entry.
 */
object RememberedRouteUiSchema : Schema<RememberedRouteUi> {
    override val version: Int = 1

    private const val MAX_ENTRIES = 128

    override fun encode(value: RememberedRouteUi): ByteArray {
        val writer = RecordWriter().int(value.entries.size)
        for ((routeKey, entry) in value.entries) {
            writer.field(routeKey).field(entry.type).field(entry.text)
        }
        return writer.build()
    }

    override fun decode(bytes: ByteArray): RememberedRouteUi? = try {
        val reader = RecordReader(bytes)
        val count = reader.count(MAX_ENTRIES)
        val entries = LinkedHashMap<String, RouteUiValue>(count)
        repeat(count) {
            val routeKey = reader.field()
            val type = reader.field()
            val text = reader.field()
            entries[routeKey] = RouteUiValue(type, text)
        }
        if (!reader.atEnd()) return null
        RememberedRouteUi(entries)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Persisted remembered route state: namespace `ui`, name `remembered`. */
val RememberedRouteUiKey: StateKey<RememberedRouteUi> = StateKey(
    namespace = "ui",
    name = "remembered",
    schema = RememberedRouteUiSchema,
    default = { RememberedRouteUi.EMPTY },
)
