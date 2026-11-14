package inego.zungjunggame.persist

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.RouteUiValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedRouteUiStateTest {

    private val slot = StateSlot(DefaultStateStore(InMemoryStateBackend()), RememberedRouteUiKey)

    @Test
    fun defaultIsEmpty() {
        assertEquals(RememberedRouteUi.EMPTY, slot.requireValue())
    }

    @Test
    fun entriesRoundTripThroughTheKey() {
        val value = RememberedRouteUi(
            mapOf("modal:duplicate_setup" to RouteUiValue("setupCard", "v1:2:true:0.0")),
        )
        slot.set(value)

        assertEquals(value, slot.requireValue())
    }

    @Test
    fun truncationTrailingGarbageAndEmptyBytesAreCorrupt() {
        val encoded = RememberedRouteUiSchema.encode(RememberedRouteUi.EMPTY)
        assertNull(RememberedRouteUiSchema.decode(encoded.copyOfRange(0, encoded.size - 1)))
        assertNull(RememberedRouteUiSchema.decode(encoded + "junk".toByteArray()))
        assertNull(RememberedRouteUiSchema.decode(ByteArray(0)))
    }
}
