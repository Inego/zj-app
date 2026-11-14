package inego.zungjunggame.ui

import inego.zungjunggame.ui.stack.RouteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteUiStateTest {

    private val key = RouteKey("modal:example")

    /** A codec with the same shape as production: versioned text, strict decode. */
    private val countCodec = object : RouteStateCodec<Int> {
        override val type = "count"

        override fun encode(value: Int): String = "c$value"

        override fun decode(text: String): Int? =
            text.removePrefix("c").takeIf { it != text }?.toIntOrNull()
    }

    private val otherCodec = object : RouteStateCodec<Int> {
        override val type = "other"
        override fun encode(value: Int): String = "o$value"
        override fun decode(text: String): Int? = text.removePrefix("o").takeIf { it != text }?.toIntOrNull()
    }

    @Test
    fun writeThenReadRoundTripsThroughTheStoredText() {
        val store = RouteUiState()
        store.write(key, countCodec, 7)

        assertEquals("c7", store.text(key))
        assertEquals(7, store.read(key, countCodec))
    }

    @Test
    fun readRejectsCorruptText() {
        val store = RouteUiState()
        store.write(key, countCodec, 7)

        store.put(key, RouteUiValue("count", "not-a-count"))
        assertNull(store.read(key, countCodec), "corrupt text must not decode")
    }

    @Test
    fun readingAnotherFamilyTripsTheDebugAssert() {
        val store = RouteUiState()
        store.write(key, countCodec, 7)

        assertFailsWith<AssertionError> { store.read(key, otherCodec) }
    }

    @Test
    fun removeDropsTheEntry() {
        val store = RouteUiState()
        store.write(key, countCodec, 7)

        store.remove(key)

        assertNull(store.text(key))
        assertNull(store.read(key, countCodec))
    }

    @Test
    fun consumeTakesTheValueAndClearsIt() {
        val store = RouteUiState()
        store.write(key, countCodec, 7)

        assertEquals(7, store.consume(key, countCodec), "consume returns the stored value")
        assertNull(store.text(key), "consume clears the entry in the same step")
        assertNull(store.consume(key, countCodec), "a second consume sees nothing")
    }

    @Test
    fun portIsNullUntilThePlayerSetsAValue() {
        val store = RouteUiState()
        val port = store.port(key, countCodec)

        assertNull(port.read(), "an unset route reads as absent, never as a sentinel")

        store.write(key, countCodec, 4)
        assertEquals(4, port.read())

        store.put(key, RouteUiValue("count", "junk"))
        assertNull(port.read(), "a corrupt value reads as absent, it does not throw")
    }

    @Test
    fun aRememberingWriteSurvivesRemovalAndRestart() {
        val memory = FakeRememberedRouteState()
        val store = RouteUiState(memory)
        store.put(key, RouteUiValue("count", "c7"), remember = true)

        store.remove(key)
        assertNull(store.text(key), "the live entry is gone")
        assertEquals(7, store.port(key, countCodec).read(), "but the route still reads what it remembers")

        val restarted = RouteUiState(memory)
        assertEquals(7, restarted.read(key, countCodec), "a fresh store over the same memory recalls it")
    }

    @Test
    fun anOrdinaryWriteIsNotRemembered() {
        val memory = FakeRememberedRouteState()
        val store = RouteUiState(memory)
        store.put(key, RouteUiValue("count", "c7"))

        store.remove(key)
        assertNull(store.read(key, countCodec))
        assertTrue(memory.values.isEmpty(), "only a remembering route writes to the remembered store")
    }

    @Test
    fun consumeFallsBackToTheRememberedValueWithoutClearingIt() {
        val memory = FakeRememberedRouteState()
        val store = RouteUiState(memory)
        store.put(key, RouteUiValue("count", "c7"), remember = true)
        store.remove(key)

        assertEquals(7, store.consume(key, countCodec), "consume sees the remembered value")
        assertEquals(7, store.read(key, countCodec), "consume does not clear what the route remembers")
    }

    @Test
    fun revisionMovesOnMutationButNotOnRead() {
        val store = RouteUiState()
        val start = store.revision

        store.write(key, countCodec, 1)
        assertEquals(start + 1, store.revision)

        store.read(key, countCodec)
        store.text(key)
        store.port(key, countCodec).read()
        assertEquals(start + 1, store.revision, "reads must not mark the durable stack dirty")

        store.put(key, RouteUiValue("count", "c2"))
        assertEquals(start + 2, store.revision)

        store.remove(key)
        assertEquals(start + 3, store.revision)

        store.remove(key)
        assertEquals(start + 3, store.revision, "removing an absent key is not a change")
    }

    /** Stands in for the persisted `ui.remembered` key across a "restart". */
    private class FakeRememberedRouteState : RememberedRouteState {
        val values = mutableMapOf<RouteKey, RouteUiValue>()

        override fun recall(key: RouteKey): RouteUiValue? = values[key]

        override fun store(key: RouteKey, value: RouteUiValue) {
            values[key] = value
        }
    }
}
