package inego.zungjunggame.render.ui

import inego.zungjunggame.ui.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scroll contract every scrolling overlay now shares.
 *
 * Pure arithmetic: no GL context and no fonts. [VerticalScrollbar.gutterWidth]
 * is a `Fonts.px` product at the default ui-scale of 1, so it is a constant here.
 * Every input method takes the owner's value through [OverlayBodyScroll.layout]
 * and returns the value it resolved; the body stores nothing across frames.
 */
class OverlayBodyScrollTest {

    private val gutter = VerticalScrollbar.gutterWidth()

    /** Body at (0, 100), 200×90, holding 190 of content: 100 of scroll. */
    private fun scrolled(scrollY: Float = 0f): OverlayBodyScroll = OverlayBodyScroll().apply {
        layout(
            left = 0f,
            bottom = 100f,
            width = 200f,
            height = 90f,
            contentHeight = 190f,
            scrollY = scrollY,
        )
    }

    // ── Wrap width ──────────────────────────────────────────────────────────

    @Test
    fun measureWidthAlwaysReservesTheStrip() {
        // Not "when asked". A wrap width that moved once the body started
        // scrolling re-wrapped the prose at the moment the bar appeared, changed
        // the content's height, and jumped the whole column.
        val body = OverlayBodyScroll()
        assertEquals(260f - gutter, body.measureWidth(300f, 40f))
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    @Test
    fun fitAndPlaceHugsCentersAndKeepsTheBodyInset() {
        val avail = Rect(left = 0f, bottom = 0f, width = 400f, height = 300f)
        val body = OverlayBodyScroll()
        val fitted = body.fitAndPlace(
            avail = avail,
            panelW = 300f,
            chromeH = 100f,
            bodyContentH = 400f,
            bodyInset = 20f,
            scrollY = 200f,
        )

        assertEquals(300f, fitted.cardH)
        assertEquals(50f, fitted.left)
        assertEquals(0f, fitted.bottom)
        assertEquals(200f, fitted.bodyViewH)
        assertEquals(200f, fitted.maxScrollY)

        // Sticky chrome above the body is chromeH minus the inset it also owns.
        assertEquals(20f, body.bottom)
        assertEquals(80f, fitted.bottom + fitted.cardH - (body.bottom + body.height))

        // The owner's offset is installed as the frame's value: scrolled to the
        // end the content stops exactly one inset above the card.
        assertEquals(200f, body.scrollY)
        assertEquals(fitted.bottom + 20f, body.contentTop - 400f)
    }

    @Test
    fun layoutInstallsAndClampsTheOwnersValue() {
        val body = OverlayBodyScroll()
        body.layout(left = 0f, bottom = 0f, width = 200f, height = 90f, contentHeight = 190f, scrollY = 1000f)
        assertEquals(100f, body.maxScrollY)
        assertEquals(100f, body.scrollY, "over-range owner value clamps to the frame range")

        body.layout(left = 0f, bottom = 0f, width = 200f, height = 90f, contentHeight = 190f, scrollY = 40f)
        assertEquals(40f, body.scrollY, "the next layout replaces the frame's value")
    }

    @Test
    fun contentYFromCountsDownFromTheFirstRowIncludingScroll() {
        val body = scrolled()
        assertEquals(0f, body.contentYFrom(190f))
        assertEquals(90f, body.contentYFrom(100f))

        val atEnd = scrolled(scrollY = 100f)
        assertEquals(100f, atEnd.contentYFrom(190f))
        assertEquals(190f, atEnd.contentYFrom(100f))
    }

    @Test
    fun rowIndexAtWalksFixedStrideRowsAndRejectsTheGaps() {
        val body = scrolled()
        // stride 30, row 20 of it, 3 rows.
        fun at(y: Float) = body.rowIndexAt(y, headerH = 0f, rowH = 20f, strideY = 30f, count = 3)

        assertEquals(0, at(190f))
        assertEquals(0, at(170f), "the row's last pixel is still the row")
        assertNull(at(169f), "the gap between two rows is not a row")
        assertEquals(1, at(160f))
        assertEquals(2, at(130f))
        assertNull(at(100f), "past the last row")
        assertNull(at(191f), "above the first row")
    }

    @Test
    fun rowIndexAtHonoursAHeaderBand() {
        val body = scrolled()
        // 40 of header above the first row, so the rows start 40 lower.
        assertEquals(0, body.rowIndexAt(150f, headerH = 40f, rowH = 20f, strideY = 30f, count = 3))
        assertNull(body.rowIndexAt(160f, headerH = 40f, rowH = 20f, strideY = 30f, count = 3))
    }

    @Test
    fun revealRowOffsetLeavesAnAlreadyVisibleRowAlone() {
        val body = scrolled(scrollY = 40f) // viewport is [40, 130] in content coords
        // row 2: stride 30, rowH 20 -> itemTop = 60, itemBottom = 80 (inside [40, 130])
        assertEquals(40f, body.revealRowOffset(index = 2, rowH = 20f, strideY = 30f, currentScrollY = 40f))
        // invalid index or empty list keeps current
        assertEquals(40f, body.revealRowOffset(index = -1, rowH = 20f, strideY = 30f, currentScrollY = 40f))
    }

    @Test
    fun revealRowOffsetPullsAnOffscreenRowIntoViewMinimally() {
        val body = scrolled(scrollY = 0f) // viewport is [0, 90], maxScroll = 100
        // row 4: itemTop = 120, itemBottom = 140 (below viewport [0, 90]).
        // Target: itemBottom - height = 140 - 90 = 50.
        assertEquals(50f, body.revealRowOffset(index = 4, rowH = 20f, strideY = 30f, currentScrollY = 0f))

        // When scrolled to 80 (view [80, 170]):
        // row 1: itemTop = 30, itemBottom = 50 (above viewport [80, 170]).
        // Target: itemTop = 30.
        assertEquals(30f, body.revealRowOffset(index = 1, rowH = 20f, strideY = 30f, currentScrollY = 80f))
    }

    @Test
    fun hitsContentRejectsTheScrollbarStrip() {
        val body = scrolled()
        assertTrue(body.hitsContent(100f, 150f))
        assertTrue(body.hitsContent(200f - gutter - 0.5f, 150f))
        assertFalse(body.hitsContent(200f - gutter, 150f), "the strip belongs to the scrollbar")
        assertFalse(body.hitsContent(100f, 99f), "below the body")
        assertFalse(body.hitsContent(100f, 191f), "above the body")
    }

    @Test
    fun aBodyThatFitsReservesNoStrip() {
        val body = OverlayBodyScroll()
        body.layout(left = 0f, bottom = 0f, width = 200f, height = 200f, contentHeight = 200f, scrollY = 0f)
        assertFalse(body.overflowing)
        assertEquals(0f, body.gutter)
        assertTrue(body.hitsContent(199.5f, 100f), "with nothing to scroll the strip is content")
        assertEquals(160f, body.contentWidth(200f, 40f))
    }

    // ── Input ───────────────────────────────────────────────────────────────

    @Test
    fun hoverBlanksOnlyWhileADragOwnsTheGesture() {
        val body = scrolled()
        assertEquals(11f, body.hoverX(11f))
        assertEquals(22f, body.hoverY(22f))

        assertTrue(body.beginPointer(100f, 150f))
        body.dragTo(120f) // past the slop, so this is a drag now
        assertTrue(body.isDrag)
        assertTrue(body.hoverX(11f).isNaN(), "nothing is hovered mid-flick")
        assertTrue(body.hoverY(22f).isNaN())

        assertTrue(body.endPointer())
        assertEquals(11f, body.hoverX(11f))
    }

    @Test
    fun aDragReturnsTheAbsoluteOffsetAndAccumulates() {
        val body = scrolled(scrollY = 20f)
        assertTrue(body.beginPointer(100f, 150f))
        assertEquals(40f, body.dragTo(170f), 1e-4f, "content follows the finger")
        assertEquals(100f, body.dragTo(230f), 1e-4f, "still measured from the press point")
        assertEquals(100f, body.dragTo(1000f), 1e-4f, "clamped at the end")
        assertEquals(100f, body.scrollY, "the frame value follows the drag")
        assertTrue(body.endPointer())
    }

    @Test
    fun aPressThatNeverMovedIsNotADrag() {
        val body = scrolled()
        assertTrue(body.beginPointer(100f, 150f))
        assertFalse(body.endPointer(), "a tap must not be reported as a scroll")
    }

    @Test
    fun aPressOutsideTheBodyIsNotClaimed() {
        val body = scrolled()
        assertFalse(body.beginPointer(300f, 150f))
        assertFalse(body.beginPointer(100f, 50f))
    }

    @Test
    fun aPressOnTheScrollbarStripClaimsTheThumbDragImmediatelyAfterLayout() {
        val body = scrolled(scrollY = 0f)
        assertTrue(body.overflowing)
        val gutterCenterX = 200f - gutter * 0.5f
        assertTrue(body.beginPointer(gutterCenterX, 150f))
        assertTrue(body.isScrollbarDrag, "scrollbar drag must be claimed on the gutter strip")
        body.dragTo(110f)
        assertTrue(body.scrollY > 0f)
        assertTrue(body.endPointer())
        assertFalse(body.isScrollbarDrag)
    }
}
