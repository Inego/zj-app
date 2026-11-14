package inego.zungjunggame.render.ui

import inego.zungjunggame.ui.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared press → drag → park state machine behind the hand-end card, the
 * HINT button and the claim row.
 *
 * Slop is [ParkDrag.DEFAULT_SLOP_DP] design px, which is 8 px while `Fonts.uiScale`
 * is 1 (the unit-test default).
 */
class ParkDragTest {

    private val bounds = Rect(left = 10f, bottom = 10f, width = 980f, height = 780f)

    private fun parked(
        restLeft: Float = 100f,
        restBottom: Float = 100f,
        blockW: Float = 200f,
        blockH: Float = 100f,
    ): ParkDrag = ParkDrag().apply {
        setGeometry(restLeft, restBottom, blockW, blockH, bounds)
    }

    @Test
    fun withoutGeometryTheParkStillTracksTheFinger() {
        val park = ParkDrag()
        park.begin(100f, 100f)
        park.drag(160f, 100f)
        val release = park.release()
        assertTrue(release.moved)
        assertEquals(60f, park.parkX, 1e-3f)
    }

    @Test
    fun travelUnderTheSlopIsATap() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(105f, 104f)
        assertFalse(park.moved)
        val release = park.release()
        assertFalse(release.moved)
        assertEquals(0f, park.parkX, 1e-3f)
        assertEquals(0f, park.parkY, 1e-3f)
    }

    @Test
    fun firstMovePastTheSlopTakesTheWholeTravel() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(130f, 140f)
        assertTrue(park.moved)
        assertEquals(30f, park.parkX, 1e-3f)
        assertEquals(40f, park.parkY, 1e-3f)
    }

    @Test
    fun dragAfterTheSlopAccumulates() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(130f, 100f)
        park.drag(150f, 120f)
        assertEquals(50f, park.parkX, 1e-3f)
        assertEquals(20f, park.parkY, 1e-3f)
    }

    @Test
    fun releaseReportsThePressPointAndClearsThePress() {
        val park = parked()
        park.begin(120f, 130f)
        park.drag(200f, 130f)
        val release = park.release()
        assertTrue(release.moved)
        assertEquals(120f, release.pressX, 1e-3f)
        assertEquals(130f, release.pressY, 1e-3f)
        assertFalse(park.active)
        assertFalse(park.moved)
        assertEquals(80f, park.parkX, 1e-3f, "the park outlives the press")
    }

    @Test
    fun dragIsClampedSoTheBlockStaysInsideTheBounds() {
        val park = parked(restLeft = 100f, restBottom = 100f, blockW = 200f, blockH = 100f)
        park.begin(100f, 100f)
        park.drag(10_000f, 10_000f)
        // Right edge 990 − 200, top edge 790 − 100, minus the rest pose.
        assertEquals(690f, park.parkX, 1e-3f)
        assertEquals(590f, park.parkY, 1e-3f)
    }

    @Test
    fun aClampDoesNotStickWhenTheFingerTurnsAround() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(10_000f, 100f)
        val atEdge = park.parkX
        // The applied offset was stored back, so a small move back moves at once
        // instead of first walking off the unused travel.
        park.drag(10_000f - 30f, 100f)
        assertEquals(atEdge - 30f, park.parkX, 1e-3f)
    }

    @Test
    fun movesBlockFalseHoldsThePressWithoutParking() {
        val park = parked()
        // The hand-end card's fold chevron: the press belongs to the block, but
        // it must never move it and never read as a drag.
        park.begin(100f, 100f, movesBlock = false)
        park.drag(300f, 400f)
        assertTrue(park.active)
        assertFalse(park.moved)
        assertEquals(0f, park.parkX, 1e-3f)
        assertEquals(0f, park.parkY, 1e-3f)
        assertFalse(park.release().moved)
    }

    @Test
    fun cancelDropsThePressAndKeepsThePark() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(160f, 100f)
        park.cancel()
        assertFalse(park.active)
        assertFalse(park.moved)
        assertEquals(60f, park.parkX, 1e-3f)
        // A stale drag after the cancel must not move the block.
        park.drag(300f, 100f)
        assertEquals(60f, park.parkX, 1e-3f)
    }

    @Test
    fun beginReplacesAnUnfinishedPress() {
        val park = parked()
        park.begin(100f, 100f)
        park.drag(160f, 100f)
        park.begin(100f, 100f)
        assertFalse(park.moved)
        assertEquals(60f, park.parkX, 1e-3f, "the old park is not undone by a new press")
        assertFalse(park.release().moved)
    }

}
