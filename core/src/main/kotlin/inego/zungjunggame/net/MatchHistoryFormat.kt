package inego.zungjunggame.net

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.platform.PlatformTimeZone
import kotlin.math.roundToInt

/**
 * Display strings for a [MatchSummary]. Uses [L10n]; unit-testable once bootstrapped.
 */
object MatchHistoryFormat {
    /**
     * The day label for an instant: Today/Yesterday in the device's zone, or the
     * local calendar date. A process that cannot place an instant reads UTC.
     */
    fun dayLabel(instantIso: String, nowMs: Long): String {
        val day = UtcDate.relativeDay(instantIso, nowMs, PlatformTimeZone::offsetMinutesAt)
        return when (day.kind) {
            UtcDate.RelativeKind.Today -> L10n.get("history.today")
            UtcDate.RelativeKind.Yesterday -> L10n.get("history.yesterday")
            UtcDate.RelativeKind.Date -> day.ymd
        }
    }

    /**
     * How long a finished match or Shadow Ranked attempt took, as `mm:ss`, or null
     * when either stamp is missing or unparseable.
     *
     * [startedAt] and [finishedAt] are the server's RFC3339 UTC stamps from
     * `GameFinished`. A negative span is refused rather than shown: it means the
     * two fields were not the pair this expects.
     */
    fun durationLabel(startedAt: String?, finishedAt: String?): String? {
        val start = startedAt?.let(UtcDate::epochMillisFromIso) ?: return null
        val end = finishedAt?.let(UtcDate::epochMillisFromIso) ?: return null
        val span = end - start
        if (span < 0L) return null
        return L10n.format("hand.duration", clockLabel(span))
    }

    /** `mm:ss` for a non-negative span; minutes are not capped at two digits. */
    fun clockLabel(spanMs: Long): String {
        val totalSeconds = spanMs / 1_000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    /**
     * When a notice was sent, as `Today · 19:27` / `Yesterday · 06:04` /
     * `2026-09-28 · 19:27`, or null when [createdAt] is blank or unparseable.
     *
     * The day word is the one match history already shows ([history.today] /
     * [history.yesterday]), so the two lists read the same and no locale needs a
     * new translation. Both the day and the clock are read in the device's zone
     * ([PlatformTimeZone]); a process that cannot place the instant reads UTC.
     */
    fun sentLabel(createdAt: String, nowMs: Long): String? {
        val millis = UtcDate.epochMillisFromIso(createdAt) ?: return null
        val offset = PlatformTimeZone.offsetMinutesAt(millis) ?: 0
        return L10n.format(
            "history.day_at",
            dayLabel(createdAt, nowMs),
            UtcDate.clockFromEpochMs(millis + offset * UtcDate.MS_PER_MINUTE),
        )
    }

    fun placeLabel(placement: Int): String = when (placement) {
        1 -> L10n.get("history.place_1")
        2 -> L10n.get("history.place_2")
        3 -> L10n.get("history.place_3")
        4 -> L10n.get("history.place_4")
        else -> L10n.format("history.place_n", placement)
    }

    /**
     * 1-based place among [totals], matching the server: seats with a strictly
     * higher score rank above this seat; ties share the best of those places.
     */
    fun placement(totals: IntArray, seat: Int): Int {
        if (seat !in totals.indices) return 0
        val mine = totals[seat]
        return 1 + totals.count { it > mine }
    }

    /**
     * Place text for a **live** result line, where the totals are known.
     *
     * [placement] gives tied seats the best of the places they cover and skips the
     * rest, so the bare number can read as a mistake — "3rd place" in a four-seat
     * game, because two seats tied above it. Saying how many seats share the place
     * shows the tie without inventing a tie-break the server does not use: its own
     * `match_placement` is `count(higher) + 1`, and match history shows that number.
     *
     * Returns `""` for a seat outside [totals] (an observer has no place).
     */
    fun placementText(totals: IntArray, seat: Int): String {
        if (seat !in totals.indices) return ""
        val share = totals.count { it == totals[seat] }
        val place = placeLabel(placement(totals, seat))
        return if (share <= 1) place else L10n.format("history.place_shared", place, share)
    }

    /**
     * Label for the rating change the server reported for a match, rounded once for
     * display. Null means the server has no rating for this match yet.
     *
     * Deliberately takes the delta, not the two ratings. Rounding both endpoints and
     * subtracting reports a different number than the match awarded: `1188.4 ->
     * 1196.65` read as `+9` against a real delta of `+8.25` (2026-09-17). The two
     * ratings are display values; the delta is a fact the match owns.
     */
    fun deltaLabel(delta: Double?): String {
        val rounded = delta?.roundToInt() ?: return L10n.get("history.rating_pending")
        return deltaLabel(rounded)
    }

    /**
     * [deltaLabel] for a change already rounded. The popup's chip uses this so its
     * sign, its tint and the number it prints all come from the same integer.
     */
    fun deltaLabel(delta: Int): String = when {
        delta > 0 -> "+$delta"
        delta < 0 -> "\u2212${-delta}"
        else -> "0"
    }

    fun ratingLabel(newRating: Double?): String? =
        newRating?.roundToInt()?.toString()

    /**
     * Rating text for a value already rounded to an [Int].
     *
     * Every rating on screen goes through one of these two overloads, because the
     * number must read the same everywhere: [L10n.format] substitutes a **boxed
     * number** through the bundle locale's `NumberFormat`, which both groups digits
     * (`1,199`) and — when the call site passes `Double.toInt()` — truncates instead
     * of rounding (`1199.75` → `1199`). Passing the label as a string opts out.
     */
    fun ratingLabel(rating: Int): String = rating.toString()

    fun roundsLabel(rounds: String): String = when (rounds) {
        "one" -> L10n.get("presence.rounds_one")
        "two" -> L10n.get("presence.rounds_two")
        else -> rounds
    }

    fun rulesLabel(rounds: String, allowChicken: Boolean): String =
        L10n.format(
            "history.rules_line",
            roundsLabel(rounds),
            PatternlessRule.standalone(allowChicken),
        )

    fun statusLabel(status: MatchStatus): String = when (status) {
        MatchStatus.Finished -> L10n.get("history.status_finished")
        MatchStatus.Left -> L10n.get("history.status_left")
        MatchStatus.Aborted -> L10n.get("history.status_aborted")
    }

    fun scoreLabel(score: Int): String = L10n.format("history.score", score)
}
