package inego.zungjunggame.net

import inego.zungjunggame.i18n.L10n
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchHistoryFormatTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        val cwd = File(".").canonicalFile
        val assets = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
        ).first { File(it, "i18n").isDirectory }
        L10n.bootstrap(Locale.US, com.badlogic.gdx.files.FileHandle(File(assets, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
    }

    @Test
    fun formatsPlaceDeltaAndPendingRating() {
        assertEquals(L10n.get("history.place_1"), MatchHistoryFormat.placeLabel(1))
        assertEquals(L10n.get("history.place_2"), MatchHistoryFormat.placeLabel(2))
        assertEquals(L10n.format("history.place_n", 5), MatchHistoryFormat.placeLabel(5))
        assertEquals("+12", MatchHistoryFormat.deltaLabel(12.4))
        assertEquals("\u22128", MatchHistoryFormat.deltaLabel(-7.8))
        assertEquals("0", MatchHistoryFormat.deltaLabel(0.0))
        assertEquals(L10n.get("history.rating_pending"), MatchHistoryFormat.deltaLabel(null))
        assertEquals("1482", MatchHistoryFormat.ratingLabel(1482.4))
    }

    @Test
    fun formatsDurationFromTheTwoGameFinishedStamps() {
        assertEquals(
            L10n.format("hand.duration", "05:23"),
            MatchHistoryFormat.durationLabel("2024-01-01T00:00:00Z", "2024-01-01T00:05:23Z"),
        )
        // A span over an hour still reads as minutes:seconds.
        assertEquals(
            L10n.format("hand.duration", "65:00"),
            MatchHistoryFormat.durationLabel("2024-01-01T00:00:00Z", "2024-01-01T01:05:00Z"),
        )
    }

    @Test
    fun durationLabelIsAbsentWhenAStampIsMissingOrUnusable() {
        assertNull(MatchHistoryFormat.durationLabel(null, "2024-01-01T00:05:23Z"))
        assertNull(MatchHistoryFormat.durationLabel("2024-01-01T00:00:00Z", null))
        assertNull(MatchHistoryFormat.durationLabel("nope", "2024-01-01T00:05:23Z"))
        // Reversed stamps are not a duration; refusing beats showing a negative clock.
        assertNull(MatchHistoryFormat.durationLabel("2024-01-01T00:05:23Z", "2024-01-01T00:00:00Z"))
    }

    @Test
    fun clockLabelPadsMinutesAndSeconds() {
        assertEquals("00:00", MatchHistoryFormat.clockLabel(0))
        assertEquals("00:09", MatchHistoryFormat.clockLabel(9_000))
        assertEquals("01:01", MatchHistoryFormat.clockLabel(61_000))
    }

    @Test
    fun labelsTheServersDeltaRatherThanTheDifferenceOfTwoRoundedRatings() {
        // Production, 2026-09-17: the match awarded +8.25 (1188.4 -> 1196.65). Every
        // row showed +9, because each path rounded both endpoints first and then
        // subtracted them. The delta is the fact the match owns; the endpoints are
        // display values and are never consulted.
        assertEquals("+8", MatchHistoryFormat.deltaLabel(8.25))
    }

    @Test
    fun placementCountsStrictlyHigherScores() {
        assertEquals(1, MatchHistoryFormat.placement(intArrayOf(100, -20, -40, -40), 0))
        assertEquals(2, MatchHistoryFormat.placement(intArrayOf(-20, 100, -40, -40), 0))
        assertEquals(1, MatchHistoryFormat.placement(intArrayOf(50, 50, 10, 10), 0))
        assertEquals(1, MatchHistoryFormat.placement(intArrayOf(50, 50, 10, 10), 1))
        assertEquals(3, MatchHistoryFormat.placement(intArrayOf(50, 50, 10, 10), 2))
        assertEquals(0, MatchHistoryFormat.placement(intArrayOf(1, 2, 3, 4), 9))
    }

    @Test
    fun placementTextIsTheBarePlaceWhenNobodySharesIt() {
        val totals = intArrayOf(100, -20, -40, -60)
        assertEquals(L10n.get("history.place_1"), MatchHistoryFormat.placementText(totals, 0))
        assertEquals(L10n.get("history.place_2"), MatchHistoryFormat.placementText(totals, 1))
        assertEquals(L10n.get("history.place_4"), MatchHistoryFormat.placementText(totals, 3))
    }

    @Test
    fun placementTextNamesHowManySeatsShareThePlace() {
        // Two seats share the best place: they say so, rather than leaning on the
        // reader to notice that nobody holds second.
        val pair = intArrayOf(50, 50, 10, -10)
        assertEquals(
            L10n.format("history.place_shared", L10n.get("history.place_1"), 2),
            MatchHistoryFormat.placementText(pair, 0),
        )
        assertEquals(MatchHistoryFormat.placementText(pair, 0), MatchHistoryFormat.placementText(pair, 1))
        assertEquals(L10n.get("history.place_3"), MatchHistoryFormat.placementText(pair, 2))

        // Three ways: the three leaders are 1st, the last seat is 4th.
        val three = intArrayOf(0, 0, 0, -30)
        assertEquals(
            L10n.format("history.place_shared", L10n.get("history.place_1"), 3),
            MatchHistoryFormat.placementText(three, 2),
        )
        assertEquals(L10n.get("history.place_4"), MatchHistoryFormat.placementText(three, 3))
    }

    @Test
    fun placementTextForAnAllSquareGameIsSharedFirstPlace() {
        val allZero = IntArray(4)
        for (seat in 0 until 4) {
            assertEquals(
                L10n.format("history.place_shared", L10n.get("history.place_1"), 4),
                MatchHistoryFormat.placementText(allZero, seat),
            )
        }
    }

    @Test
    fun placementTextIsEmptyForASeatOutsideTheTotals() {
        assertEquals("", MatchHistoryFormat.placementText(intArrayOf(1, 2, 3, 4), 9))
        assertEquals("", MatchHistoryFormat.placementText(IntArray(0), 0))
    }
}
