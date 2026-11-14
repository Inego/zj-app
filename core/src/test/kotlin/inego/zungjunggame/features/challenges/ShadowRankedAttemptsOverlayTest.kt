package inego.zungjunggame.features.challenges

import inego.zungjunggame.render.ui.VerticalScrollbar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShadowRankedAttemptsOverlayTest {

    @Test
    fun overflowingAttemptsCardEnablesScrollbarDragInGutter() {
        val overlay = ShadowRankedAttemptsOverlay()
        overlay.seedLayoutForTest(contentHeight = 2000f, scrollY = 0f)

        assertTrue(overlay.overflowingForTest)
        assertEquals(VerticalScrollbar.gutterWidth(), overlay.gutterForTest)
        assertTrue(overlay.maxScrollForTest > 0f)

        val listLeft = overlay.listLeftForTest
        val listW = overlay.listWidthForTest
        val listBottom = overlay.listBottomForTest
        val listH = overlay.listHeightForTest

        // Press on the scrollbar track (within the reserved gutter on the right edge)
        val gutterCenterX = listLeft + listW - overlay.gutterForTest * 0.5f
        val pressY = listBottom + listH * 0.5f

        val claimed = overlay.onPointerDown(gutterCenterX, pressY)
        assertTrue(claimed)
        assertTrue(overlay.isScrollbarDraggingForTest, "Pointer down in gutter must claim scrollbar drag")

        val dragVerdict = overlay.onPointerDrag(gutterCenterX, pressY - 40f)
        assertNotNull(dragVerdict)
        val dragScroll = dragVerdict.dragScrollOffset
        assertNotNull(dragScroll)
        assertTrue(dragScroll > 0f)

        val upVerdict = overlay.onPointerUp(gutterCenterX, pressY - 40f)
        assertFalse(overlay.isScrollbarDraggingForTest)
        assertNotNull(upVerdict.settledScrollOffset)
        assertEquals(dragScroll, upVerdict.settledScrollOffset)
        assertFalse(upVerdict.close)
    }

    @Test
    fun nonOverflowingAttemptsCardReservesNoGutterAndIgnoresScrollbarDrag() {
        val overlay = ShadowRankedAttemptsOverlay()
        overlay.seedLayoutForTest(contentHeight = 100f, scrollY = 0f)

        assertFalse(overlay.overflowingForTest)
        assertEquals(0f, overlay.gutterForTest)
        assertEquals(0f, overlay.maxScrollForTest)

        val listLeft = overlay.listLeftForTest
        val listW = overlay.listWidthForTest
        val listBottom = overlay.listBottomForTest
        val listH = overlay.listHeightForTest

        val rightEdgeX = listLeft + listW - 4f
        val pressY = listBottom + listH * 0.5f

        overlay.onPointerDown(rightEdgeX, pressY)
        assertFalse(overlay.isScrollbarDraggingForTest)
    }

    @Test
    fun clickingLoadMoreResolvesSelectedIdWhenNotDragged() {
        val overlay = ShadowRankedAttemptsOverlay()
        overlay.seedLayoutForTest(contentHeight = 2000f, scrollY = 0f)
        overlay.seedLoadMoreHitForTest(120f, 80f, 300f, 44f)

        val claimed = overlay.onPointerDown(200f, 100f)
        assertTrue(claimed)

        val verdict = overlay.onPointerUp(200f, 100f)
        assertEquals(inego.zungjunggame.net.ShadowRankedAttemptsClient.LOAD_MORE_ID, verdict.selectedId)
    }

    @Test
    fun draggingOverLoadMoreDoesNotTriggerIt() {
        val overlay = ShadowRankedAttemptsOverlay()
        overlay.seedLayoutForTest(contentHeight = 2000f, scrollY = 0f)
        overlay.seedLoadMoreHitForTest(120f, 80f, 300f, 44f)

        overlay.onPointerDown(200f, 100f)
        overlay.onPointerDrag(200f, 150f)
        val verdict = overlay.onPointerUp(200f, 150f)
        assertNull(verdict.selectedId)
    }
}
