package inego.zungjunggame.render.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The controlled scroll core: every method takes the owner's current value and
 * returns the value the interaction produced, so nothing outlives the gesture
 * except the drag origin, the sampled velocity, and the coasting flag.
 */
class VerticalScrollTest {

    @Test
    fun dragAccumulatesFromTheOriginAndClamps() {
        val scroll = VerticalScroll()
        scroll.beginDrag(worldY = 500f, current = 100f)

        assertEquals(100f, scroll.dragTo(497f, 1000f), 1e-4f, "sub-slop motion does not move")
        assertFalse(scroll.isDrag)

        assertEquals(90f, scroll.dragTo(490f, 1000f), 1e-4f, "past slop the full delta applies")
        assertTrue(scroll.isDrag)

        // Multiple move events in one frame are all measured from the press
        // point, so no delta is lost and the total is applied once.
        assertEquals(70f, scroll.dragTo(470f, 1000f), 1e-4f)
        assertEquals(0f, scroll.dragTo(200f, 1000f), "clamped at the top")
        assertEquals(1000f, scroll.dragTo(3000f, 1000f), "clamped at the end")
    }

    @Test
    fun aPressThatNeverMovedIsNotADrag() {
        val scroll = VerticalScroll()
        scroll.beginDrag(worldY = 500f, current = 40f)
        assertFalse(scroll.endDrag())
        assertFalse(scroll.isFlinging)
        // After the gesture the widget holds no offset: the idle method answers
        // from its argument.
        assertEquals(40f, scroll.advance(1f / 60f, 40f, 1000f))
    }

    @Test
    fun aReleasedFlingCoastsThenStops() {
        val scroll = VerticalScroll()
        // Drag up (y-up: later content) from the top, well short of the end so
        // every sampled step is unclamped and feeds the velocity.
        scroll.beginDrag(worldY = 100f, current = 0f)
        repeat(5) { i ->
            Thread.sleep(4)
            scroll.dragTo(100f + (i + 1) * 60f, 1000f)
        }
        assertTrue(scroll.endDrag(), "the moves were a drag")
        assertTrue(scroll.isFlinging, "a fast release arms inertia")

        var offset = 300f
        var steps = 0
        while (scroll.isFlinging && steps < 1000) {
            val next = scroll.advance(1f / 60f, offset, 1000f)
            assertTrue(next >= offset, "the coast keeps moving the same way")
            assertTrue(next <= 1000f)
            offset = next
            steps++
        }
        assertFalse(scroll.isFlinging, "the coast settles")
        assertTrue(steps in 1 until 1000)
        // Settled: advancing again answers from its argument, unchanged.
        assertEquals(offset, scroll.advance(1f / 60f, offset, 1000f))
    }

    @Test
    fun cancelDropsTheDragAndTheCoast() {
        val scroll = VerticalScroll()
        scroll.beginDrag(worldY = 500f, current = 100f)
        scroll.dragTo(300f, 1000f)
        scroll.cancel()
        assertFalse(scroll.isDrag)
        assertFalse(scroll.isFlinging)
        assertFalse(scroll.endDrag(), "there is no drag left to end")
    }
}
