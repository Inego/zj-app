package inego.zungjunggame.persist

import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persisted notification settings: the current layout round-trips, and every
 * older layout migrates without dropping the opt-in, the cadence, or the language.
 */
class NotificationPrefsStateTest {

    private val backend = InMemoryStateBackend()

    @Test
    fun theCurrentLayoutRoundTrips() {
        val prefs = NotificationPrefs(
            emailOptIn = true,
            digestIntervalHours = 6,
            language = "ru",
            timeZone = "Europe/Berlin",
        )
        val slot = StateSlot(DefaultStateStore(backend), NotificationPrefsKey)
        slot.set(prefs)
        assertEquals(
            prefs,
            StateSlot(DefaultStateStore(backend), NotificationPrefsKey).requireValue(),
        )
    }

    @Test
    fun olderLayoutsMigrateWithTheirChoicesIntact() {
        // v1 knew the opt-in and an immediate count. The count has no cadence, so the
        // interval becomes the once-a-day default and the later fields stay unset.
        backend.put(
            NotificationPrefsKey.fullName,
            frame(1, RecordWriter().boolean(true).int(4).build()),
        )
        val fromV1 = StateSlot(DefaultStateStore(backend), NotificationPrefsKey).requireValue()
        assertTrue(fromV1.emailOptIn)
        assertEquals(NotificationPrefs.DEFAULT.digestIntervalHours, fromV1.digestIntervalHours)
        assertNull(fromV1.language)
        assertNull(fromV1.timeZone)

        // v3 knew the language and not the zone.
        backend.put(
            NotificationPrefsKey.fullName,
            frame(3, RecordWriter().boolean(true).int(6).field("zh-HK").build()),
        )
        val fromV3 = StateSlot(DefaultStateStore(backend), NotificationPrefsKey).requireValue()
        assertTrue(fromV3.emailOptIn)
        assertEquals(6, fromV3.digestIntervalHours)
        assertEquals("zh-HK", fromV3.language)
        assertNull(fromV3.timeZone)
    }

    @Test
    fun aTruncatedCurrentLayoutIsCorrupt() {
        assertNull(
            NotificationPrefsSchema.decode(
                RecordWriter().boolean(true).int(6).field("ru").build(),
            ),
        )
    }

    /** The store's framing: a 4-byte big-endian schema version, then the payload. */
    private fun frame(version: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()
}
