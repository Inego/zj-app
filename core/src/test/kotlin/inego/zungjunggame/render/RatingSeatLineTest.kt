package inego.zungjunggame.render

import inego.zungjunggame.net.PlayerRatingUpdateWire
import inego.zungjunggame.render.online.RatingSeatLine
import inego.zungjunggame.render.seats.PlayerLabels
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RatingSeatLineTest {

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
        // Seat 0: Alice (You), cum = 100, old = 1500, new = 1516 (+16)
        // Seat 1: Bob (Right), cum = 250, old = 1500, new = 1530 (+30) -> 1st in match
        // Seat 2: Charlie (Opposite), cum = -40, old = 1500, new = 1475 (-25) -> 4th in match
        // Seat 3: Diana (Left), cum = 50, old = 1500, new = 1490 (-10) -> 3rd in match
        val updates = listOf(
            PlayerRatingUpdateWire(seat = 0, userId = "u0", oldRating = 1500.0, newRating = 1515.6, delta = 15.6),
            PlayerRatingUpdateWire(seat = 1, userId = "u1", oldRating = 1500.0, newRating = 1530.2, delta = 30.2),
            PlayerRatingUpdateWire(seat = 2, userId = "u2", oldRating = 1500.0, newRating = 1474.8, delta = -25.2),
            PlayerRatingUpdateWire(seat = 3, userId = "u3", oldRating = 1500.0, newRating = 1489.6, delta = -10.4),
        )
        val runningTotals = intArrayOf(100, 250, -40, 50)

        val result = RatingSeatLine.build(updates, humanSeat = 0, runningTotals = runningTotals)

        assertEquals(4, result.size)
        // 1st: Seat 1 (Right) with cum = 250
        assertEquals(1530, result[0].after)
        assertEquals(30, result[0].delta)
        assertEquals(1500, result[0].before)
        assertFalse(result[0].isHuman)
        assertEquals(1, result[0].relativeSeat)

        // 2nd: Seat 0 (You) with cum = 100
        assertEquals(1516, result[1].after)
        assertEquals(16, result[1].delta)
        assertEquals(1500, result[1].before)
        assertTrue(result[1].isHuman)
        assertEquals(0, result[1].relativeSeat)

        // 3rd: Seat 3 (Left) with cum = 50
        assertEquals(1490, result[2].after)
        assertEquals(-10, result[2].delta)
        assertEquals(1500, result[2].before)
        assertFalse(result[2].isHuman)
        assertEquals(3, result[2].relativeSeat)

        // 4th: Seat 2 (Opposite) with cum = -40
        assertEquals(1475, result[3].after)
        assertEquals(-25, result[3].delta)
        assertEquals(1500, result[3].before)
        assertFalse(result[3].isHuman)
        assertEquals(2, result[3].relativeSeat)
    }

    @Test
    fun ordersByNewRatingDecreasingWhenScoresAbsent() {
        val updates = listOf(
            PlayerRatingUpdateWire(seat = 0, userId = "u0", oldRating = 1400.0, newRating = 1420.0, delta = 20.0),
            PlayerRatingUpdateWire(seat = 1, userId = "u1", oldRating = 1600.0, newRating = 1590.0, delta = -10.0),
        )

        val result = RatingSeatLine.build(updates, humanSeat = 0, runningTotals = null as IntArray?)

        assertEquals(2, result.size)
        // Seat 1 has newRating 1590 > Seat 0's 1420
        assertEquals(1590, result[0].after)
        assertEquals(1, result[0].relativeSeat)
        assertEquals(1420, result[1].after)
        assertEquals(0, result[1].relativeSeat)
    }

    @Test
    fun breaksTiesCounterclockwiseStartingFromPlayer() {
        // All seats have cumulative score 0
        // humanSeat = 0 -> order must be: 0 (You), 1 (Right), 2 (Opposite), 3 (Left)
        val updates = (0..3).map { seat ->
            PlayerRatingUpdateWire(seat = seat, userId = "u$seat", oldRating = 1500.0, newRating = 1500.0, delta = 0.0)
        }
        val runningTotals = intArrayOf(0, 0, 0, 0)

        val result = RatingSeatLine.build(updates, humanSeat = 0, runningTotals = runningTotals)

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
        val updates = (0..3).map { seat ->
            PlayerRatingUpdateWire(seat = seat, userId = "u$seat", oldRating = 1500.0, newRating = 1500.0, delta = 0.0)
        }
        val runningTotals = intArrayOf(100, 100, 100, 100)

        val result = RatingSeatLine.build(updates, humanSeat = 2, runningTotals = runningTotals)

        assertEquals(listOf(0, 1, 2, 3), result.map { it.relativeSeat })
        assertTrue(result[0].isHuman)
    }

    @Test
    fun breaksPartialTiesCorrectly() {
        // humanSeat = 1 (Bob)
        // Seat 0 (Left of Bob, rel 3): cum = 100
        // Seat 1 (Bob / You, rel 0): cum = 100
        // Seat 2 (Right of Bob, rel 1): cum = 50
        // Seat 3 (Opposite Bob, rel 2): cum = 100
        val updates = (0..3).map { seat ->
            PlayerRatingUpdateWire(seat = seat, userId = "u$seat", oldRating = 1500.0, newRating = 1500.0, delta = 0.0)
        }
        val runningTotals = intArrayOf(100, 100, 50, 100)

        val result = RatingSeatLine.build(updates, humanSeat = 1, runningTotals = runningTotals)

        assertEquals(4, result.size)
        assertEquals(0, result[0].relativeSeat)
        assertTrue(result[0].isHuman)
        assertEquals(2, result[1].relativeSeat)
        assertEquals(3, result[2].relativeSeat)
        assertEquals(1, result[3].relativeSeat)
    }

    @Test
    fun createsPendingLineWhenLocalPlayerMissingFromUpdates() {
        val updates = listOf(
            PlayerRatingUpdateWire(seat = 1, userId = "u1", oldRating = 1500.0, newRating = 1510.0, delta = 10.0),
        )
        val runningTotals = intArrayOf(50, 100)

        val result = RatingSeatLine.build(updates, humanSeat = 0, runningTotals = runningTotals)

        assertEquals(2, result.size)
        // Seat 1 is cum 100, Seat 0 is cum 50 (pending)
        assertEquals(1, result[0].relativeSeat)
        assertFalse(result[0].pending)

        assertEquals(0, result[1].relativeSeat)
        assertTrue(result[1].isHuman)
        assertTrue(result[1].pending)
    }

    @Test
    fun filtersOutBotsFromRatingUpdates() {
        PlayerLabels.setPlayerNames(
            names = arrayOf("Alice", "Bob", "Charlie", "Diana"),
            isBot = booleanArrayOf(false, false, true, false),
        )

        val updates = listOf(
            PlayerRatingUpdateWire(seat = 0, userId = "u0", oldRating = 1500.0, newRating = 1510.0, delta = 10.0),
            PlayerRatingUpdateWire(seat = 2, userId = "bot", oldRating = 1500.0, newRating = 1490.0, delta = -10.0),
        )

        val result = RatingSeatLine.build(updates, humanSeat = 0, runningTotals = null as IntArray?)

        assertEquals(1, result.size)
        assertTrue(result[0].isHuman)
        assertEquals(0, result[0].relativeSeat)
    }

    /**
     * The row shows the delta the server sent, not the difference between the two
     * rounded ratings.
     *
     * Production, 2026-09-17 (`game_id=bl8ry55g8s`): the match awarded **+8.25**
     * (`1188.4 -> 1196.65`), and the card showed **+9** — each endpoint had been
     * rounded for display and then subtracted, and the two roundings happened to
     * run opposite ways. Before and after stay independently rounded display
     * values, so the row can read `1188 +8 1197`; that is the real arithmetic of a
     * fractional rating, and the alternative was reporting a delta nobody was paid.
     */
    @Test
    fun showsTheServersDeltaEvenWhenItDisagreesWithTheRoundedEndpoints() {
        val updates = listOf(
            PlayerRatingUpdateWire(
                seat = 0,
                userId = "u0",
                oldRating = 1188.4,
                newRating = 1196.65,
                delta = 8.25,
            ),
        )

        val line = RatingSeatLine.build(updates, humanSeat = 0).single()

        assertEquals(1188, line.before)
        assertEquals(8, line.delta, "the server granted +8.25, so the card says +8 — not the +9 the old subtraction produced")
        assertEquals(1197, line.after)
    }
}
