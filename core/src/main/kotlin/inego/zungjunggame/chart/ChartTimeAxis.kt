package inego.zungjunggame.chart

import inego.zungjunggame.net.UtcDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * One labeled instant on the chart's time axis.
 *
 * [label] is what the axis prints at [epochMs]: a time of day, or the calendar
 * date that a time-of-day label cannot carry. Placement, fitting and dropping
 * are the view's job; this value is only the instant and its text.
 */
data class TimeTick(
    val epochMs: Long,
    val label: String,
)

/**
 * The chart's horizontal axis: which instants are ticked, and what each tick says.
 *
 * GL-free and font-free, like [ChartViewport] and [ChartYAxis]: it reads the
 * visible window and returns a short ordered list that the view maps to pixels.
 *
 * **Cadence and format are separate decisions.** [stepMs] chooses how often a
 * tick appears from the span. A label is then composed from the calendar, not
 * from the span: at a sub-day step the first visible tick and the first tick of
 * every new UTC day carry the day (`MM-DD`), and the rest carry the time of day
 * (`HH:mm`, or `HH:mm:ss` below a minute). A day step or coarser already carries
 * its date in the fine label, so nothing changes there.
 *
 * Time is UTC and the text is digits and separators, the same shape in every
 * language: every server instant is UTC and the client knows no player zone. A
 * day is a UTC calendar day, not an offset from the first tick, so a window that
 * starts mid-day still names the day its first tick falls on.
 */
object ChartTimeAxis {

    /**
     * The ticks for the visible window `[fromEpochMs, toEpochMs]`, ascending.
     *
     * The first tick is the first step multiple at or after [fromEpochMs], so
     * every returned tick is visible; the view clamps an edge label rather than
     * this function hiding a tick. A degenerate or non-finite window has none.
     */
    fun ticks(fromEpochMs: Double, toEpochMs: Double): List<TimeTick> {
        if (!fromEpochMs.isFinite() || !toEpochMs.isFinite() || toEpochMs <= fromEpochMs) {
            return emptyList()
        }
        val step = stepMs(toEpochMs - fromEpochMs)
        val granularity = granularityOf(step)
        val ticks = ArrayList<TimeTick>(MAX_TIME_TICKS)
        var previousDay = Long.MIN_VALUE
        var instant = ceil(fromEpochMs / step).toLong() * step
        while (instant <= toEpochMs && ticks.size < MAX_TIME_TICKS) {
            val day = utcDay(instant)
            val label = when {
                granularity != Granularity.Clock -> dateLabel(instant, granularity)
                ticks.isEmpty() || day != previousDay -> dateLabel(instant, Granularity.Day)
                else -> clockLabel(instant, step)
            }
            ticks += TimeTick(instant, label)
            previousDay = day
            instant += step
        }
        return ticks
    }

    // ── Cadence ─────────────────────────────────────────────────────────────

    /**
     * The finest step from [TIME_STEPS] that keeps the window at or under
     * [TARGET_TIME_TICKS] ticks. A window of two days or more never steps in
     * half-days: the label would repeat its date, and the axis would read as two
     * identical ticks. One day's worth of time still shows three ticks across
     * two days.
     */
    private fun stepMs(spanMs: Double): Long {
        var index = 0
        while (index < TIME_STEPS.size - 1 && spanMs / TIME_STEPS[index] > TARGET_TIME_TICKS) {
            index++
        }
        val chosen = TIME_STEPS[index]
        return if (spanMs >= 2.0 * DAY_MS) max(chosen, UtcDate.MS_PER_DAY) else chosen
    }

    private fun granularityOf(stepMs: Long): Granularity = when {
        stepMs >= YEAR_MS -> Granularity.Year
        stepMs >= 28L * UtcDate.MS_PER_DAY -> Granularity.Month
        stepMs >= UtcDate.MS_PER_DAY -> Granularity.Day
        else -> Granularity.Clock
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    /** The coarsest unit a label can print at this step. */
    private enum class Granularity { Clock, Day, Month, Year }

    private fun dateLabel(epochMs: Long, granularity: Granularity): String {
        val ymd = UtcDate.ymdFromEpochMs(epochMs)
        return when (granularity) {
            Granularity.Year -> ymd.substring(0, 4)
            Granularity.Month -> ymd.substring(0, 7)
            Granularity.Day, Granularity.Clock -> ymd.substring(5, 10)
        }
    }

    private fun clockLabel(epochMs: Long, stepMs: Long): String {
        val withinDay = epochMs - utcDay(epochMs) * UtcDate.MS_PER_DAY
        val totalSeconds = withinDay / 1_000L
        val hours = (totalSeconds / 3_600L).toString().padStart(2, '0')
        val minutes = ((totalSeconds / 60L) % 60L).toString().padStart(2, '0')
        if (stepMs >= 60_000L) return "$hours:$minutes"
        val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
        return "$hours:$minutes:$seconds"
    }

    /** The UTC calendar day of [epochMs], as days since 1970-01-01. */
    private fun utcDay(epochMs: Long): Long = floor(epochMs.toDouble() / DAY_MS).toLong()

    private const val TARGET_TIME_TICKS = 5.0
    private const val MAX_TIME_TICKS = 12

    private const val DAY_MS = 86_400_000.0
    private const val YEAR_MS = 365L * 86_400_000L

    /** One candidate per step, ascending; the axis picks the first that fits. */
    private val TIME_STEPS = longArrayOf(
        1_000L, 2_000L, 5_000L, 10_000L, 30_000L,
        60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
        3_600_000L, 3 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L,
        86_400_000L, 2 * 86_400_000L, 7 * 86_400_000L, 14 * 86_400_000L,
        30 * 86_400_000L, 60 * 86_400_000L, 90 * 86_400_000L,
        180 * 86_400_000L, 365 * 86_400_000L,
        2 * 365 * 86_400_000L, 5 * 365 * 86_400_000L, 10 * 365 * 86_400_000L,
    )
}
