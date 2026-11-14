package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `platform.window` key: round trip, defaults, and the storage layer's
 * version/corruption gates.
 */
class WindowStateKeyTest {
    private val backend = InMemoryStateBackend()
    private val slot = StateSlot(DefaultStateStore(backend), WindowStateKey)

    @Test
    fun anAbsentPositionRoundTripsAsAbsent() {
        slot.set(WindowState(width = 1280, height = 768))

        val read = StateSlot(DefaultStateStore(backend), WindowStateKey).requireValue()

        assertNull(read.x, "no stored position means the OS centers the window")
        assertNull(read.y)
    }

    @Test
    fun corruptBytesFallBackToTheDefault() {
        backend.put(WindowStateKey.fullName, frame(WindowStateSchema.version, ByteArray(0)))
        assertEquals(WindowState.DEFAULT, slot.requireValue())
    }

    @Test
    fun aForwardVersionFallsBackToTheDefault() {
        backend.put(
            WindowStateKey.fullName,
            frame(
                WindowStateSchema.version + 1,
                WindowStateSchema.encode(WindowState(width = 1600, height = 900)),
            ),
        )

        assertEquals(WindowState.DEFAULT, slot.requireValue())
    }

    @Test
    fun sanitizedRaisesASizeBelowTheMinimum() {
        val small = WindowState(width = 10, height = 20).sanitized()

        assertEquals(WindowState.MIN_WIDTH, small.width)
        assertEquals(WindowState.MIN_HEIGHT, small.height)
    }

    @Test
    fun sanitizedDropsMaximizedWhenFullscreen() {
        val both = WindowState(fullscreen = true, maximized = true, x = 40, y = 60).sanitized()

        assertTrue(both.fullscreen)
        assertFalse(both.maximized, "a fullscreen frame already covers a maximized one")
        assertEquals(40, both.x)
        assertEquals(60, both.y)
    }

    /** The store's framing: a 4-byte big-endian schema version, then the payload. */
    private fun frame(version: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()
}
