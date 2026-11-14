package inego.zungjunggame.render.seatwinds

import inego.zungjunggame.engine.Tiles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool tests for the seat-winds diagram clock and layout.
 * Does not lock lesson body keys or campaign order.
 */
class SeatWindsTest {

    @Test
    fun unknownBodyKeyIsNull() {
        assertNull(SeatWindsBeats.of("not.a.registered.beat"))
    }

    @Test
    fun pauseHoldsHomeThenOneTurnLandsAtRestOnHand2() {
        val home = SeatWindsMotion.phase(0f)
        assertTrue(home.atRest)
        assertEquals(0, home.steps)
        assertEquals(1, home.handNumber)
        assertEquals(0f, home.rotationTurns, 1e-5f)

        val midPause = SeatWindsMotion.phase(SeatWindsMotion.PAUSE_SEC * 0.5f)
        assertTrue(midPause.atRest)
        assertEquals(0, midPause.steps)

        val midRotate = SeatWindsMotion.phase(
            SeatWindsMotion.PAUSE_SEC + SeatWindsMotion.ROTATE_SEC * 0.5f,
        )
        assertFalse(midRotate.atRest)
        assertEquals(0, midRotate.steps)
        assertEquals(0.5f, midRotate.rotateT, 0.02f)

        val after = SeatWindsMotion.phase(
            SeatWindsMotion.PAUSE_SEC + SeatWindsMotion.ROTATE_SEC,
        )
        assertTrue(after.atRest)
        assertEquals(1, after.steps)
        assertEquals(2, after.handNumber)
        assertEquals(1f, after.rotationTurns, 1e-4f)
    }

    @Test
    fun fourHandsWrapToEastHome() {
        val looped = SeatWindsMotion.phase(SeatWindsMotion.loopSec)
        assertTrue(looped.atRest)
        assertEquals(0, looped.steps)
        assertEquals(1, looped.handNumber)
        assertEquals(0f, looped.rotationTurns, 1e-4f)
    }

    @Test
    fun eastStartsBottomAndMovesRightThenOpposite() {
        val m = probeMetrics()
        val home = SeatWindsLayout.plan(0f, 0f, 400f, 400f, 0f, m)
        val east0 = home.winds.first { it.tileType == Tiles.EAST }
        assertTrue(abs(east0.cx - home.tableCx) < 1.5f)
        assertTrue(east0.cy < home.tableCy)

        val one = SeatWindsLayout.plan(0f, 0f, 400f, 400f, 1f, m)
        val east1 = one.winds.first { it.tileType == Tiles.EAST }
        assertTrue(east1.cx > one.tableCx)
        assertTrue(abs(east1.cy - one.tableCy) < 1.5f)

        val two = SeatWindsLayout.plan(0f, 0f, 400f, 400f, 2f, m)
        val east2 = two.winds.first { it.tileType == Tiles.EAST }
        assertTrue(abs(east2.cx - two.tableCx) < 1.5f)
        assertTrue(east2.cy > two.tableCy)
    }

    @Test
    fun windsStayOnTableAndSouthStartsAtRight() {
        val m = probeMetrics()
        val plan = SeatWindsLayout.plan(10f, 20f, 360f, 320f, 0f, m)
        assertTrue(plan.squareSide > 8f)
        val left = plan.squareLeft
        val bottom = plan.squareBottom
        val right = plan.squareLeft + plan.squareSide
        val top = plan.squareBottom + plan.squareSide
        for ((_, _, cx, cy) in plan.winds) {
            assertTrue(cx >= left - 0.5f)
            assertTrue(cx <= right + 0.5f)
            assertTrue(cy >= bottom - 0.5f)
            assertTrue(cy <= top + 0.5f)
        }
        val south = plan.winds.first { it.tileType == Tiles.SOUTH }
        assertTrue(south.cx > plan.tableCx)
        assertTrue(abs(south.cy - plan.tableCy) < 1.5f)
        val areaLeft = 10f
        val areaBottom = 20f
        val areaRight = 370f
        val areaTop = 340f
        for ((_, cx, cy) in plan.seats) {
            assertTrue(cx >= areaLeft - 0.5f)
            assertTrue(cx <= areaRight + 0.5f)
            assertTrue(cy >= areaBottom - 0.5f)
            assertTrue(cy <= areaTop + 0.5f)
        }
    }

    private fun probeMetrics() = SeatWindsLayout.Metrics(
        pad = 8f,
        seatGap = 10f,
        seatLabelW = 40f,
        seatLabelH = 12f,
        windInset = 24f,
    )
}
