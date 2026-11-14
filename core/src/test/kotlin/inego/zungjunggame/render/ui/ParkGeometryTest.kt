package inego.zungjunggame.render.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one clamp every parked block shares: the layout that places a block and
 * the drag that moves it must agree on where it can go.
 *
 * Bounds are hand-written so the arithmetic is readable: 1000×800 box inset by
 * 10 on each side.
 */
class ParkGeometryTest {

    private val bounds = OverlayViewport.Rect(left = 10f, bottom = 10f, width = 980f, height = 780f)

    @Test
    fun noParkLeavesTheRestPoseAlone() {
        val placed = ParkGeometry.place(
            restLeft = 100f,
            restBottom = 200f,
            blockW = 300f,
            blockH = 100f,
            parkX = 0f,
            parkY = 0f,
            bounds = bounds,
        )
        assertEquals(100f, placed.left, 1e-3f)
        assertEquals(200f, placed.bottom, 1e-3f)
        assertEquals(0f, placed.parkX, 1e-3f)
        assertEquals(0f, placed.parkY, 1e-3f)
    }

    @Test
    fun parkMovesTheBlockAndReportsTheAppliedOffset() {
        val placed = ParkGeometry.place(
            restLeft = 100f,
            restBottom = 200f,
            blockW = 300f,
            blockH = 100f,
            parkX = 40f,
            parkY = -60f,
            bounds = bounds,
        )
        assertEquals(140f, placed.left, 1e-3f)
        assertEquals(140f, placed.bottom, 1e-3f)
        assertEquals(40f, placed.parkX, 1e-3f)
        assertEquals(-60f, placed.parkY, 1e-3f)
    }

    @Test
    fun clampStopsTheBlockAtTheEdgeAndReportsTheSmallerOffset() {
        val placed = ParkGeometry.place(
            restLeft = 100f,
            restBottom = 200f,
            blockW = 300f,
            blockH = 100f,
            parkX = 10_000f,
            parkY = 10_000f,
            bounds = bounds,
        )
        // Right edge 990 − 300, top edge 790 − 100: the block sits flush.
        assertEquals(690f, placed.left, 1e-3f)
        assertEquals(690f, placed.bottom, 1e-3f)
        assertEquals(
            590f,
            placed.parkX,
            1e-3f,
            "the applied offset is what the park keeps, so reversing the drag moves at once",
        )
        assertEquals(490f, placed.parkY, 1e-3f)
    }

    @Test
    fun clampStopsAtTheNearEdgeToo() {
        val placed = ParkGeometry.place(
            restLeft = 100f,
            restBottom = 200f,
            blockW = 300f,
            blockH = 100f,
            parkX = -10_000f,
            parkY = -10_000f,
            bounds = bounds,
        )
        assertEquals(10f, placed.left, 1e-3f)
        assertEquals(10f, placed.bottom, 1e-3f)
        assertEquals(-90f, placed.parkX, 1e-3f)
        assertEquals(-190f, placed.parkY, 1e-3f)
    }

    @Test
    fun blockWiderThanTheScreenSlidesInsteadOfPinningOneEdge() {
        // A claim row with several chow previews can be wider than a phone.
        val draggedRight = ParkGeometry.place(
            restLeft = 0f,
            restBottom = 0f,
            blockW = 1400f,
            blockH = 100f,
            parkX = 10_000f,
            parkY = 0f,
            bounds = bounds,
        )
        assertEquals(
            10f,
            draggedRight.left,
            1e-3f,
            "dragged right, the row's left end comes fully into view",
        )
        assertEquals(10f, draggedRight.parkX, 1e-3f)

        val draggedLeft = ParkGeometry.place(
            restLeft = 0f,
            restBottom = 0f,
            blockW = 1400f,
            blockH = 100f,
            parkX = -10_000f,
            parkY = 0f,
            bounds = bounds,
        )
        assertEquals(
            -410f,
            draggedLeft.left,
            1e-3f,
            "and dragged left, its right end — never pinned with one end unreachable",
        )
        assertEquals(990f, draggedLeft.left + 1400f, 1e-3f)
        assertEquals(-410f, draggedLeft.parkX, 1e-3f)
    }
}
