package inego.zungjunggame.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RectTest {

    private val rect = Rect(left = 10f, bottom = 20f, width = 100f, height = 50f)

    @Test
    fun containsInside() {
        assertTrue(rect.contains(60f, 45f))
    }

    @Test
    fun containsEdge() {
        assertTrue(rect.contains(10f, 20f))
        assertTrue(rect.contains(110f, 70f))
        assertTrue(rect.contains(10f, 70f))
    }

    @Test
    fun containsOutside() {
        assertFalse(rect.contains(9.9f, 45f))
        assertFalse(rect.contains(60f, 19.9f))
        assertFalse(rect.contains(110.1f, 45f))
        assertFalse(rect.contains(60f, 70.1f))
    }

    @Test
    fun intersectsOverlap() {
        assertTrue(rect.intersects(Rect(50f, 30f, 100f, 100f)))
        assertTrue(rect.intersects(Rect(0f, 0f, 20f, 30f)))
    }

    @Test
    fun intersectsTouchIsFalse() {
        assertFalse(rect.intersects(Rect(110f, 20f, 10f, 10f)))
        assertFalse(rect.intersects(Rect(10f, 70f, 10f, 10f)))
    }

    @Test
    fun intersectsDisjointIsFalse() {
        assertFalse(rect.intersects(Rect(200f, 200f, 10f, 10f)))
        assertFalse(rect.intersects(Rect(0f, 0f, 5f, 5f)))
    }
}
