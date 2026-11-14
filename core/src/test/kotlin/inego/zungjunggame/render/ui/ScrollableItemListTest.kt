package inego.zungjunggame.render.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollableItemListTest {

    private fun sampleItems(count: Int): List<ListItem> =
        (0 until count).map { i ->
            ListItem(
                id = "item_$i",
                label = "Item $i",
                number = "${i + 1}",
                selectable = true,
            )
        }

    /**
     * 20 items: rowH = 32, gap = 2 -> stride = 34. Total height =
     * 20 * 32 + 19 * 2 = 678. Viewport height = 200 -> maxScroll = 478.
     */
    private fun list(count: Int = 20): ScrollableItemList = ScrollableItemList().apply {
        setViewport(0f, 0f, 200f, 200f)
        setItems(sampleItems(count))
    }

    @Test
    fun revealOffsetLeavesAnAlreadyVisibleRowAlone() {
        val list = list()
        // item_10: itemTop = 340, itemBottom = 372 — inside the [300, 500] view.
        assertEquals(300f, list.revealOffset("item_10", current = 300f))
        // item_18: itemTop = 612, itemBottom = 644 — inside the [478, 678] view.
        assertEquals(478f, list.revealOffset("item_18", current = 478f))
        // Unknown ids and a null selection keep what they were handed.
        assertEquals(300f, list.revealOffset("missing", current = 300f))
        assertEquals(300f, list.revealOffset(null, current = 300f))
    }

    @Test
    fun revealOffsetPullsAnOffscreenRowIntoViewMinimally() {
        val list = list()
        // item_0 is above viewTop = 300: go to its top.
        assertEquals(0f, list.revealOffset("item_0", current = 300f))
        // item_10: itemTop = 340, itemBottom = 372; viewport is [0, 200], so
        // bring its bottom to the viewport bottom.
        assertEquals(172f, list.revealOffset("item_10", current = 0f))
    }

    @Test
    fun revealOffsetCannotMoveBeforeTheViewportIsLaidOut() {
        val list = ScrollableItemList()
        list.setItems(sampleItems(20))
        // No setViewport yet: the caller's value stands unclamped, so a restore
        // waiting for the first frame is not zeroed.
        assertEquals(150f, list.revealOffset("item_10", current = 150f))
    }

    @Test
    fun restatingTheSameRowsLeavesAScrollbarDragRunning() {
        val list = list()
        list.bindFrame(0f, 0f, 200f, 200f, scrollY = 0f)
        val gutter = VerticalScrollbar.gutterWidth()
        list.onPointerDown(200f - gutter * 0.5f, 100f)
        val grabbed = list.scrollY
        list.onPointerDrag(40f)
        val dragged = list.scrollY
        assertTrue(dragged > grabbed, "thumb mapping must move the offset")

        list.setItems(sampleItems(20))
        list.onPointerDrag(20f)
        assertTrue(list.scrollY > dragged, "the same rows must not abort the thumb drag")
    }

    @Test
    fun setItemsResolvesTheSelectionWithoutTouchingAScroll() {
        val list = list()
        // An unknown selection falls back to the first selectable row; a valid
        // one is kept.
        list.setItems(sampleItems(5), selectedId = "item_4")
        assertEquals("item_4", list.selectedId)
        list.setItems(sampleItems(5), selectedId = "missing")
        assertEquals("item_0", list.selectedId)
    }

    @Test
    fun aTapWithoutAFrameValueSelectsTheTopRow() {
        val list = list()
        // With no frame installed the viewport starts at 0; the item under the
        // pointer is resolved from the geometry alone.
        list.onPointerDown(50f, 180f)
        val up = list.onPointerUp()
        assertEquals("item_0", up.selectedId)
        assertNull(up.settledScrollOffset)
    }
}
