package inego.zungjunggame.persist

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persisted half of the display preferences: the tile-label choice, the
 * hand-end card's fold, parked place and body scroll, and the board chrome's parks
 * — defaults, the migration chain every older layout walks, and the corruption
 * gates.
 */
class UiPreferencesStateTest {

    private val backend = InMemoryStateBackend()

    @Test
    fun olderLayoutsMigrateWithTheirChoicesIntact() {
        // v1 knew the label alone.
        backend.put(UiPreferencesKey.fullName, frame(1, RecordWriter().boolean(true).build()))
        val fromV1 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertTrue(fromV1.showTileLabels)
        assertEquals(0f, fromV1.breakdownParkX)
        assertEquals(0f, fromV1.breakdownParkY)
        assertFalse(fromV1.breakdownCollapsed)
        assertEquals(0f, fromV1.hintParkX)
        assertEquals(0f, fromV1.claimParkY)

        // v2 knew the label and the parked place.
        backend.put(
            UiPreferencesKey.fullName,
            frame(2, RecordWriter().boolean(true).float(-120f).float(40f).build()),
        )
        val fromV2 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertTrue(fromV2.showTileLabels)
        assertEquals(-120f, fromV2.breakdownParkX)
        assertEquals(40f, fromV2.breakdownParkY)
        assertFalse(fromV2.breakdownCollapsed)

        // v3 adds the fold; a payload from it gains the board parks at rest.
        backend.put(
            UiPreferencesKey.fullName,
            frame(3, RecordWriter().boolean(true).float(-120f).float(40f).boolean(true).build()),
        )
        val fromV3 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertTrue(fromV3.showTileLabels)
        assertEquals(-120f, fromV3.breakdownParkX)
        assertEquals(40f, fromV3.breakdownParkY)
        assertTrue(fromV3.breakdownCollapsed)
        assertEquals(0f, fromV3.hintParkX)
        assertEquals(0f, fromV3.hintParkY)
        assertEquals(0f, fromV3.claimParkX)
        assertEquals(0f, fromV3.claimParkY)
        assertEquals(0f, fromV3.breakdownScrollY)

        // v4 adds the board chrome parks; a payload from it gains the body scroll at top.
        backend.put(
            UiPreferencesKey.fullName,
            frame(
                4,
                RecordWriter().boolean(true).float(-120f).float(40f).boolean(true)
                    .float(10f).float(20f).float(5f).float(-4f).build(),
            ),
        )
        val fromV4 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertTrue(fromV4.showTileLabels)
        assertTrue(fromV4.breakdownCollapsed)
        assertEquals(10f, fromV4.hintParkX)
        assertEquals(20f, fromV4.hintParkY)
        assertEquals(5f, fromV4.claimParkX)
        assertEquals(-4f, fromV4.claimParkY)
        assertEquals(0f, fromV4.breakdownScrollY)

        // v5 adds the hand-end card's body scroll, read back from the current layout.
        backend.put(
            UiPreferencesKey.fullName,
            frame(
                5,
                RecordWriter().boolean(true).float(-120f).float(40f).boolean(true)
                    .float(10f).float(20f).float(5f).float(-4f).float(88f).build(),
            ),
        )
        val fromV5 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertEquals(88f, fromV5.breakdownScrollY)
        assertEquals(-120f, fromV5.breakdownParkX)
        // v5 predates sounds: it gains the master switch and the default level.
        assertTrue(fromV5.soundsEnabled)
        assertEquals(UiPreferences.DEFAULT_SOUND_VOLUME, fromV5.soundVolume)

        // v6 adds the sound switch and level, read back from the current layout.
        backend.put(
            UiPreferencesKey.fullName,
            frame(
                6,
                RecordWriter().boolean(true).float(-120f).float(40f).boolean(true)
                    .float(10f).float(20f).float(5f).float(-4f).float(88f)
                    .boolean(false).int(3).build(),
            ),
        )
        val fromV6 = StateSlot(DefaultStateStore(backend), UiPreferencesKey).requireValue()
        assertFalse(fromV6.soundsEnabled)
        assertEquals(3, fromV6.soundVolume)
    }

    @Test
    fun missingFieldAndTrailingGarbageAreCorrupt() {
        val current = RecordWriter().boolean(true).float(1f).float(2f).boolean(true)
            .float(0f).float(0f).float(0f).float(0f).float(3f)
            .boolean(true).int(8).build()
        assertNull(UiPreferencesSchema.decode(ByteArray(0)))
        // A current-version blob with a field missing, and one with a field too many.
        assertNull(UiPreferencesSchema.decode(RecordWriter().boolean(true).build()))
        assertNull(UiPreferencesSchema.decode(current + "1:x".toByteArray()))
    }

    /** The store's framing: a 4-byte big-endian schema version, then the payload. */
    private fun frame(version: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()
}
