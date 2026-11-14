package inego.zungjunggame.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitRegionsTest {

    private val a = ComponentAction("a")
    private val b = ComponentAction("b")
    private val first = Rect(0f, 0f, 10f, 10f)
    private val overlapping = Rect(5f, 5f, 10f, 10f)

    @Test
    fun emptyReturnsNull() {
        assertNull(HitRegions<ComponentAction>().hitAt(5f, 5f))
    }

    @Test
    fun singleRegionHitsInsideAndMissesOutside() {
        val regions = HitRegions<ComponentAction>()
        regions.add(first, a)
        assertEquals(a, regions.hitAt(5f, 5f))
        assertNull(regions.hitAt(50f, 5f))
    }

    @Test
    fun lowerRankWinsOverlap() {
        val regions = HitRegions<ComponentAction>()
        regions.add(overlapping, a, rank = 5)
        regions.add(first, b, rank = 1)
        assertEquals(b, regions.hitAt(6f, 6f))
    }

    @Test
    fun equalRankKeepsInsertionOrder() {
        val regions = HitRegions<ComponentAction>()
        regions.add(first, a)
        regions.add(overlapping, b)
        assertEquals(a, regions.hitAt(6f, 6f))
    }

    @Test
    fun equalRankReversedOrderFlipsWinner() {
        val regions = HitRegions<ComponentAction>()
        regions.add(overlapping, b)
        regions.add(first, a)
        assertEquals(b, regions.hitAt(6f, 6f))
    }

    @Test
    fun rectsForReturnsEveryRectForAction() {
        val regions = HitRegions<ComponentAction>()
        val second = Rect(20f, 0f, 10f, 10f)
        regions.add(first, a)
        regions.add(second, a)
        regions.add(overlapping, b)
        assertEquals(listOf(first, second), regions.rectsFor(a))
        assertEquals(listOf(overlapping), regions.rectsFor(b))
        assertTrue(regions.rectsFor(ComponentAction("missing")).isEmpty())
    }

    @Test
    fun clearEmptiesAndSizeTracksRegions() {
        val regions = HitRegions<ComponentAction>()
        assertEquals(0, regions.size)
        regions.add(first, a)
        regions.add(overlapping, b, rank = 1)
        assertEquals(2, regions.size)
        regions.clear()
        assertEquals(0, regions.size)
        assertNull(regions.hitAt(5f, 5f))
    }
}
