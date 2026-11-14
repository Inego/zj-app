package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZjSettlementTest {
    @Test
    fun selfDrawEveryonePaysFullValue() {
        val d = ZjSettlement.computeDeltas(handValue = 40, winnerSeat = 0, discarderSeat = -1)
        assertEquals(listOf(120, -40, -40, -40), d.toList())
        assertEquals(0, d.sum())
    }

    @Test
    fun winOnDiscardAtOrUnderCapEveryonePaysFull() {
        val d = ZjSettlement.computeDeltas(handValue = 25, winnerSeat = 1, discarderSeat = 0)
        assertEquals(listOf(-25, 75, -25, -25), d.toList())
        assertEquals(0, d.sum())
    }

    @Test
    fun winOnDiscardOverCapNonDiscardersPay25DiscarderPaysRest() {
        // HV=40: non-discarders pay 25; discarder pays 40 + 2*15 = 70; winner +120.
        val d = ZjSettlement.computeDeltas(handValue = 40, winnerSeat = 2, discarderSeat = 0)
        assertEquals(120, d[2])
        assertEquals(-70, d[0], "discarder pays full + excess for the other two")
        assertEquals(-25, d[1])
        assertEquals(-25, d[3])
        assertEquals(0, d.sum())
        assertEquals(3 * 40, d[2])
    }

    @Test
    fun exhaustiveDrawIsZero() {
        val d = ZjSettlement.computeDeltas(handValue = 0, winnerSeat = -1, discarderSeat = -1)
        assertEquals(listOf(0, 0, 0, 0), d.toList())
    }

    @Test
    fun sessionTableAccumulatesRunningTotals() {
        val table = SessionScoreTable()
        val state = GameState()
        state.handScore = 10
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        assertTrue(state.isTerminal())
        table.recordTerminal(state)
        assertEquals(1, table.handCount)
        assertEquals(listOf(30, -10, -10, -10), table.rows()[0].deltas.toList())
        assertEquals(listOf(30, -10, -10, -10), table.runningTotals().toList())

        state.handScore = 5
        state.currentPlayer = 1
        table.recordTerminal(state)
        // After hand 2: seat0 30-5=25, seat1 -10+15=5, seat2/3 -10-5=-15.
        assertEquals(listOf(25, 5, -15, -15), table.runningTotals().toList())
    }
}
