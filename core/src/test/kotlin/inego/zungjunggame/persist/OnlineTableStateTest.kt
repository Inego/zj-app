package inego.zungjunggame.persist

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `online.table` key: round trip, the empty-means-none spelling, and the
 * corruption gates.
 */
class OnlineTableStateTest {

    private val store = DefaultStateStore(InMemoryStateBackend())
    private val slot = StateSlot(store, OnlineTableIdKey)

    @Test
    fun clearLeavesNoTable() {
        slot.set("tbl_42")
        slot.clear()
        assertEquals("", slot.requireValue())
    }

    @Test
    fun schemaRoundTripsAndRejectsCorruption() {
        assertEquals("tbl_42", OnlineTableIdSchema.decode(OnlineTableIdSchema.encode("tbl_42")))
        assertEquals("", OnlineTableIdSchema.decode(OnlineTableIdSchema.encode("")))

        assertNull(OnlineTableIdSchema.decode(ByteArray(0)))
        assertNull(OnlineTableIdSchema.decode(RecordWriter().field("a").build() + "1:x".toByteArray()))
    }
}
