package inego.zungjunggame.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StateSlotTest {

    private class StringSchema : Schema<String> {
        override val version: Int = 1
        override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
        override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
    }

    private val store = DefaultStateStore(InMemoryStateBackend())
    private val key = StateKey("slot", "text", StringSchema())
    private val slot = StateSlot(store, key)

    @Test
    fun requireValueUsesTheKeyDefault() {
        val defaulted = StateSlot(store, StateKey("slot", "defaulted", StringSchema(), default = { "fallback" }))
        assertEquals("fallback", defaulted.requireValue())
    }

    @Test
    fun requireValueWithoutADefaultIsAWiringBug() {
        assertFailsWith<IllegalArgumentException> { slot.requireValue() }
    }
}
