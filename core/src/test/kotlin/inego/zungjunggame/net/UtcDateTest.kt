package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtcDateTest {
    @Test
    fun unixEpochIs1970() {
        assertEquals("1970-01-01", UtcDate.ymdFromUnixDays(0))
        assertEquals("1970-01-01", UtcDate.ymdFromEpochMs(0))
        assertEquals("1970-01-02", UtcDate.ymdFromEpochMs(UtcDate.MS_PER_DAY))
    }

    @Test
    fun knownUtcMidnight() {
        // 2024-01-01T00:00:00Z
        assertEquals("2024-01-01", UtcDate.ymdFromEpochMs(1_704_067_200_000L))
        // 2024-01-02T00:00:00Z
        assertEquals("2024-01-02", UtcDate.ymdFromEpochMs(1_704_153_600_000L))
    }

    @Test
    fun relativeDayTodayYesterdayAndDate() {
        val now = 1_704_067_200_000L + 12 * 3_600_000L
        val today = UtcDate.relativeDay("2024-01-01T08:00:00Z", now) { null }
        assertEquals(UtcDate.RelativeKind.Today, today.kind)
        val yesterday = UtcDate.relativeDay("2023-12-31T23:00:00Z", now) { null }
        assertEquals(UtcDate.RelativeKind.Yesterday, yesterday.kind)
        val older = UtcDate.relativeDay("2023-12-01T00:00:00Z", now) { null }
        assertEquals(UtcDate.RelativeKind.Date, older.kind)
        assertEquals("2023-12-01", older.ymd)
    }

    @Test
    fun parsesRfc3339UtcInstants() {
        // 2024-01-01T00:00:00Z
        assertEquals(1_704_067_200_000L, UtcDate.epochMillisFromIso("2024-01-01T00:00:00Z"))
        // Fractional seconds are truncated to whole milliseconds.
        assertEquals(
            1_704_067_200_123L,
            UtcDate.epochMillisFromIso("2024-01-01T00:00:00.123456Z"),
        )
        // Time of day adds on top of the civil-day epoch.
        assertEquals(
            1_704_067_200_000L + 3_600_000L + 120_000L + 45_000L,
            UtcDate.epochMillisFromIso("2024-01-01T01:02:45Z"),
        )
    }

    @Test
    fun clockIsZeroPaddedUtcWithinTheDay() {
        assertEquals("00:00", UtcDate.clockFromEpochMs(0))
        assertEquals("19:27", UtcDate.clockFromEpochMs(1_704_067_200_000L + 70_020_000L))
        // The last millisecond of a day is the end of that day's clock, and the
        // next one starts it over — no day offset leaks into the label.
        assertEquals("23:59", UtcDate.clockFromEpochMs(UtcDate.MS_PER_DAY - 1L))
        assertEquals("00:00", UtcDate.clockFromEpochMs(UtcDate.MS_PER_DAY))
        // Before the epoch the within-day offset still lands in 0..86 399 999, so
        // the floor stays inside the day instead of going negative.
        assertEquals("23:59", UtcDate.clockFromEpochMs(-1L))
        assertEquals("00:00", UtcDate.clockFromEpochMs(-UtcDate.MS_PER_DAY))
    }

    @Test
    fun refusesStampsThatAreNotUtcInstants() {
        // An offset is not something the server produces; guessing would misread it.
        assertNull(UtcDate.epochMillisFromIso("2024-01-01T00:00:00+02:00"))
        assertNull(UtcDate.epochMillisFromIso("2024-01-01"))
        assertNull(UtcDate.epochMillisFromIso("not a date"))
    }
}
