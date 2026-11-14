package inego.zungjunggame.state

/** Volatile [StateBackend] backed by a [LinkedHashMap]; copies in and out. */
class InMemoryStateBackend : StateBackend {
    private val map = LinkedHashMap<String, ByteArray>()

    override fun isAvailable(): Boolean = true

    override fun get(name: String): ByteArray? = map[name]?.copyOf()

    override fun put(name: String, value: ByteArray) {
        map[name] = value.copyOf()
    }

    override fun remove(name: String) {
        map.remove(name)
    }

    override fun names(): Set<String> = map.keys.toSet()

    override fun flush() {}
}
