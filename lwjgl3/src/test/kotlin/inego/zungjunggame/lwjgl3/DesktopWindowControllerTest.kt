package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The geometry round trip through `platform.window`: what the controller adopts
 * at launch is what it writes back when the window goes away.
 *
 * No libGDX application exists here, which is the point: the controller reads and
 * writes the key without it, exactly as the launcher does. (The live fullscreen
 * and maximize flags it also stores can only be exercised with a window.)
 */
class DesktopWindowControllerTest {
    private val backend = InMemoryStateBackend()
    private val slot = StateSlot(DefaultStateStore(backend), WindowStateKey)

    @Test
    fun aStoredGeometryBelowTheMinimumIsRaisedToIt() {
        slot.set(WindowState(width = 10, height = 10))

        val controller = DesktopWindowController(slot)
        controller.persist()

        assertEquals(WindowState.MIN_WIDTH, slot.requireValue().width)
        assertEquals(WindowState.MIN_HEIGHT, slot.requireValue().height)
    }
}
