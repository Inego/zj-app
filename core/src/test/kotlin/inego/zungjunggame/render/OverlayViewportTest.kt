package inego.zungjunggame.render

import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.ui.Rect
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayViewportTest {

    @AfterTest
    fun tearDown() {
        SafeArea.clear()
    }

    @Test
    fun safeAreaAndMarginShrinkTheRect() {
        val avail = OverlayViewport.available(
            screenW = 2700f,
            screenH = 1224f,
            safe = SafeArea.Insets(left = 80f, top = 0f, right = 80f, bottom = 40f),
            marginPx = 12f,
        )
        assertEquals(92f, avail.left)
        assertEquals(52f, avail.bottom)
        assertEquals(2516f, avail.width)
        assertEquals(1160f, avail.height)
    }

    @Test
    fun placeCenteredStaysInsideWhenPanelIsSmaller() {
        val avail = OverlayViewport.available(800f, 480f, marginPx = 12f)
        val (left, bottom) = OverlayViewport.placeCentered(200f, 100f, avail)
        assertTrue(left >= avail.left)
        assertTrue(bottom >= avail.bottom)
        assertTrue(left + 200f <= avail.right + 1e-3f)
        assertTrue(bottom + 100f <= avail.top + 1e-3f)
    }

    @Test
    fun placeCenteredPinsWhenPanelFillsAvailable() {
        val avail = Rect(12f, 12f, 2676f, 1200f)
        val (left, bottom) = OverlayViewport.placeCentered(avail.width, avail.height, avail)
        assertEquals(avail.left, left)
        assertEquals(avail.bottom, bottom)
    }

    @Test
    fun fitStickyMenuOverflowScrollsAndKeepsCardOnScreen() {
        // Local-game menu: 8×144dp rows + chrome on a 1200px landscape avail.
        val chrome = 262f
        val body = 8f * 144f + 7f * 24f
        val fit = OverlayViewport.fitSticky(
            availableH = 1200f,
            chromeH = chrome,
            bodyContentH = body,
        )
        assertEquals(1200f, fit.cardH)
        assertEquals(1200f, fit.totalH)
        assertEquals(1200f - chrome, fit.bodyViewH)
        assertTrue(fit.maxScrollY > 0f)
        assertEquals(body - fit.bodyViewH, fit.maxScrollY, 1e-3f)
    }

    @Test
    fun fitStickyReservesFooterUnderTheCard() {
        val fit = OverlayViewport.fitSticky(
            availableH = 1200f,
            chromeH = 250f,
            bodyContentH = 1400f,
            footerH = 170f,
        )
        assertEquals(1030f, fit.cardH)
        assertEquals(1200f, fit.totalH)
        assertEquals(780f, fit.bodyViewH)
        assertEquals(620f, fit.maxScrollY)
    }

    @Test
    fun fitStickyHugsWhenContentFits() {
        val fit = OverlayViewport.fitSticky(
            availableH = 1200f,
            chromeH = 200f,
            bodyContentH = 300f,
        )
        assertEquals(500f, fit.cardH)
        assertEquals(300f, fit.bodyViewH)
        assertEquals(0f, fit.maxScrollY)
    }

    @Test
    fun clampSizeNeverExceedsAvailable() {
        val avail = Rect(0f, 0f, 400f, 200f)
        val (w, h) = OverlayViewport.clampSize(800f, 900f, avail)
        assertEquals(400f, w)
        assertEquals(200f, h)
    }
}
