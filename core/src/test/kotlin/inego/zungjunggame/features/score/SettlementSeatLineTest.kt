package inego.zungjunggame.features.score

import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.render.seats.PlayerLabels
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

class SettlementSeatLineTest {

    @BeforeTest
    fun setUp() {
        PlayerLabels.clearCustomNames()
    }

    @AfterTest
    fun tearDown() {
        PlayerLabels.clearCustomNames()
    }

    @Test
    fun ordersByCumulativeScoreDecreasingWhenScoresDistinct() {
        // humanSeat = 0 (You)
        // Seat 0: delta = 10, runningTotal = 100
        // Seat 1: delta = 20, runningTotal = 250 (Leader)
        // Seat 2: delta = -10, runningTotal = -40
        // Seat 3: delta = 0, runningTotal = 50
        val deltas = intArrayOf(10, 20, -10, 0)
        val runningTotals = intArrayOf(100, 250, -40, 50)

        val result = SettlementSeatLine.build(deltas, runningTotals, humanSeat = 0)

        assertEquals(4, result.size)
        // 1st: Seat 1 (Right) with after = 250
        assertEquals(250, result[0].after)
        assertEquals(20, result[0].delta)
        assertEquals(230, result[0].before)
        assertFalse(result[0].isHuman)
        assertEquals(1, result[0].relativeSeat)

        // 2nd: Seat 0 (You) with after = 100
        assertEquals(100, result[1].after)
        assertEquals(10, result[1].delta)
        assertEquals(90, result[1].before)
        assertTrue(result[1].isHuman)
        assertEquals(0, result[1].relativeSeat)

        // 3rd: Seat 3 (Left) with after = 50
        assertEquals(50, result[2].after)
        assertEquals(0, result[2].delta)
        assertEquals(50, result[2].before)
        assertFalse(result[2].isHuman)
        assertEquals(3, result[2].relativeSeat)

        // 4th: Seat 2 (Opposite) with after = -40
        assertEquals(-40, result[3].after)
        assertEquals(-10, result[3].delta)
        assertEquals(-30, result[3].before)
        assertFalse(result[3].isHuman)
        assertEquals(2, result[3].relativeSeat)
    }

    @Test
    fun breaksTiesCounterclockwiseStartingFromPlayer() {
        // All seats have cumulative score 0
        // humanSeat = 0 -> order must be: 0 (You), 1 (Right), 2 (Opposite), 3 (Left)
        val deltas = intArrayOf(0, 0, 0, 0)
        val runningTotals = intArrayOf(0, 0, 0, 0)

        val result = SettlementSeatLine.build(deltas, runningTotals, humanSeat = 0)

        assertEquals(listOf(0, 1, 2, 3), result.map { it.relativeSeat })
        assertTrue(result[0].isHuman)
    }

    @Test
    fun breaksTiesCounterclockwiseWhenPlayerIsNotSeatZero() {
        // All seats have score 100
        // humanSeat = 2
        // relativeSeat 0 = seat 2 (You)
        // relativeSeat 1 = seat 3 (Right)
        // relativeSeat 2 = seat 0 (Opposite)
        // relativeSeat 3 = seat 1 (Left)
        val deltas = intArrayOf(0, 0, 0, 0)
        val runningTotals = intArrayOf(100, 100, 100, 100)

        val result = SettlementSeatLine.build(deltas, runningTotals, humanSeat = 2)

        assertEquals(listOf(0, 1, 2, 3), result.map { it.relativeSeat })
        assertTrue(result[0].isHuman)
        assertEquals(100, result[0].after)
    }

    @Test
    fun breaksPartialTiesCorrectly() {
        // humanSeat = 1 (Bob)
        // Seats:
        // Seat 0 (Left of Bob, rel 3): cum = 100
        // Seat 1 (Bob / You, rel 0): cum = 100
        // Seat 2 (Right of Bob, rel 1): cum = 50
        // Seat 3 (Opposite Bob, rel 2): cum = 100
        //
        // Tie between seats 0, 1, 3 (all cum = 100):
        // Relative seats: seat 1 is rel 0, seat 3 is rel 2, seat 0 is rel 3
        // So tie break gives:
        // 1st: Seat 1 (cum 100, rel 0)
        // 2nd: Seat 3 (cum 100, rel 2)
        // 3rd: Seat 0 (cum 100, rel 3)
        // 4th: Seat 2 (cum 50, rel 1)
        val deltas = intArrayOf(0, 0, 0, 0)
        val runningTotals = intArrayOf(100, 100, 50, 100)

        val result = SettlementSeatLine.build(deltas, runningTotals, humanSeat = 1)

        assertEquals(4, result.size)
        assertEquals(0, result[0].relativeSeat)
        assertEquals(100, result[0].after)
        assertTrue(result[0].isHuman)

        assertEquals(2, result[1].relativeSeat)
        assertEquals(100, result[1].after)

        assertEquals(3, result[2].relativeSeat)
        assertEquals(100, result[2].after)

        assertEquals(1, result[3].relativeSeat)
        assertEquals(50, result[3].after)
    }

    @Test
    fun setsBotFlagCorrectly() {
        PlayerLabels.setPlayerNames(
            names = arrayOf("Alice", "Bob", "Charlie", "Diana"),
            isBot = booleanArrayOf(false, false, true, false),
        )

        val deltas = intArrayOf(0, 0, 0, 0)
        val runningTotals = intArrayOf(10, 20, 30, 40)

        val result = SettlementSeatLine.build(deltas, runningTotals, humanSeat = 0)

        val botLine = result.first { it.isBot }
        assertEquals(30, botLine.after)

        val alice = result.first { it.name == "Alice" }
        assertFalse(alice.isBot)
        assertTrue(alice.isHuman)
    }

    @Test
    fun handlesHandScoreRowAndNullSafely() {
        val row = HandScoreRow(
            handNumber = 1,
            handValue = 30,
            winnerSeat = 0,
            discarderSeat = 1,
            deltas = intArrayOf(30, -10, -10, -10),
            runningTotals = intArrayOf(30, -10, -10, -10),
        )

        val result = SettlementSeatLine.build(row, humanSeat = 0)
        assertEquals(4, result.size)
        assertEquals(30, result[0].after)
        assertTrue(result[0].isHuman)

        val empty = SettlementSeatLine.build(row = null, humanSeat = 0)
        assertTrue(empty.isEmpty())
    }

}
