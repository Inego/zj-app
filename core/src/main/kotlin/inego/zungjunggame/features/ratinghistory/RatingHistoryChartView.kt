package inego.zungjunggame.features.ratinghistory

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.chart.ChartTimeAxis
import inego.zungjunggame.chart.ChartViewport
import inego.zungjunggame.chart.ChartYAxis
import inego.zungjunggame.chart.TimeTick
import inego.zungjunggame.net.RatingCandle
import inego.zungjunggame.net.RatingTileStatus
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import inego.zungjunggame.render.ui.AppPalette

/**
 * Paint for the rating plot: grid, time/rating axes, carry segments, and the
 * candlesticks themselves.
 *
 * **Reads, never decides.** The component chooses the resolution and owns the
 * route window; this view receives one [RatingChartSeries] (one level of E2's
 * cached tiles) and a [ChartViewport], and draws exactly those candles. It keeps
 * only view-local state: the eased [ChartYAxis]. A settled chart paints nothing
 * new and asks for no frames.
 *
 * **Zero waiting.** Everything drawn is already retained. A pan or zoom reads the
 * same cached tiles and re-projects them; the network only widens coverage. No
 * candle is interpolated, and no empty interval is drawn flat unless the server
 * said that tile is [RatingTileStatus.Covered] — a `before_history`,
 * `after_history` or `unavailable` tile leaves a visible gap.
 *
 * **Empty windows.** A window the server covers but that holds no candle is still
 * a chart: the grid, both axes and the flat carry across the window are painted,
 * and the carry is the exact rating the window opened on, never an
 * interpolation. The axis target is a single value there, so the carry lands on
 * the plot's mid line. The owner paints its own "no games in this range" line
 * over the result.
 *
 * **Every candle is final.** The newest bucket a response carries is a bucket the
 * server had published when it answered, so it is drawn like every other candle:
 * the chart is a snapshot of a reading, not a live feed with a mutable tail.
 *
 * **A candle is placed by its data, never by the window.** Its x is its bucket's
 * middle, pulled inside the publication's own span. The newest bucket runs past the
 * end of the published history (its last event) and the oldest starts before it, so
 * those two marks are placed at the bound they cross — the last and first event —
 * instead of falling off the plot, which had left the flat carry reading as "nothing
 * since". Clipping to the publication's bounds rather than to the window is what
 * makes the placement stable: a cut candle slides with the rest of the plot while
 * the player pans instead of sticking to the edge and popping away.
 *
 * **Connect marks, not buckets.** Candles are capped-width marks, not bodies
 * filling their time buckets. Flat connectors meet their body edges; painting
 * and picking share those endpoints. Only the popup's interval uses bucket
 * boundaries. Even adjacent buckets need connecting ink, but no invented
 * nonempty carry interval. Covered tile boundaries join at their projected x.
 *
 * **Precision.** Geometry uses the full `Double` ratings and epoch instants;
 * only axis labels are rounded. The rating ticks come from the axis *target*
 * range, not the eased range, so labels stay stable while the scale eases.
 */
class RatingHistoryChartView {

    private val axis = ChartYAxis()

    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    /** The last chart identity the axis was built for; a target change snaps. */
    private var lastTarget: RatingHistoryTarget? = null

    /** The candle area of the last painted frame; null before the first paint. */
    private var area: RatingChartArea? = null

    /**
     * The flat carries the last paint put down, merged across tile seams and held
     * as parallel primitives: the hit test reads the ink the paint laid down
     * without allocating on a settled frame.
     */
    private var carryCount = 0
    private var carryFrom = LongArray(8)
    private var carryTo = LongArray(8)
    private var carryRating = DoubleArray(8)
    private var carryY = FloatArray(8)
    private var carryLeft = FloatArray(8)
    private var carryRight = FloatArray(8)

    /** True while the vertical scale is still easing toward its target. */
    val isAnimating: Boolean get() = axis.isEasing

    /**
     * Forget the previous surface's eased scale and painted selection.
     *
     * The overlay that owns this view is shared across route instances, so the
     * Y axis would otherwise carry the window the last visit left into the next
     * open and animate the new window from that old scale. A new surface snaps
     * to its own first target instead.
     */
    fun reset() {
        axis.clear()
        lastTarget = null
        area = null
        carryCount = 0
    }

    /**
     * Advance the Y ease for this frame. [series] and [viewport] are this frame's
     * data and window; null clears the axis so the first real value snaps.
     */
    fun advance(
        deltaSec: Float,
        series: RatingChartSeries?,
        viewport: ChartViewport?,
        target: RatingHistoryTarget?,
    ) {
        if (target != lastTarget) {
            axis.clear()
            lastTarget = target
        }
        if (series == null || viewport == null) {
            axis.clear()
            return
        }
        updateTarget(series, viewport)
        axis.advance(deltaSec)
    }

    /**
     * Paint one frame. [plot] is the chart's reserved rect; axes and their gutters
     * live inside it, so the published gesture region and the painted plot are the
     * same rectangle.
     *
     * [highlight] is the selection the popup names, if any: a candle is painted
     * in a brighter ink over a halo of its own range, a flat carry as a brighter,
     * thicker line, so the numbers and the ink they are about are visibly one
     * thing.
     *
     * Returns true when the plot was painted. False means there was nothing to map
     * onto the axes — no covered tile contributes a value in this window, or the
     * plot is too small to lay out — so the caller's own state line is the plot.
     * That answer is this view's, not the caller's: the caller has already decided
     * whether a chart may be painted *at all* for its phase, and only this view
     * knows whether a chart is actually mappable from what it was handed.
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        plot: Rect,
        series: RatingChartSeries?,
        viewport: ChartViewport?,
        target: RatingHistoryTarget?,
        highlight: RatingHistoryPopup? = null,
    ): Boolean {
        // A frame that paints nothing must not leave last frame's carries for the
        // hit test to name.
        carryCount = 0
        if (series == null || viewport == null) return false
        if (target != lastTarget) {
            axis.clear()
            lastTarget = target
        }
        // A route pushed during input has not had a motion phase yet. Snap this
        // first frame instead of painting an empty plot until the next one.
        if (!axis.hasTarget) {
            updateTarget(series, viewport)
            axis.snapToTarget()
        }
        if (!axis.hasTarget) return false

        val font = Fonts.small
        val area = plotArea(plot, font)
        if (area.width <= 1f || area.height <= 1f) return false
        // The area this frame painted, kept for the hit test: a pointer names the
        // candle the paint put there.
        this.area = area

        // One tick list for both painters: a grid line and its label cannot
        // disagree about where the axis ticks.
        val timeTicks = ChartTimeAxis.ticks(viewport.fromEpochMs, viewport.toEpochMs)
        drawGrid(batch, WhitePixel.get(), area, viewport, timeTicks)
        drawCandles(batch, WhitePixel.get(), camera, area, series, viewport, highlight)
        drawAxisLabels(batch, plot, area, font, viewport, timeTicks)
        batch.setColor(Color.WHITE)
        return true
    }

    // ── Layout inside the published plot ────────────────────────────────────

    /**
     * The candle area of the last painted frame, or null before the first paint.
     *
     * The hit test and the gesture anchor read it, so a pointer names the candle
     * the paint put there — and a zoom holds the instant the paint put there — one
     * area, one answer.
     */
    internal val chartArea: RatingChartArea? get() = area

    /**
     * The band the last painted frame put [candle]'s own range in, or null
     * before a paint or when the candle carries no usable range.
     *
     * The vertical half of "one area, one answer": a pointer names a candle only
     * where that candle's ink is, and only this view knows the scale the paint
     * used — the eased one, which a re-derivation would miss mid-ease.
     */
    internal fun candleBand(candle: RatingCandle): ClosedFloatingPointRange<Float>? {
        val area = area ?: return null
        if (!candle.high.isFinite() || !candle.low.isFinite()) return null
        val highY = axis.y(candle.high, area.bottom, area.height)
        val lowY = axis.y(candle.low, area.bottom, area.height)
        return min(highY, lowY)..max(highY, lowY)
    }

    /**
     * The flat carry at ([x], [y]) in world coordinates, or null when the pointer
     * is not on one.
     *
     * The answer is the last paint's, exactly as [candleBand] is: the carries were
     * recorded with the axis that drew them, so the line the reader points at and
     * the line the popup names are the same ink. The vertical reach is a finger's,
     * like a flat candle's minimum band, because the ink itself is a hairline;
     * horizontally the line's own span decides.
     */
    internal fun carryAt(x: Float, y: Float): RatingCarry? {
        val area = area ?: return null
        if (carryCount == 0 || !area.contains(x, y)) return null
        val reach = max(1f, Fonts.px(CARRY_PICK_BAND_DP)) * 0.5f
        for (index in 0 until carryCount) {
            if (carryY[index] < area.bottom || carryY[index] > area.top) continue
            if (x < carryLeft[index] || x > carryRight[index]) continue
            if (abs(y - carryY[index]) > reach) continue
            return RatingCarry(carryFrom[index], carryTo[index], carryRating[index])
        }
        return null
    }

    private fun plotArea(plot: Rect, font: BitmapFont): RatingChartArea =
        ratingChartArea(plot, font)

    // ── Visible bounds for the Y axis ───────────────────────────────────────

    /**
     * Fold the visible candles' highs/lows and every visible covered tile's carry
     * into one target range. A non-covered tile contributes nothing — an
     * unavailable interval must not pull the axis toward a rating it never had.
     */
    private fun updateTarget(series: RatingChartSeries, viewport: ChartViewport) {
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        val first = series.firstTileEndingAfter(viewport.fromEpochMs)
        val last = series.lastTileStartingBefore(viewport.toEpochMs)
        var index = first
        while (index <= last) {
            val tile = series.tiles[index]
            if (tile.status == RatingTileStatus.Covered) {
                val candles = tile.candles
                val firstCandle = series.firstCandleOverlapping(candles, viewport.fromEpochMs)
                val lastCandle = series.lastCandleStartingBefore(candles, viewport.toEpochMs)
                // The rating the window opens on: the close of the last candle
                // before the visible range (which may be earlier in this tile), or
                // the tile's exact opening carry when there is none.
                val openingCarry = if (firstCandle > 0) {
                    candles[firstCandle - 1].close
                } else {
                    tile.carryRating
                }
                if (openingCarry != null && openingCarry.isFinite()) {
                    low = min(low, openingCarry)
                    high = max(high, openingCarry)
                }
                var candleIndex = firstCandle
                while (candleIndex <= lastCandle) {
                    val candle = candles[candleIndex]
                    if (candle.low.isFinite()) low = min(low, candle.low)
                    if (candle.high.isFinite()) high = max(high, candle.high)
                    candleIndex++
                }
            }
            index++
        }
        if (low <= high) axis.setTarget(low, high) else axis.clear()
    }

    // ── Grid and axes ───────────────────────────────────────────────────────

    private fun drawGrid(
        batch: SpriteBatch,
        px: Texture,
        area: RatingChartArea,
        viewport: ChartViewport,
        timeTicks: List<TimeTick>,
    ) {
        batch.setColor(GRID_INK)
        val thickness = max(1f, Fonts.px(1f))

        // Rating lines: values from the stable target, positions from the eased
        // mapping, so a label never moves relative to the rating it names.
        val step = ratingStep(axis.targetHigh - axis.targetLow)
        val firstTick = ceil(axis.targetLow / step).toLong()
        val lastTick = floor(axis.targetHigh / step).toLong()
        var tick = firstTick
        var drawn = 0
        while (tick <= lastTick && drawn < MAX_RATING_TICKS) {
            val value = tick * step
            val y = axis.y(value, area.bottom, area.height)
            if (y >= area.bottom && y <= area.top) {
                batch.draw(px, area.left, y - thickness * 0.5f, area.width, thickness)
            }
            tick++
            drawn++
        }

        // Time lines: the axis's ticks, across the candle area.
        for (tick in timeTicks) {
            val x = viewport.plotX(tick.epochMs.toDouble(), area.left, area.width)
            if (x >= area.left && x <= area.right) {
                batch.draw(px, x - thickness * 0.5f, area.bottom, thickness, area.height)
            }
        }
        batch.setColor(Color.WHITE)
    }

    private fun drawAxisLabels(
        batch: SpriteBatch,
        plot: Rect,
        area: RatingChartArea,
        font: BitmapFont,
        viewport: ChartViewport,
        timeTicks: List<TimeTick>,
    ) {
        val inset = Fonts.px(CHART_INSET_DP)
        font.color = AXIS_INK

        // Rating labels: right-aligned in the gutter, at the same y as their line.
        val step = ratingStep(axis.targetHigh - axis.targetLow)
        val firstTick = ceil(axis.targetLow / step).toLong()
        val lastTick = floor(axis.targetHigh / step).toLong()
        val labelWidth = Fonts.textWidth(font, RATING_LABEL_SAMPLE)
        val labelLeft = plot.right - inset - labelWidth
        val minLabelGap = font.capHeight + Fonts.px(2f)
        var previousBaseline = Float.NEGATIVE_INFINITY
        var tick = firstTick
        var drawn = 0
        while (tick <= lastTick && drawn < MAX_RATING_TICKS) {
            val value = tick * step
            val y = axis.y(value, area.bottom, area.height)
            if (y >= area.bottom && y <= area.top && y > previousBaseline + minLabelGap) {
                val text = (value).roundToInt().toString()
                font.draw(
                    batch,
                    text,
                    labelLeft,
                    y + font.capHeight * 0.5f,
                    labelWidth,
                    Align.right,
                    false,
                )
                previousBaseline = y
            }
            tick++
            drawn++
        }

        // Time labels under the candle area, one per tick. A label is clamped to
        // the plot rather than dropped at an edge: the first tick is the one that
        // names the day, and a window opening on a step boundary puts it exactly
        // on the edge. A label that would still collide with its predecessor is
        // dropped, so a narrow plot keeps the leftmost of two crowded labels.
        val baseline = plot.bottom + inset + font.capHeight
        val gap = Fonts.px(CHART_LABEL_GAP_DP)
        var previousRight = Float.NEGATIVE_INFINITY
        for (tick in timeTicks) {
            val width = Fonts.textWidth(font, tick.label)
            if (width > area.width) continue
            val x = viewport.plotX(tick.epochMs.toDouble(), area.left, area.width)
            val left = (x - width * 0.5f).coerceIn(area.left, area.right - width)
            if (left > previousRight + gap) {
                font.draw(batch, tick.label, left, baseline)
                previousRight = left + width
            }
        }
        font.color = Color.WHITE
    }

    // ── Candles and carries ─────────────────────────────────────────────────

    private fun drawCandles(
        batch: SpriteBatch,
        px: Texture,
        camera: Camera,
        area: RatingChartArea,
        series: RatingChartSeries,
        viewport: ChartViewport,
        highlight: RatingHistoryPopup?,
    ) {
        val first = series.firstTileEndingAfter(viewport.fromEpochMs)
        val last = series.lastTileStartingBefore(viewport.toEpochMs)
        if (first > last) return

        val spanMs = viewport.spanMs
        if (spanMs <= 0.0) return
        val bucketPx = area.width * (series.bucketMs.toDouble() / spanMs)
        val bodyWidth = (bucketPx * BODY_FRACTION).toFloat().coerceIn(1f, Fonts.px(MAX_BODY_DP))
        val wickWidth = max(1f, Fonts.px(1f))
        val highlightedCandle = (highlight as? RatingHistoryPopup.Candle)?.candle
        val highlightedCarry = (highlight as? RatingHistoryPopup.Carry)?.carry

        batch.flush()
        clipBounds.set(area.left, area.bottom, area.width, area.height)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val clipped = ScissorStack.pushScissors(scissors)
        if (!clipped) return

        // The band behind the named candle goes down first, so it lies under the
        // carry lines and the candles rather than over them.
        if (highlightedCandle != null) {
            drawHighlightHalo(
                batch, px, area, viewport, series.bucketMs, bodyWidth, highlightedCandle,
            )
        }

        for (index in first..last) {
            val tile = series.tiles[index]
            if (tile.status != RatingTileStatus.Covered) continue
            val candles = tile.candles
            // Project the complete covered tile, then clip the ink. Selecting
            // only overlapping buckets loses the neighbours that anchor a
            // connector when the window cuts through half of a bucket.
            var fromMs = tile.startMs
            var left = viewport.plotX(fromMs.toDouble(), area.left, area.width)
            var rating = tile.carryRating
            for (candle in candles) {
                val center = candleX(viewport, area, candle.startMs, series.bucketMs)
                val right = center - bodyWidth * 0.5f
                if (rating != null) {
                    drawCarry(
                        batch, px, area, fromMs, candle.startMs, rating,
                        left, right, highlightedCarry,
                    )
                }
                drawCandle(
                    batch, px, area, viewport, series.bucketMs, bodyWidth, wickWidth, candle,
                    highlighted = highlightedCandle != null && candle == highlightedCandle,
                )
                fromMs = candle.startMs + series.bucketMs
                left = center + bodyWidth * 0.5f
                rating = candle.close
            }
            // Tile edges remain time-projected anchors, so neighbouring covered
            // tiles join there, but unavailable tiles are never bridged.
            if (rating != null) {
                drawCarry(
                    batch, px, area, fromMs, tile.endMs, rating,
                    left, viewport.plotX(tile.endMs.toDouble(), area.left, area.width),
                    highlightedCarry,
                )
            }
        }

        batch.flush()
        ScissorStack.popScissors()
        batch.setColor(Color.WHITE)
    }

    /**
     * The halo behind the candle the popup names: a soft column over the
     * candle's own range, from its low to its high.
     *
     * It is what makes the named candle findable at a glance — the popup's
     * numbers sit beside the pointer, and the halo says which candle they are
     * about. It spans the candle's range rather than the whole plot: a full
     * column would read as a rule across the chart, not as a mark on a candle.
     */
    private fun drawHighlightHalo(
        batch: SpriteBatch,
        px: Texture,
        area: RatingChartArea,
        viewport: ChartViewport,
        bucketMs: Long,
        bodyWidth: Float,
        candle: RatingCandle,
    ) {
        val center = candleX(viewport, area, candle.startMs, bucketMs)
        val half = bodyWidth * 0.5f + Fonts.px(1.5f)
        if (center + half < area.left || center - half > area.right) return
        val highY = axis.y(candle.high, area.bottom, area.height)
        val lowY = axis.y(candle.low, area.bottom, area.height)
        val air = Fonts.px(1.5f)
        val bottom = min(highY, lowY) - air
        val top = max(highY, lowY) + air
        if (top <= bottom) return
        batch.setColor(HIGHLIGHT_HALO_INK)
        batch.draw(px, center - half, bottom, half * 2f, top - bottom)
    }

    /**
     * One flat connector: [fromMs]/[toMs] name its data interval, while
     * [left]/[right] attach its ink to candle body edges or tile boundaries.
     * Adjacent buckets have an empty data interval but still need a connector
     * between their marks; that connector does not name a carry in a popup.
     *
     * The span is recorded — and merged with the previous one when they touch at
     * the same rating — **before** it is clipped, so the hit test names the whole
     * stretch even when it runs off the screen: the popup is about the carry, not
     * about the window that happens to show part of it.
     */
    private fun drawCarry(
        batch: SpriteBatch,
        px: Texture,
        area: RatingChartArea,
        fromMs: Long,
        toMs: Long,
        rating: Double,
        left: Float,
        right: Float,
        highlight: RatingCarry?,
    ) {
        if (!rating.isFinite() || toMs < fromMs || right <= left) return
        val y = axis.y(rating, area.bottom, area.height)
        if (toMs > fromMs) recordCarry(fromMs, toMs, rating, y, left, right)
        val x1 = max(left, area.left)
        val x2 = min(right, area.right)
        if (x2 <= x1) return

        // A selected carry keeps its span but not its quiet ink: it is the line
        // the numbers beside it describe, so it must be findable at a glance.
        val selected = toMs > fromMs && highlight != null &&
            highlight.rating == rating &&
            fromMs >= highlight.fromMs &&
            toMs <= highlight.toMs
        val thickness = if (selected) {
            max(2f, Fonts.px(CARRY_HIGHLIGHT_THICKNESS_DP))
        } else {
            max(1f, Fonts.px(CARRY_THICKNESS_DP))
        }
        batch.setColor(if (selected) CARRY_HIGHLIGHT_INK else CARRY_INK)
        batch.draw(px, x1, y - thickness * 0.5f, x2 - x1, thickness)
    }

    /** Record one painted carry, merging it into the previous one when they touch. */
    private fun recordCarry(
        fromMs: Long,
        toMs: Long,
        rating: Double,
        y: Float,
        left: Float,
        right: Float,
    ) {
        if (carryCount > 0) {
            val last = carryCount - 1
            if (carryTo[last] == fromMs && carryRating[last] == rating) {
                carryTo[last] = toMs
                carryRight[last] = right
                return
            }
        }
        if (carryCount == carryFrom.size) growCarries()
        carryFrom[carryCount] = fromMs
        carryTo[carryCount] = toMs
        carryRating[carryCount] = rating
        carryY[carryCount] = y
        carryLeft[carryCount] = left
        carryRight[carryCount] = right
        carryCount++
    }

    private fun growCarries() {
        val size = carryFrom.size * 2
        carryFrom = carryFrom.copyOf(size)
        carryTo = carryTo.copyOf(size)
        carryRating = carryRating.copyOf(size)
        carryY = carryY.copyOf(size)
        carryLeft = carryLeft.copyOf(size)
        carryRight = carryRight.copyOf(size)
    }

    /**
     * The x a candle's body, wick and halo are centred on: the middle of its
     * bucket, kept inside the publication's own span.
     *
     * **The window's edges are instants inside a bucket, not bucket boundaries.**
     * Its right edge is the newest instant the publication knows — the last event —
     * so the newest bucket runs past the plot, and its plain middle can lie outside
     * it. A candle centred there is painted off the plot; the previous close is
     * then carried flat to the edge, so the newest event reads as "nothing since",
     * and which rungs hit it depends on where in its bucket the event fell. The
     * oldest bucket is cut the same way at the all-time start.
     *
     * So the mark is pulled inside the **publication's** `[first event, last
     * event]` and nowhere else. That bound is the same for every pan, so a cut
     * candle keeps one position and slides with the plot; clipping to the window
     * instead would glue it to an edge and pop it away. Only the two extreme
     * buckets of the history can be cut, and a pulled-back mark lands exactly on
     * the last (or first) event — the honest place for the one candle whose bucket
     * continues past the data.
     *
     * It is deliberately not centred on the "covered" part of a cut bucket: a
     * bucket's events are not spread evenly over `[start, last event]`, so that
     * interval is not where its data is. A lone event near the end of a wide
     * bucket would draw its candle up to half a bucket away from the event and
     * leave the same blank tail the cut caused.
     */
    private fun candleX(
        viewport: ChartViewport,
        area: RatingChartArea,
        startMs: Long,
        bucketMs: Long,
    ): Float {
        val middle = startMs.toDouble() + bucketMs * 0.5
        val inside = middle.coerceIn(viewport.allTimeFromEpochMs, viewport.allTimeToEpochMs)
        return viewport.plotX(inside, area.left, area.width)
    }

    private fun drawCandle(
        batch: SpriteBatch,
        px: Texture,
        area: RatingChartArea,
        viewport: ChartViewport,
        bucketMs: Long,
        bodyWidth: Float,
        wickWidth: Float,
        candle: RatingCandle,
        highlighted: Boolean,
    ) {
        val center = candleX(viewport, area, candle.startMs, bucketMs)
        val left = center - bodyWidth * 0.5f
        // The mark comes from the data, so this is only a visibility test: a bucket
        // that overlaps the window can still be marked off it when the zoom is finer
        // than one bucket, and a mark the player cannot see is not painted.
        if (left > area.right || left + bodyWidth < area.left) return

        val highY = axis.y(candle.high, area.bottom, area.height)
        val lowY = axis.y(candle.low, area.bottom, area.height)
        val openY = axis.y(candle.open, area.bottom, area.height)
        val closeY = axis.y(candle.close, area.bottom, area.height)
        val rising = candle.close >= candle.open

        val bodyBottom = min(openY, closeY)
        val bodyHeight = max(1f, abs(closeY - openY))
        val wickBottom = min(highY, lowY)
        val wickHeight = max(1f, abs(highY - lowY))

        // The named candle is brighter than the rest and its wick is thicker:
        // the eye has to find it among its neighbours at a glance.
        val bodyInk = when {
            highlighted && rising -> HIGHLIGHT_RISING_INK
            highlighted -> HIGHLIGHT_FALLING_INK
            rising -> RISING_INK
            else -> FALLING_INK
        }
        val wick = if (highlighted) wickWidth * HIGHLIGHT_WICK_FACTOR else wickWidth

        batch.setColor(bodyInk)
        batch.draw(px, center - wick * 0.5f, wickBottom, wick, wickHeight)

        batch.setColor(bodyInk)
        batch.draw(px, left, bodyBottom, bodyWidth, bodyHeight)
    }

    // ── Tick selection and formatting ───────────────────────────────────────

    /** A 1/2/5×10^n rating step that yields about [TARGET_RATING_TICKS] lines. */
    private fun ratingStep(span: Double): Double {
        if (!span.isFinite() || span <= 0.0) return 1.0
        val rough = span / TARGET_RATING_TICKS
        val exponent = floor(log10(rough))
        val base = 10.0.pow(exponent)
        val fraction = rough / base
        val nice = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return max(1.0, nice * base)
    }

    private companion object {
        const val MAX_BODY_DP = 16f

        /** Body width as a fraction of its bucket's pixel width. */
        const val BODY_FRACTION = 0.62

        const val TARGET_RATING_TICKS = 4.0
        const val MAX_RATING_TICKS = 10

        val GRID_INK = AppPalette.tint(AppPalette.panelRim, 0.10f)
        val AXIS_INK = AppPalette.inkMuted
        val CARRY_INK = AppPalette.chartAxis
        val RISING_INK = AppPalette.chartRising
        val FALLING_INK = AppPalette.chartFalling

        /** The halo behind the candle the popup names. */
        val HIGHLIGHT_HALO_INK = AppPalette.chartBand

        /** The named candle's own ink: brighter than its neighbours. */
        val HIGHLIGHT_RISING_INK = AppPalette.chartRisingHighlight
        val HIGHLIGHT_FALLING_INK = AppPalette.chartFallingHighlight

        /** How much thicker the named candle's wick is than the others'. */
        const val HIGHLIGHT_WICK_FACTOR = 2f

        /** A carry line's thickness, and the selected carry's. */
        const val CARRY_THICKNESS_DP = 1.5f
        const val CARRY_HIGHLIGHT_THICKNESS_DP = 2.5f

        /**
         * The vertical band a carry may be picked by, in design px. A carry is a
         * hairline; a finger needs the same minimum a flat candle gets.
         */
        const val CARRY_PICK_BAND_DP = 24f

        /** The selected carry's ink: the quiet carry, brought up to full voice. */
        val CARRY_HIGHLIGHT_INK = AppPalette.chartCarryHighlight
    }
}
