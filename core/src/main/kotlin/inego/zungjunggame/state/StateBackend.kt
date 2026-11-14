package inego.zungjunggame.state

/**
 * Platform storage behind a [StateStore]; byte-blob, name-addressed.
 *
 * [get] and [names] are this backend's memory: they observe every [put] and
 * [remove] issued since the last [flush]. [flush] only makes that memory durable.
 */
interface StateBackend {
    fun isAvailable(): Boolean
    fun get(name: String): ByteArray?
    fun put(name: String, value: ByteArray)
    fun remove(name: String)
    fun names(): Set<String>
    fun flush()
}
