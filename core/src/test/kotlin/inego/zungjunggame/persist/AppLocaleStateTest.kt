package inego.zungjunggame.persist

import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The persisted UI language: default, round trip, and the unknown-tag gate.
 */
class AppLocaleStateTest {

    private val store = DefaultStateStore(InMemoryStateBackend())
    private val slot = StateSlot(store, AppLocaleKey)

    @Test
    fun defaultWhenUnset() {
        assertEquals(AppLocale.DEFAULT, slot.requireValue())
        assertEquals(AppLocale.DEFAULT_TAG, slot.requireValue().tag)
    }

    @Test
    fun unknownTagIsCorruptAndFallsBackToDefault() {
        assertNull(AppLocaleSchema.decode(RecordWriter().field("klingon").build()))
        assertNull(AppLocaleSchema.decode(ByteArray(0)))
        // Only the exact persisted tag is valid; a bare language is legacy-import
        // vocabulary and is normalized on the way in, not on the way out.
        assertNull(AppLocaleSchema.decode(RecordWriter().field("ru-RU").build()))
    }
}
