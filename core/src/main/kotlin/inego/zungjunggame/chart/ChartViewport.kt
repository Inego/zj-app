package inego.zungjunggame.chart

import kotlin.math.max
import kotlin.math.min

/**
 * The rating chart's visible time window: a `Double` millisecond range held as an
 * offset from a **nearby origin**, never an epoch timestamp squeezed into a
 * `Float`.
 *
 * Epoch milliseconds are ~1.7e12. A `Float` has 24 bits of mantissa, so at that
 * magnitude it cannot represent a step finer than ~100 ms — panning a chart
 * zoomed to a millisecond per pixel would land on the same pixel. A `Double`
 * represents integer milliseconds exactly to 9e15, and [originEpochMs] keeps the
 * arithmetic near zero anyway: every offset here is a few seconds or days, so a
 * window at millisecond width still subtracts nearby numbers.
 *
 * The value is immutable: an operation returns the window it produces, and the
 * owner (the chart component, holding the persisted route state) decides what to
 * store. All time math is epoch milliseconds at the edges ([fromEpochMs],
 * [toEpochMs]) and `Double` offsets from [originEpochMs] inside.
 *
 * **Origin.** [originEpochMs] is the all-time start of the history, which is the
 * one instant the window, the bounds, and the renderer all name. A bounds
 * refresh ([withBounds], e.g. a backfill that added earlier events) re-origins
 * the value; the epoch accessors are what stay stable across that.
 *
 * **Bounds and clamping.** The visible span is clamped to
 * `[minSpanMs, all-time span]`, and the window is clamped inside the all-time
 * bounds, so a gesture can never scroll off the history or invert the axis. A
 * window that already sits on a bound stays glued to it while the zoom anchor is
 * near that same edge ([zoomBy]).
 *
 * **A snapshot, not a feed.** The window the player leaves is the window they get
 * back: new bounds ([withBounds]) re-origin the value but never move the visible
 * range, so a chart does not chase the newest event while it is being read.
 */
class ChartViewport private constructor(
    /** Epoch ms every offset here is measured from: the history's all-time start. */
    val originEpochMs: Double,
    /** The latest instant the owner knows about, as an offset from [originEpochMs]. */
    val latestOffsetMs: Double,
    /** Visible range start, as an offset from [originEpochMs]. */
    val fromOffsetMs: Double,
    /** Visible range end, as an offset from [originEpochMs]. */
    val toOffsetMs: Double,
    /** Zoom-in floor of the visible span; the all-time span is the zoom-out ceiling. */
    val minSpanMs: Double,
) {

    /** The visible span in milliseconds; the renderer's level-choice hook. */
    val spanMs: Double get() = toOffsetMs - fromOffsetMs

    /** The whole history's span in milliseconds. */
    val allTimeSpanMs: Double get() = latestOffsetMs

    /** Visible range start, as epoch milliseconds. */
    val fromEpochMs: Double get() = originEpochMs + fromOffsetMs

    /** Visible range end, as epoch milliseconds. */
    val toEpochMs: Double get() = originEpochMs + toOffsetMs

    /** The all-time start, as epoch milliseconds (the origin itself). */
    val allTimeFromEpochMs: Double get() = originEpochMs

    /** The latest instant the owner knows, as epoch milliseconds. */
    val allTimeToEpochMs: Double get() = originEpochMs + latestOffsetMs

    // ── Transforms ───────────────────────────────────────────────────────────

    /**
     * Where [epochMs] sits across the plot: 0 at [fromEpochMs], 1 at [toEpochMs],
     * and outside that range for an instant outside the window. A degenerate
     * window (no span at all) reports 0.
     */
    fun fractionOf(epochMs: Double): Double =
        if (spanMs <= 0.0) 0.0 else (epochMs - fromEpochMs) / spanMs

    /** The instant at [fraction] across the plot: the exact inverse of [fractionOf]. */
    fun epochAt(fraction: Double): Double = fromEpochMs + fraction * spanMs

    /** Plot x of [epochMs] in a plot of [plotWidthPx] starting at [plotLeft]. */
    fun plotX(epochMs: Double, plotLeft: Float, plotWidthPx: Float): Float =
        plotLeft + (fractionOf(epochMs) * plotWidthPx).toFloat()

    /** The instant at [plotX]; the inverse of [plotX] and the renderer's hint hit test. */
    fun epochAtPlotX(plotX: Float, plotLeft: Float, plotWidthPx: Float): Double =
        if (plotWidthPx <= 0f) fromEpochMs else epochAt((plotX - plotLeft) / plotWidthPx.toDouble())

    /** Where [plotX] sits across the plot, before any clamping. */
    fun plotFraction(plotX: Float, plotLeft: Float, plotWidthPx: Float): Double =
        if (plotWidthPx <= 0f) 0.5 else (plotX - plotLeft) / plotWidthPx.toDouble()

    /**
     * How many [bucketMs]-wide candles the window spans. The renderer's level
     * choice (with its hysteresis band) reads this to pick a resolution.
     */
    fun candleCount(bucketMs: Double): Double =
        if (bucketMs <= 0.0) 0.0 else spanMs / bucketMs

    // ── Window operations ────────────────────────────────────────────────────

    /**
     * Pan the content by [dxPixels] of a plot [plotWidthPx] wide: positive moves
     * the content right, revealing **earlier** time — the same "content sticks to
     * the finger" direction the vertical lists use, and the sign a finger's
     * horizontal delta and the wheel's horizontal delta already share.
     */
    fun panByPixels(dxPixels: Float, plotWidthPx: Float): ChartViewport {
        if (plotWidthPx <= 0f || !dxPixels.isFinite()) return this
        return panByFraction(dxPixels.toDouble() / plotWidthPx)
    }

    /** Pan by a fraction of the plot; positive moves the content right (earlier time). */
    fun panByFraction(dxFraction: Double): ChartViewport {
        if (!dxFraction.isFinite() || dxFraction == 0.0) return this
        return gestured(fromOffsetMs - dxFraction * spanMs, spanMs)
    }

    /**
     * Zoom by [factor] (the span is multiplied, so below 1 zooms in) keeping the
     * instant at [anchorFraction] of the chart's content area where it is. The
     * clamp can pull the window inside the all-time bounds; the anchor then slides
     * only as far as the bound requires.
     *
     * **The anchor holds, except at a bound.** Holding the instant under the cursor
     * is the right rule in the middle of the history, and zooming out at a bound
     * needs no special case: such a window always leaves its bound, so the clamp
     * pins it again. Zooming *in* at a bound is different. With the newest instant
     * on the right edge and the cursor a tenth of the way in from it, holding the
     * cursor's instant drags the edge off the end of the history by a tenth of that
     * notch's shrink; each further notch drags it further, until "the end" is a pan
     * away — the candles the player was reading slide off the right edge while they
     * zoom in on them. So a window already on a bound stays **glued** to it when the
     * anchor is within [EDGE_STICK_FRACTION] of that same edge: the anchored instant
     * drifts by that small amount toward the edge instead of the edge leaving the
     * data. The margin is a fraction of the content width, so a pinch midpoint, a
     * wheel notch, a phone and a desktop window all get the same behaviour.
     */
    fun zoomBy(factor: Double, anchorFraction: Double): ChartViewport {
        if (!factor.isFinite() || factor <= 0.0 || !anchorFraction.isFinite()) return this
        val span = spanMs * factor
        val fromRequested = when {
            atRightBound() && anchorFraction >= 1.0 - EDGE_STICK_FRACTION ->
                latestOffsetMs - span
            atLeftBound() && anchorFraction <= EDGE_STICK_FRACTION -> 0.0
            else -> {
                val anchor = fromOffsetMs + anchorFraction * spanMs
                anchor - anchorFraction * span
            }
        }
        return gestured(fromRequested, span)
    }

    /** The whole history at its own extent, from the all-time start. */
    fun jumpToAllTime(): ChartViewport = window(0.0, latestOffsetMs)

    /**
     * New all-time bounds from the owner — fresh metadata or a backfill that added
     * earlier events. The origin moves to the new all-time start and the visible
     * range keeps its instants where the new bounds allow them; the window is a
     * snapshot, so growing bounds do not move it.
     */
    fun withBounds(allTimeFromEpochMs: Double, allTimeToEpochMs: Double): ChartViewport {
        if (!allTimeFromEpochMs.isFinite() || !allTimeToEpochMs.isFinite()) return this
        val origin = min(allTimeFromEpochMs, allTimeToEpochMs)
        val latest = max(0.0, allTimeToEpochMs - origin)
        val rebased = ChartViewport(origin, latest, 0.0, 0.0, minSpanMs)
        return rebased.window(fromEpochMs - origin, spanMs)
    }

    // ── Clamping ─────────────────────────────────────────────────────────────

    /** True when the window already ends at the newest instant the chart may show. */
    private fun atRightBound(): Boolean = fromOffsetMs >= maxFromOffset(spanMs)

    /** True when the window already starts at the all-time start. */
    private fun atLeftBound(): Boolean = fromOffsetMs <= 0.0

    /** The furthest a window of [span] may start: the all-time end less that span. */
    private fun maxFromOffset(span: Double): Double =
        (latestOffsetMs - span).coerceAtLeast(0.0)

    /**
     * The one place a requested `(from, span)` becomes a valid window: the span is
     * clamped to the ladder `[minSpanMs, all-time span]` and the position inside
     * the all-time bounds, so no operation can leave the axis inverted or empty.
     */
    private fun window(
        fromRequested: Double,
        spanRequested: Double,
    ): ChartViewport {
        val maxSpan = latestOffsetMs.coerceAtLeast(0.0)
        val floor = min(minSpanMs, maxSpan).coerceAtLeast(0.0)
        val span = when {
            !spanRequested.isFinite() -> maxSpan
            maxSpan <= 0.0 -> 0.0
            else -> spanRequested.coerceIn(floor, maxSpan)
        }
        val maxFrom = maxFromOffset(span)
        val from = if (fromRequested.isFinite()) fromRequested.coerceIn(0.0, maxFrom) else maxFrom
        return ChartViewport(originEpochMs, latestOffsetMs, from, from + span, minSpanMs)
    }

    /** A gesture's window: clamped as [window] does, and nothing else. */
    private fun gestured(fromRequested: Double, spanRequested: Double): ChartViewport =
        window(fromRequested, spanRequested)

    companion object {
        /** Zoom-in floor when the owner names none: the ladder's finest bucket. */
        const val DEFAULT_MIN_SPAN_MS = 1.0

        /**
         * How close to an edge — as a fraction of the content width — the zoom
         * anchor must be for a window already on that bound to stay glued to it
         * ([zoomBy]). About a tenth of the chart is the width of a few candles at a
         * readable zoom: close enough to mean "the end", far enough that a player
         * zooming around the middle keeps the exact instant they pointed at.
         */
        const val EDGE_STICK_FRACTION = 0.1

        /**
         * A window over `[allTimeFromEpochMs, allTimeToEpochMs]`, showing
         * `[fromEpochMs, toEpochMs]`. The all-time start is the origin; the
         * visible range is clamped into the bounds and to the span floor.
         */
        fun of(
            allTimeFromEpochMs: Double,
            allTimeToEpochMs: Double,
            fromEpochMs: Double,
            toEpochMs: Double,
            minSpanMs: Double = DEFAULT_MIN_SPAN_MS,
        ): ChartViewport {
            val origin = min(allTimeFromEpochMs, allTimeToEpochMs).takeIf { it.isFinite() } ?: 0.0
            val latest = max(0.0, allTimeToEpochMs - origin).takeIf { it.isFinite() } ?: 0.0
            val floor = if (minSpanMs.isFinite()) max(0.0, minSpanMs) else DEFAULT_MIN_SPAN_MS
            return ChartViewport(origin, latest, 0.0, 0.0, floor)
                .window(fromEpochMs - origin, toEpochMs - fromEpochMs)
        }
    }
}
