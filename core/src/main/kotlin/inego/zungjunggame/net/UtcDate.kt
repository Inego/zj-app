package inego.zungjunggame.net

/**
 * UTC calendar dates without `java.time` (TeaVM-safe).
 *
 * Civil conversion follows Howard Hinnant's `civil_from_days`.
 */
object UtcDate {
    const val MS_PER_DAY = 86_400_000L

    enum class RelativeKind { Today, Yesterday, Date }

    data class RelativeDay(val kind: RelativeKind, val ymd: String)

    fun ymdFromEpochMs(epochMs: Long): String = ymdFromUnixDays(floorDiv(epochMs, MS_PER_DAY))

    /**
     * Weekday of [epochMs]'s UTC day, ISO-8601: 0 = Monday … 6 = Sunday.
     *
     * Unix day 0 was a Thursday, so it lands on index 3. The weekday belongs to
     * the same civil day [ymdFromEpochMs] names, which is what lets a caller add
     * a zone offset to both and get that zone's date and weekday.
     */
    fun weekdayFromEpochMs(epochMs: Long): Int {
        val weekday = (floorDiv(epochMs, MS_PER_DAY) + 3L) % 7L
        return (if (weekday < 0L) weekday + 7L else weekday).toInt()
    }

    fun ymdFromUnixDays(unixDays: Long): String {
        val z = unixDays + 719468L
        val era = floorDiv(z, 146097L)
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        var y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val d = doy - (153L * mp + 2L) / 5L + 1L
        val m = mp + if (mp < 10L) 3L else -9L
        y += if (m <= 2L) 1L else 0L
        return "%04d-%02d-%02d".format(y.toInt(), m.toInt(), d.toInt())
    }

    fun parseIsoDate(iso: String): String? {
        if (iso.length < 10) return null
        val ymd = iso.substring(0, 10)
        return if (ymd[4] == '-' && ymd[7] == '-') ymd else null
    }

    /**
     * Epoch milliseconds for an RFC3339 UTC instant (`2026-09-28T19:27:20.123456Z`),
     * or null when [iso] is not one.
     *
     * Fractional seconds are truncated to milliseconds. Only a trailing `Z` is
     * accepted: every server stamp is UTC, so a numeric offset is a shape this
     * never produced and would otherwise be silently misread as local time.
     */
    fun epochMillisFromIso(iso: String): Long? {
        if (iso.length < 20) return null
        if (iso[4] != '-' || iso[7] != '-' || (iso[10] != 'T' && iso[10] != ' ')) return null
        if (iso[13] != ':' || iso[16] != ':') return null
        val year = digits(iso, 0, 4) ?: return null
        val month = digits(iso, 5) ?: return null
        val day = digits(iso, 8) ?: return null
        val hour = digits(iso, 11) ?: return null
        val minute = digits(iso, 14) ?: return null
        val second = digits(iso, 17) ?: return null
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        var millis = 0L
        var end = 19
        if (iso[end] == '.') {
            var frac = 0L
            var count = 0
            var i = end + 1
            while (i < iso.length && iso[i] in '0'..'9') {
                if (count < 3) {
                    frac = frac * 10 + (iso[i] - '0')
                    count++
                }
                i++
            }
            while (count < 3) {
                frac *= 10
                count++
            }
            millis = frac
            end = i
        }
        if (end != iso.length - 1 || iso[end] != 'Z') return null
        val days = daysFromCivil(year, month, day)
        return days * MS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
    }

    /** Two decimal digits at [start], or null when either is not a digit. */
    private fun digits(iso: String, start: Int): Int? {
        val a = iso[start]
        val b = iso[start + 1]
        if (a !in '0'..'9' || b !in '0'..'9') return null
        return (a - '0') * 10 + (b - '0')
    }

    /** Four decimal digits at [start], or null when any is not a digit. */
    private fun digits(iso: String, start: Int, count: Int): Int? {
        var value = 0
        for (i in start until start + count) {
            val c = iso[i]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Hinnant's `days_from_civil`). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val m = month.toLong()
        val d = day.toLong()
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L
        val doy = (153L * (if (m > 2) m - 3 else m + 9) + 2L) / 5L + d - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146097L + doe - 719468L
    }

    /**
     * `HH:mm` for an epoch-milliseconds instant, in UTC.
     *
     * The time of day is paired with a day label (see [relativeDay]) rather than a
     * full stamp, so the clock only has to order notices *within* a day. It is
     * UTC because every stamp this client receives is UTC and nothing on the
     * client knows the player's zone.
     */
    fun clockFromEpochMs(epochMs: Long): String {
        val withinDay = epochMs - floorDiv(epochMs, MS_PER_DAY) * MS_PER_DAY
        val minutes = withinDay / 60_000L
        return "%02d:%02d".format(minutes / 60L, minutes % 60L)
    }

    /**
     * `HH:mm:ss` for an epoch-milliseconds instant, in UTC.
     *
     * The same clock as [clockFromEpochMs] for a span shorter than a minute: at
     * that resolution the minute alone can name both of its ends the same.
     */
    fun clockWithSecondsFromEpochMs(epochMs: Long): String {
        val withinDay = epochMs - floorDiv(epochMs, MS_PER_DAY) * MS_PER_DAY
        val seconds = withinDay / 1_000L
        return "%02d:%02d:%02d".format(seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L)
    }

    /** `HH:mm:ss.SSS` for an epoch-milliseconds instant, in UTC, for a sub-second span. */
    fun clockWithMillisFromEpochMs(epochMs: Long): String {
        val withinDay = epochMs - floorDiv(epochMs, MS_PER_DAY) * MS_PER_DAY
        val seconds = withinDay / 1_000L
        val millis = withinDay % 1_000L
        return "%02d:%02d:%02d.%03d".format(
            seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L, millis,
        )
    }

    fun relativeDay(endedAtIso: String, nowMs: Long): RelativeDay {
        val ended = parseIsoDate(endedAtIso) ?: return RelativeDay(RelativeKind.Date, endedAtIso)
        val today = ymdFromEpochMs(nowMs)
        if (ended == today) return RelativeDay(RelativeKind.Today, ended)
        val yesterday = ymdFromEpochMs(nowMs - MS_PER_DAY)
        if (ended == yesterday) return RelativeDay(RelativeKind.Yesterday, ended)
        return RelativeDay(RelativeKind.Date, ended)
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        val r = a % b
        if (r != 0L && (a xor b) < 0L) q -= 1
        return q
    }
}
