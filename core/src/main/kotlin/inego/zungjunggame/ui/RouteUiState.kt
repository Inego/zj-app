package inego.zungjunggame.ui

import inego.zungjunggame.ui.stack.RouteKey

/**
 * Text codec for one family of route state: the stable [type] id names the
 * family and [encode]/[decode] move the value to and from its stored text.
 *
 * Decoding is the one place a corrupt or unknown-version text is handled: a
 * codec answers null and the reader falls back to the value it was handed.
 */
interface RouteStateCodec<T> {
    /** Stable id of the value family, stored beside the text. */
    val type: String

    fun encode(value: T): String

    /** The value [text] carries, or null when it is corrupt or another version. */
    fun decode(text: String): T?
}

/** One stored route-state value: the family it belongs to and its text form. */
data class RouteUiValue(val type: String, val text: String)

/**
 * The player-set state of each route on the stack, in memory and route-keyed.
 *
 * One store for every stateful route: the stack snapshot is its persistence form,
 * so the two cannot desync. A presenter's factory injects a [StatePort] built
 * from this store, the route's key, and the route's declared [RouteStateCodec];
 * the widget reports a change and the effect writes here. [text] is what
 * `Router.snapshot` projects into the entry, and `Router.onEntryRemoved` clears a
 * key exactly when its entry leaves the stack.
 *
 * A route whose spec [inego.zungjunggame.ui.stack.LayerSpec.remembersState] also
 * has every write mirrored into [remembered], and [read] falls back to it once the
 * entry is gone — so the value outlives the route and the process.
 */
class RouteUiState(
    /** Where a remembering route's values are kept past removal; null keeps none. */
    private val remembered: RememberedRouteState? = null,
) {
    private val values = LinkedHashMap<RouteKey, RouteUiValue>()

    /**
     * Monotonic count of store mutations. The runtime's durable-write trigger
     * watches it, so a change that does **not** replace the stack — a scroll, a
     * draft, a drawer — is still captured by the opportunistic flush, not only at
     * a lifecycle edge.
     */
    var revision: Long = 0L
        private set

    /**
     * The value stored for [key], decoded through [codec]. Null when nothing is
     * stored, when the stored value belongs to another family ([RouteUiValue.type]
     * differs), or when its text does not decode.
     */
    fun <T> read(key: RouteKey, codec: RouteStateCodec<T>): T? {
        val stored = values[key] ?: remembered?.recall(key) ?: return null
        if (stored.type != codec.type) {
            // A cross-family read is a wiring error, not a value: the boot re-stamps
            // every seeded entry with its route's declared codec, so a family
            // mismatch can only come from reading with the wrong codec. Loud in
            // debug, absent in release (assertions are off there).
            assert(false) {
                "route state ${key.value} is family '${stored.type}', read as '${codec.type}'"
            }
            return null
        }
        return codec.decode(stored.text)
    }

    fun <T> write(key: RouteKey, codec: RouteStateCodec<T>, value: T) {
        values[key] = RouteUiValue(codec.type, codec.encode(value))
        revision++
    }

    /**
     * Take the value stored for [key] and clear it in one operation, so a route's
     * own state cannot be read after its route removed it. Null under the same
     * rules as [read]. Clearing an absent key is a no-op, so this is idempotent
     * with the route's own `onEntryRemoved`.
     */
    fun <T> consume(key: RouteKey, codec: RouteStateCodec<T>): T? {
        val value = read(key, codec)
        remove(key)
        return value
    }

    /** The stored text for [key], whatever family it belongs to; null when absent. */
    fun text(key: RouteKey): String? = values[key]?.text

    /**
     * Stores [value] for [key]. [remember] also mirrors it into [remembered], for a
     * route whose spec remembers its state; the cold-start seeding path leaves it
     * false because the value was read from the persisted stack, not set now.
     */
    fun put(key: RouteKey, value: RouteUiValue, remember: Boolean = false) {
        values[key] = value
        revision++
        if (remember) remembered?.store(key, value)
    }

    fun remove(key: RouteKey) {
        if (values.remove(key) != null) revision++
    }

    /** Read-only view of one route's value for its presenter. */
    fun <T> port(key: RouteKey, codec: RouteStateCodec<T>): StatePort<T> =
        StatePort { read(key, codec) }
}

/**
 * Read-only view of one route's player-set state; writes emit through the action
 * channel.
 *
 * A null answer means the player has set nothing (or the stored text is
 * corrupt), so the widget's own view default stands. Absence is never encoded as
 * a sentinel value: "unset" and "set to zero" are different facts.
 */
fun interface StatePort<T> {
    fun read(): T?
}

/**
 * The shared codec for "how far down a layer's body the player had read": one
 * value family because the question and its answer are the same on every
 * scrollable layer.
 *
 * Text form: `v1:<offset>`, so a later layout can be told apart from this one.
 */
object RouteScrollCodec : RouteStateCodec<Float> {
    override val type = "scroll"

    override fun encode(value: Float): String = "$PREFIX$value"

    override fun decode(text: String): Float? {
        if (!text.startsWith(PREFIX)) return null
        val offset = text.removePrefix(PREFIX).toFloatOrNull() ?: return null
        if (!offset.isFinite() || offset < 0f) return null
        return offset
    }

    private const val PREFIX = "v1:"
}
