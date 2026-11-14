package inego.zungjunggame.features.ratinghistory

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.net.RatingCandle
import inego.zungjunggame.net.UtcDate
import kotlin.math.roundToInt

/**
 * The popup's content: one headline, then the quiet lines under it.
 *
 * **The numbers lead.** The headline is what the reader pointed at — a candle's
 * entry and exit with its signed change, or a carry's held value — and the
 * overlay gives it the large face; a candle's change also gets a chip tinted by
 * its direction. The date, the clock and the extremes follow as small, muted
 * lines: they place the numbers, they are not a second answer competing with
 * them.
 *
 * **Local means local.** The chart's axis is UTC, because that is the grid the
 * server published and the buckets are cut on. This popup speaks the player's
 * own clock — their date, their time of day — because "when did I play" is a
 * question about their day, not about the server's. It says nothing about the
 * offset it read: the reader asked for the time they played, and that is what
 * the line says.
 *
 * **The date line names the weekday.** A date on its own takes a moment to place
 * on a week — "was that the weekend?" — and the weekday is that answer. It is
 * the short name (`Wed`), so the line does not widen the plate, and it is
 * localized from the shared `date.weekday.*` keys rather than derived from a
 * `java.time` formatter, which TeaVM does not carry. The rule is "one date, one
 * weekday": a candle's sub-day bucket names one date even when its clock runs
 * past local midnight, and a whole-day bucket that stays inside one day does too;
 * a range names two dates and carries no weekday, which would speak for only one
 * end. A carry that stays inside one local date follows the same rule; one that
 * crosses a date names both timestamps and no weekday.
 *
 * **The numbers.** A rating is written the way the rest of the app writes one
 * (rounded once, no grouping), and the change is the difference of the two
 * ratings, signed, with the minus sign the app uses everywhere. The high and
 * the low are written only when they are not already one of the two ends.
 *
 * **The clock follows the span.** A time range is written with the precision the
 * span needs: `HH:mm` for a minute or more, `HH:mm:ss` below a minute, and
 * `HH:mm:ss.SSS` below a second. A bucket can be a few seconds wide at a fine
 * zoom, and a minute-only range would then name both ends the same.
 */
internal object RatingHistoryTooltip {

    /**
     * The popup's content for [candle], a bucket of [bucketMs] wide.
     *
     * [offsetMinutes] is the device's UTC offset at the bucket's start; null
     * means the device cannot report one, and the popup then reads UTC — the
     * axis's own clock. The whole bucket is read at that one offset: a bucket
     * that crosses a daylight-saving change would otherwise name two clocks at
     * once.
     */
    fun content(candle: RatingCandle, bucketMs: Long, offsetMinutes: Int?): RatingPopupContent {
        val offset = offsetMinutes ?: 0
        val startMs = candle.startMs
        // The bucket is half-open; the last instant inside it is the end of the
        // range the popup names, while the time range itself reads as the span
        // the bucket covers.
        val endExclusiveMs = startMs + bucketMs
        val endInclusiveMs = endExclusiveMs - 1

        val from = localDate(startMs, offset)
        val to = localDate(endInclusiveMs, offset)
        val context = if (bucketMs >= UtcDate.MS_PER_DAY) {
            // A bucket of a day or more names the days it covers, not a clock
            // time: the day edge is what the reader is looking for. One date gets
            // its weekday; a range names two dates and stays bare, because one
            // weekday would speak for only one end of it.
            if (from == to) {
                dayLine(from, localWeekday(startMs, offset))
            } else {
                L10n.format("ratingHistory.tooltip.dates", from, to)
            }
        } else {
            // The line names the bucket's start date even when the time range
            // runs past local midnight, so it takes that date's weekday: the
            // title says one day, and that day has a name.
            contextLine(
                dayLine(from, localWeekday(startMs, offset)),
                L10n.format(
                    "ratingHistory.tooltip.time_range",
                    localTime(startMs, offset, bucketMs),
                    localTime(endExclusiveMs, offset, bucketMs),
                ),
            )
        }

        val open = rating(candle.open)
        val close = rating(candle.close)
        val roundedChange = (candle.close - candle.open).roundToInt()
        val headline = RatingPopupHeadline.Movement(
            ratings = L10n.format("ratingHistory.tooltip.ratings", open, close),
            change = MatchHistoryFormat.deltaLabel(roundedChange),
            movement = when {
                roundedChange > 0 -> RatingMovement.Up
                roundedChange < 0 -> RatingMovement.Down
                else -> RatingMovement.Flat
            },
        )

        // The extremes are shown only when they tell the reader something the
        // entry and the exit do not already say: a high that repeats one of the
        // two ends is the end, and a low that repeats one of them is the end.
        // A candle that only ever moved one way then reads as one quiet line.
        val lines = ArrayList<RatingPopupLine>(3)
        lines += RatingPopupLine(context, RatingPopupLineTone.Context)
        val high = rating(candle.high)
        if (high != open && high != close) {
            lines += RatingPopupLine(
                L10n.format("ratingHistory.tooltip.max", high),
                RatingPopupLineTone.Extreme,
            )
        }
        val low = rating(candle.low)
        if (low != open && low != close) {
            lines += RatingPopupLine(
                L10n.format("ratingHistory.tooltip.min", low),
                RatingPopupLineTone.Extreme,
            )
        }
        return RatingPopupContent(headline, lines)
    }

    /**
     * The popup's content for one flat [carry]: the held rating as the headline,
     * then the period the line covers as the quiet line under it.
     *
     * The period always names its **times**, at the precision the span needs: a
     * stretch inside one local date gets that date (and its weekday) plus the two
     * clock times; one that crosses a date names both full timestamps, because a
     * single date would speak for only one end. A span shorter than a minute keeps
     * its seconds, so its two ends cannot read the same. The weekday rule is the
     * candle popup's: one date, one weekday; a range carries none.
     *
     * The end is the instant the next candle's bucket starts — the line's
     * exclusive end — as the candle popup's time range is. The whole period is
     * read at [offsetMinutes] taken at its start, so a stretch across a
     * daylight-saving change does not name two clocks at once.
     */
    fun content(carry: RatingCarry, offsetMinutes: Int?): RatingPopupContent {
        val offset = offsetMinutes ?: 0
        val spanMs = (carry.toMs - carry.fromMs).coerceAtLeast(0L)

        val fromDate = localDate(carry.fromMs, offset)
        val toDate = localDate(carry.toMs, offset)
        val context = if (fromDate == toDate) {
            contextLine(
                dayLine(fromDate, localWeekday(carry.fromMs, offset)),
                L10n.format(
                    "ratingHistory.tooltip.time_range",
                    localTime(carry.fromMs, offset, spanMs),
                    localTime(carry.toMs, offset, spanMs),
                ),
            )
        } else {
            // The period is read across dates, so both endpoints carry their own
            // date and time; the range line is the same template the candle's
            // date range uses.
            L10n.format(
                "ratingHistory.tooltip.dates",
                "$fromDate ${localTime(carry.fromMs, offset, spanMs)}",
                "$toDate ${localTime(carry.toMs, offset, spanMs)}",
            )
        }
        return RatingPopupContent(
            headline = RatingPopupHeadline.Value(rating(carry.rating)),
            lines = listOf(RatingPopupLine(context, RatingPopupLineTone.Context)),
        )
    }

    /** The local date of [epochMs], as the app writes a date everywhere. */
    private fun localDate(epochMs: Long, offsetMinutes: Int): String =
        UtcDate.ymdFromEpochMs(epochMs + offsetMinutes * 60_000L)

    /** The date line for a span that lies inside one local day. */
    private fun dayLine(date: String, weekday: String): String =
        L10n.format("ratingHistory.tooltip.day_with_weekday", date, weekday)

    /**
     * The date and the clock as one quiet line, joined the way the rest of the
     * app joins two facts. They are context for the headline, so they share a
     * line and a face rather than competing for the reader's eye.
     */
    private fun contextLine(date: String, time: String): String =
        date + CONTEXT_SEPARATOR + time

    /**
     * The localized full weekday name of the local day containing [epochMs]: it
     * applies the same offset [localDate] does, so the date and the weekday name
     * the same day.
     */
    private fun localWeekday(epochMs: Long, offsetMinutes: Int): String =
        L10n.get(WEEKDAY_KEYS[UtcDate.weekdayFromEpochMs(epochMs + offsetMinutes * 60_000L)])

    /** `date.weekday.*`, in [UtcDate.weekdayFromEpochMs]'s ISO order. */
    private val WEEKDAY_KEYS = arrayOf(
        "date.weekday.mon",
        "date.weekday.tue",
        "date.weekday.wed",
        "date.weekday.thu",
        "date.weekday.fri",
        "date.weekday.sat",
        "date.weekday.sun",
    )

    /**
     * The local time of day of [epochMs], written at the precision [spanMs]
     * needs: minutes for a span of a minute or more, seconds below that, and
     * milliseconds below a second. It is one clock for the whole span; only its
     * precision follows the span.
     */
    private fun localTime(epochMs: Long, offsetMinutes: Int, spanMs: Long): String {
        val shifted = epochMs + offsetMinutes * 60_000L
        return when {
            spanMs >= MS_PER_MINUTE -> UtcDate.clockFromEpochMs(shifted)
            spanMs >= MS_PER_SECOND -> UtcDate.clockWithSecondsFromEpochMs(shifted)
            else -> UtcDate.clockWithMillisFromEpochMs(shifted)
        }
    }

    /** Below these widths, a minute-only clock would collapse a span's two ends. */
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_SECOND = 1_000L

    /** How two facts share one quiet line: the app's own separator. */
    private const val CONTEXT_SEPARATOR = " · "

    /** A rating as the app writes one: rounded once, no digit grouping. */
    private fun rating(value: Double): String = value.roundToInt().toString()
}

/**
 * The popup's content, before paint gives it a face: one headline, then the
 * quiet lines under it.
 *
 * The split is the point. The headline is the answer to the pointer — a candle's
 * movement or a carry's rating — and the overlay paints it in the large face with
 * the movement chip. The lines are the context that places the answer, painted
 * small and muted so they cannot compete with it.
 */
internal data class RatingPopupContent(
    val headline: RatingPopupHeadline,
    val lines: List<RatingPopupLine>,
)

/** The one thing the popup is about, and the one thing painted large. */
internal sealed interface RatingPopupHeadline {
    /** A candle's entry and exit, with the signed change between them. */
    data class Movement(
        val ratings: String,
        val change: String,
        val movement: RatingMovement,
    ) : RatingPopupHeadline

    /** A flat carry's held rating: one value, no label. */
    data class Value(val value: String) : RatingPopupHeadline
}

/** One quiet line under the headline. */
internal data class RatingPopupLine(
    val text: String,
    val tone: RatingPopupLineTone,
)

/** What a quiet line is for, which is what its ink follows. */
internal enum class RatingPopupLineTone {
    /** The date and the clock: where the headline sits. */
    Context,

    /** A candle's high or low: a detail, the quietest print. */
    Extreme,
}

/** Which way a candle moved; the change chip's tint follows it. */
internal enum class RatingMovement { Up, Down, Flat }
