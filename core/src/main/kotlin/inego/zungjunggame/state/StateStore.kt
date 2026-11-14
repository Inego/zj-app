package inego.zungjunggame.state

/** Typed, versioned persistence facade over a [StateBackend]. */
interface StateStore {
    fun <S : Any> read(key: StateKey<S>): S?
    fun <S : Any> write(key: StateKey<S>, value: S)
    fun remove(key: StateKey<*>)
    fun contains(key: StateKey<*>): Boolean
    fun clearNamespace(namespace: String)

    /**
     * Write [value] — `null` removes the key — and make it durable before
     * returning, reporting whether the backend holds exactly that afterwards.
     *
     * This is the one durable write. A value is committed rather than batched
     * when its loss is not a next-frame annoyance but an outcome the player
     * pays for: the refresh token, whose write is coupled to a one-shot event
     * on the server (the token presented was consumed the moment the response
     * was sent, so a rotation left in backend memory is a credential the next
     * boot presents already burned). [StateKey.secret] carries exactly that
     * meaning — the same flag that says "seal it, its leak is unrecoverable"
     * says "commit it, its loss is too" — and [DefaultStateStore] routes every
     * secret key through here, so no caller has to remember the rule.
     *
     * True means the container holds [value]; for a `null` value it means the
     * key is durably gone. A refused seal is false, never "absent, so fine".
     */
    fun <S : Any> commit(key: StateKey<S>, value: S?): Boolean

    /**
     * Persist pending writes to the backend.
     *
     * This is the **batched** half of persistence: everything whose owner can
     * simply write it again (the stack, route UI, sessions, preferences) sits
     * in the backend's memory between two lifecycle edges and is published by
     * the runtime at pause, exit and dispose, and by a changed stack at most
     * once a minute. A caller that hands such a value to a new backend in the
     * same boot has to flush; Android does not publish an editor until then.
     *
     * A [StateKey.secret] value never waits for that: it is committed on write.
     */
    fun flush()
}
