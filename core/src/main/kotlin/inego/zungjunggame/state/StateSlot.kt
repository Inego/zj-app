package inego.zungjunggame.state

/**
 * Typed accessor for one [StateKey] within a [StateStore].
 *
 * Hand a slot to the model that owns the value: it can read and write that one
 * key and nothing else, so persistence stays a narrow dependency rather than a
 * store handle passed around the graph.
 */
class StateSlot<S : Any>(private val store: StateStore, private val key: StateKey<S>) {
    /** The stored value, or null when the key has neither data nor a default. */
    fun get(): S? = store.read(key)

    /**
     * The stored value, or the key's [StateKey.default].
     *
     * Only for keys that declare a default (missing/corrupt data already fell
     * back to it); throws when a key was declared without one, which is a wiring
     * bug rather than bad data.
     */
    fun requireValue(): S =
        requireNotNull(get()) { "no stored value for '${key.fullName}' and it declares no default" }

    fun set(value: S) = store.write(key, value)

    fun clear() = store.remove(key)

    /**
     * Write [value] — `null` removes the key — and make it durable before
     * returning; false when the backend does not hold it afterwards.
     *
     * For the values whose write the player cannot simply repeat: a refresh
     * token the server has already consumed, where "the write did not land" is
     * not a stale frame but a forced sign-in. Everything else is batched
     * through [set] and publishes at the runtime's lifecycle edges.
     */
    fun commit(value: S?): Boolean = store.commit(key, value)
}
