package inego.zungjunggame.state

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateStoreTest {

    private class StringSchema : Schema<String> {
        override val version: Int = 1
        override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
        override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
    }

    private class IntSchema : Schema<Int> {
        override val version: Int = 2
        override fun encode(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
        override fun decode(bytes: ByteArray): Int? =
            if (bytes.size == 4) ByteBuffer.wrap(bytes).int else null

        override val migrations: List<Migration> = listOf(
            Migration { fromVersion, bytes ->
                if (fromVersion == 1 && bytes.size == 1) {
                    MigrationResult.Migrated(ByteBuffer.allocate(4).putInt(bytes[0].toInt()).array())
                } else {
                    MigrationResult.Drop
                }
            },
        )
    }

    private class DropSchema : Schema<Int> {
        override val version: Int = 2
        override fun encode(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
        override fun decode(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).int
        override val migrations: List<Migration> = listOf(
            Migration { _, _ -> MigrationResult.Drop },
        )
    }

    private class RecordingBackend : StateBackend {
        val delegate = InMemoryStateBackend()
        var flushes: Int = 0

        override fun isAvailable(): Boolean = true
        override fun get(name: String): ByteArray? = delegate.get(name)
        override fun put(name: String, value: ByteArray) = delegate.put(name, value)
        override fun remove(name: String) = delegate.remove(name)
        override fun names(): Set<String> = delegate.names()
        override fun flush() {
            flushes++
        }
    }

    private val backing = RecordingBackend()
    private val store = DefaultStateStore(backing)

    private val textKey = StateKey("test", "text", StringSchema())
    private val intKey = StateKey("test", "int", IntSchema(), default = { -1 })
    private val dropKey = StateKey("test", "drop", DropSchema(), default = { -1 })
    private val otherKey = StateKey("other", "text", StringSchema())

    private fun frame(version: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()

    @Test
    fun absentReturnsDefault() {
        assertNull(store.read(textKey))
        assertEquals(-1, store.read(intKey))
    }

    @Test
    fun roundTripsValue() {
        store.write(textKey, "hello")
        assertEquals("hello", store.read(textKey))
    }

    @Test
    fun writeFramesSchemaVersion() {
        store.write(intKey, 42)
        val raw = backing.delegate.get(intKey.fullName)!!
        assertEquals(2, ByteBuffer.wrap(raw, 0, 4).int)
        assertEquals(42, ByteBuffer.wrap(raw, 4, 4).int)
    }

    @Test
    fun appliesMigrationThenDecodes() {
        backing.delegate.put(intKey.fullName, frame(1, byteArrayOf(7)))
        assertEquals(7, store.read(intKey))
    }

    @Test
    fun droppedMigrationReturnsDefault() {
        backing.delegate.put(dropKey.fullName, frame(1, ByteBuffer.allocate(4).putInt(9).array()))
        assertEquals(-1, store.read(dropKey))
    }

    @Test
    fun missingMigrationReturnsDefault() {
        backing.delegate.put(intKey.fullName, frame(0, byteArrayOf(7)))
        assertEquals(-1, store.read(intKey))
    }

    @Test
    fun forwardVersionReturnsDefault() {
        backing.delegate.put(intKey.fullName, frame(3, ByteBuffer.allocate(4).putInt(9).array()))
        assertEquals(-1, store.read(intKey))
    }

    @Test
    fun shortOrCorruptBytesReturnDefault() {
        backing.delegate.put(textKey.fullName, byteArrayOf(1, 2, 3))
        assertNull(store.read(textKey))

        backing.delegate.put(intKey.fullName, frame(2, byteArrayOf(1, 2)))
        assertEquals(-1, store.read(intKey))
    }

    @Test
    fun removeAndContains() {
        store.write(textKey, "hello")
        assertTrue(store.contains(textKey))

        store.remove(textKey)
        assertFalse(store.contains(textKey))
        assertNull(store.read(textKey))
    }

    @Test
    fun clearNamespaceRemovesOnlyThatNamespace() {
        store.write(textKey, "hello")
        store.write(otherKey, "world")

        store.clearNamespace("test")

        assertFalse(store.contains(textKey))
        assertTrue(store.contains(otherKey))
        assertEquals("world", store.read(otherKey))
    }

}
