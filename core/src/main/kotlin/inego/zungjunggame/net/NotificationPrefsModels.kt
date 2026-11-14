package inego.zungjunggame.net

import kotlin.math.abs

/**
 * One player's notification settings.
 *
 * [emailOptIn] is the Score Breakdown opt-in; [digestIntervalHours] is how often the
 * results of other players' attempts are gathered into one digest — one of
 * [NotificationPrefsScale.HOURS]. [language] is the BCP 47 tag the server renders
 * notification mail in (`ru`, `zh-HK`), or null when the player has set none and mail
 * falls back to English. [timeZone] is the IANA id (or fixed offset) reports render
 * game times in, or null when the device has not reported one and the report stays
 * in UTC.
 */
data class NotificationPrefs(
    val emailOptIn: Boolean,
    val digestIntervalHours: Int,
    val language: String? = null,
    val timeZone: String? = null,
) {
    companion object {
        /** The server's initial values, shown before the first read lands: once a day, English. */
        val DEFAULT = NotificationPrefs(emailOptIn = false, digestIntervalHours = 24)
    }
}

/**
 * The settings slider scale for [NotificationPrefs.digestIntervalHours]: the eight
 * cadences the player may choose, one slider step each.
 *
 * The values are the hours between digests: 1, 2, 3, 4, 6, 8, 12, 24 — every hour,
 * every 2 hours, …, twice a day, once a day. Every value divides the day, so the
 * server sends at fixed UTC slots from midnight.
 */
object NotificationPrefsScale {
    /** The allowed hours between digests, ascending; the slider's steps in order. */
    val HOURS: List<Int> = listOf(1, 2, 3, 4, 6, 8, 12, 24)

    /** The slider's top step. */
    val MAX_STEP: Int get() = HOURS.lastIndex

    /** The slider step for [hours]: its own step, or the nearest allowed one. */
    fun stepFor(hours: Int): Int {
        val exact = HOURS.indexOf(hours)
        if (exact >= 0) return exact
        var best = 0
        var bestGap = Int.MAX_VALUE
        HOURS.forEachIndexed { index, candidate ->
            val gap = abs(candidate - hours)
            if (gap < bestGap) {
                bestGap = gap
                best = index
            }
        }
        return best
    }

    /** The hours a slider [step] commits. */
    fun hoursFor(step: Int): Int = HOURS[step.coerceIn(0, MAX_STEP)]

    /** [hours] as the nearest allowed cadence. */
    fun coerce(hours: Int): Int = hoursFor(stepFor(hours))
}

/** HTTP failure while reading or writing the player's notification settings. */
class NotificationPrefsException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
) : Exception(message, cause)
