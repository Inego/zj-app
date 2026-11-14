package inego.zungjunggame.ui.stack

import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey

/**
 * Versioned codec for [StackState].
 *
 * Field framing comes from [RecordWriter]/[RecordReader] (pure Kotlin, so this
 * compiles on desktop, Android, and Wasm): an entry count, then per entry a
 * route key, kind name, optional id, sorted arg pairs, an optional component-state
 * placeholder, and the policy the entry was pushed with. Malformed, truncated, or
 * unknown-kind input decodes to null, so [inego.zungjunggame.state.DefaultStateStore]
 * falls back to [StackStateKey]'s default. [Route.Reference]'s in-session
 * `returnTo` stack is intentionally dropped (volatile by design).
 *
 * **No migrations.** A snapshot written by an older client is not translated: the
 * version bump makes it unreadable, so the client starts at the root floor with
 * an empty stack. What a client can hold locally is regenerable; carrying
 * migration code for it is not worth the branch coverage it would need.
 */
object StackStateSchema : Schema<StackState> {
    /**
     * v3: a context above the floor is a `Layer` of its own kind rather than a
     * replaced `Screen`, and every entry records the policy it was pushed with.
     */
    override val version: Int = 3

    private const val MAX_ENTRIES = 64
    private const val MAX_ARGS = 32

    override fun encode(value: StackState): ByteArray {
        val writer = RecordWriter().int(value.entries.size)
        for ((routeKey, kind, id, args1, componentState, policy) in value.entries) {
            writer.field(routeKey)
            writer.field(kind.name)
            writer.field(id.orEmpty())
            val args = args1.entries.sortedBy { it.key }
            writer.int(args.size)
            for ((key, argValue) in args) {
                writer.field(key)
                writer.field(argValue)
            }
            writer.field(componentState.orEmpty())
            writer.field(policy.presentation.name)
            writer.boolean(policy.persists)
        }
        return writer.build()
    }

    override fun decode(bytes: ByteArray): StackState? {
        return try {
            val reader = RecordReader(bytes)
            val count = reader.count(MAX_ENTRIES)
            val entries = ArrayList<RouteEntryState>(count)
            for (i in 0 until count) {
                val routeKey = reader.field()
                val kindName = reader.field()
                val kind = RouteKind.entries.firstOrNull { it.name == kindName } ?: return null
                val id = reader.field().takeIf { it.isNotEmpty() }
                val argCount = reader.count(MAX_ARGS)
                val args = LinkedHashMap<String, String>(argCount)
                repeat(argCount) { args[reader.field()] = reader.field() }
                val componentState = reader.field().takeIf { it.isNotEmpty() }
                val presentationName = reader.field()
                val presentation = Presentation.entries.firstOrNull { it.name == presentationName }
                    ?: return null
                val persists = reader.boolean()
                entries += RouteEntryState(
                    routeKey = routeKey,
                    kind = kind,
                    id = id,
                    args = args,
                    componentState = componentState,
                    policy = LayerPolicy(presentation, persists),
                )
            }
            if (!reader.atEnd()) return null
            StackState(entries)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Persisted [StackState]: namespace `stack`, name `state`. */
val StackStateKey: StateKey<StackState> = StateKey(
    namespace = "stack",
    name = "state",
    schema = StackStateSchema,
    default = { StackState.EMPTY },
)
