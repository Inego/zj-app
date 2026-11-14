package inego.zungjunggame.ui.stack

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StackStateSchemaTest {

    private val below = LayerPolicy(Presentation.Below, persists = true)

    private val sample = StackState(
        listOf(
            RouteEntryState(
                routeKey = "layer:local_board",
                kind = RouteKind.Layer,
                id = "local_board",
                args = mapOf("tableId" to "t-1", "lessonId" to "l-2"),
                componentState = "board-state",
                // How the context was pushed travels with the entry.
                policy = below,
            ),
            RouteEntryState("reference", RouteKind.Reference, null, policy = below),
        ),
    )

    private fun store(): DefaultStateStore = DefaultStateStore(InMemoryStateBackend())

    @Test
    fun encodeIsStableAcrossArgumentOrder() {
        val first = RouteEntryState(
            "modal:settings", RouteKind.Modal, "settings", mapOf("a" to "1", "b" to "2"), policy = below,
        )
        val second = RouteEntryState(
            "modal:settings", RouteKind.Modal, "settings", mapOf("b" to "2", "a" to "1"), policy = below,
        )

        assertEquals(
            StackStateSchema.encode(StackState(listOf(first))).toString(Charsets.UTF_8),
            StackStateSchema.encode(StackState(listOf(second))).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun malformedBytesDecodeToNull() {
        assertNull(StackStateSchema.decode(byteArrayOf(1, 2, 3)))
        assertNull(StackStateSchema.decode("not-a-stack".toByteArray(Charsets.UTF_8)))
        assertNull(StackStateSchema.decode("2:hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun unknownKindDecodeToNull() {
        val encoded = StackStateSchema.encode(sample).toString(Charsets.UTF_8)
        assertNull(StackStateSchema.decode(encoded.replace("Layer", "Ghosts").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun storeRoundTripsThroughKey() {
        val store = store()
        store.write(StackStateKey, sample)

        assertEquals(sample, store.read(StackStateKey))
    }

    @Test
    fun missingStoreEntryReadsDefault() {
        assertEquals(StackState.EMPTY, store().read(StackStateKey))
    }

    @Test
    fun forwardVersionFallsBackToDefault() {
        val backend = InMemoryStateBackend()
        val payload = StackStateSchema.encode(sample)
        val frame = ByteBuffer.allocate(4 + payload.size)
            .putInt(StackStateSchema.version + 1)
            .put(payload)
            .array()
        backend.put(StackStateKey.fullName, frame)

        assertEquals(StackState.EMPTY, DefaultStateStore(backend).read(StackStateKey))
    }

    @Test
    fun screenEntryMissingIdHasNoRoute() {
        val entry = RouteEntryState("screen:", RouteKind.Screen, null, policy = below)
        assertNull(entry.toRoute())
    }
}
