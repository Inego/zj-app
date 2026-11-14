package inego.zungjunggame.features.auth

import inego.zungjunggame.ui.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartingLevelOverlayTest {

    private val panel = Rect(40f, 40f, 320f, 360f)
    private val novice = Rect(60f, 260f, 280f, 46f)
    private val amateur = Rect(60f, 200f, 280f, 46f)
    private val strong = Rect(60f, 140f, 280f, 46f)
    @Test
    fun resetClearsBusyAndError() {
        val overlay = StartingLevelOverlay()

        overlay.setBusy(true)
        assertTrue(overlay.busy)

        overlay.setError("Test error")
        assertFalse(overlay.busy)
        assertEquals("Test error", overlay.errorText)

        overlay.reset()
        assertFalse(overlay.busy)
        assertEquals("", overlay.errorText)
        overlay.dispose()
    }

    @Test
    fun busyReleasePicksNothing() {
        val overlay = StartingLevelOverlay()

        // Busy: the press is claimed, yet the release picks nothing because the
        // request is still in flight.
        overlay.seedLayoutForTest(panel, novice, amateur, strong, busy = true)
        assertTrue(overlay.onPointerDown(novice.centerX, novice.centerY))
        assertEquals(
            StartingLevelOverlay.PointerResult.None,
            overlay.onPointerUp(novice.centerX, novice.centerY),
        )
        overlay.dispose()
    }
}
