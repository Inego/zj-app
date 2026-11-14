package inego.zungjunggame.render

import inego.zungjunggame.render.table.ConnectorCurveDrawer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectorCurveDrawerTest {

    @Test
    fun arcReturnsNullForDegenerateDistance() {
        val arc = ConnectorCurveDrawer.computeArc(
            x0 = 100f,
            y0 = 100f,
            x1 = 100.5f,
            y1 = 100.2f,
            centerRefX = 500f,
            centerRefY = 500f,
        )
        assertNull(arc)
    }

    @Test
    fun bezierEvaluationAtEndpointsAndMidpoint() {
        val bezier = ConnectorCurveDrawer.QuadraticBezier(
            x0 = 0f,
            y0 = 0f,
            cx = 50f,
            cy = 100f,
            x1 = 100f,
            y1 = 0f,
        )
        val (startPX, startPY) = bezier.evaluate(0f)
        assertEquals(0f, startPX)
        assertEquals(0f, startPY)

        val (endPX, endPY) = bezier.evaluate(1f)
        assertEquals(100f, endPX)
        assertEquals(0f, endPY)

        val (midPX, midPY) = bezier.evaluate(0.5f)
        assertEquals(50f, midPX)
        // 0.25 * 0 + 0.5 * 100 + 0.25 * 0 = 50
        assertEquals(50f, midPY)
    }

    @Test
    fun arcBendsInwardTowardTableCenter() {
        val tableCx = 500f
        val tableCy = 500f

        fun assertBendsTowardCenter(x0: Float, y0: Float, x1: Float, y1: Float, seatName: String) {
            val arc = ConnectorCurveDrawer.computeArc(
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                centerRefX = tableCx,
                centerRefY = tableCy,
            )
            assertNotNull(arc, "$seatName arc should not be null")
            val midX = (x0 + x1) * 0.5f
            val midY = (y0 + y1) * 0.5f
            val toCenterDx = tableCx - midX
            val toCenterDy = tableCy - midY
            val bendDx = arc.cx - midX
            val bendDy = arc.cy - midY
            val dot = bendDx * toCenterDx + bendDy * toCenterDy
            assertTrue(dot > 0f, "$seatName arc control point should deflect toward table center (dot=$dot)")
        }

        // Bottom seat: Chord from river (450, 400) to bottom hand (500, 100).
        assertBendsTowardCenter(450f, 400f, 500f, 100f, "Bottom")

        // Opposite (Top) seat: Chord from river (550, 600) to top hand (500, 900).
        assertBendsTowardCenter(550f, 600f, 500f, 900f, "Top")

        // Right seat: Chord from river (600, 520) to right hand (900, 500).
        assertBendsTowardCenter(600f, 520f, 900f, 500f, "Right")

        // Left seat: Chord from river (400, 480) to left hand (100, 500).
        assertBendsTowardCenter(400f, 480f, 100f, 500f, "Left")
    }

    @Test
    fun winOnDiscardStyleResolutionByPlayerInvolvement() {
        val playerSeat = 0

        // 1. Player wins on someone's discard -> Green (WIN_ON_DISCARD_PLAYER)
        assertEquals(
            ConnectorCurveDrawer.CurveStyle.forWinOnDiscard(
                winnerSeat = 0,
                winSource = 2,
                playerSeat = playerSeat,
            ),
            ConnectorCurveDrawer.CurveStyle.WIN_ON_DISCARD_PLAYER,
        )

        // 2. Someone wins on Player's discard -> Red (WIN_ON_DISCARD_AGAINST_PLAYER)
        assertEquals(
            ConnectorCurveDrawer.CurveStyle.forWinOnDiscard(
                winnerSeat = 1,
                winSource = 0,
                playerSeat = playerSeat,
            ),
            ConnectorCurveDrawer.CurveStyle.WIN_ON_DISCARD_AGAINST_PLAYER,
        )

        // 3. From someone to someone else (Player not involved) -> Orange (WIN_ON_DISCARD)
        assertEquals(
            ConnectorCurveDrawer.CurveStyle.forWinOnDiscard(
                winnerSeat = 2,
                winSource = 3,
                playerSeat = playerSeat,
            ),
            ConnectorCurveDrawer.CurveStyle.WIN_ON_DISCARD,
        )

        // Spectator or uninvolved seat (-1) -> Orange (WIN_ON_DISCARD)
        assertEquals(
            ConnectorCurveDrawer.CurveStyle.forWinOnDiscard(
                winnerSeat = 1,
                winSource = 2,
                playerSeat = -1,
            ),
            ConnectorCurveDrawer.CurveStyle.WIN_ON_DISCARD,
        )
    }
}
