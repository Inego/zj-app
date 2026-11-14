package inego.zungjunggame.features.ratinghistory

import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.net.RatingHistoryTiles
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * The chart's resolution ladder: which `2^level` bucket width the plot should be
 * drawn at for its current span and width, and when to change it.
 *
 * **Density.** A plot of [plotWidthPx] targets roughly one bucket per
 * [TARGET_CANDLE_PX] of width (bounded so a tiny phone plot still shows a
 * readable number of candles). The nearest power of two is the preferred rung.
 *
 * **Hysteresis.** The current rung is kept while its candle count stays inside a
 * wide band around the target: a rung changes only when it is more than
 * [FINE_RATIO] times too coarse or [COARSE_RATIO] times too dense. The two
 * thresholds are deliberately separated by more than a factor of two, so one
 * switch can never satisfy the condition for switching straight back and a
 * pinch cannot make the level oscillate. Each step is a full power of two, which
 * is exactly one bucket rung.
 *
 * **The tile clip.** E2 can hold at most [RatingHistoryClient.MAX_VISIBLE_TILES]
 * whole tiles for one window; a rung finer than that is not "held" by
 * [RatingHistoryClient.holds] and would never settle. The selected rung is
 * therefore raised (coarsened) until the visible span touches at most that many
 * canonical tiles. Nothing here does tile arithmetic by hand:
 * [RatingHistoryTiles] owns it.
 */
internal object RatingChartLevel {

    /** Plot width one candle is aimed at, in world pixels. */
    const val TARGET_CANDLE_PX: Float = 10f

    /** A tiny plot still gets at least this many candles' worth of span. */
    private const val MIN_TARGET_CANDLES: Double = 16.0

    /** A huge plot does not ask for thousands of buckets. */
    private const val MAX_TARGET_CANDLES: Double = 512.0

    /**
     * Switch to a finer rung when the current one shows fewer than this fraction
     * of the target candles (the candles are more than twice as wide as wanted).
     */
    private const val FINE_RATIO: Double = 0.5

    /**
     * Switch to a coarser rung when the current one shows more than this multiple
     * of the target candles. Together with [FINE_RATIO] the band is four times
     * wide, which is what keeps a two-level switch from bouncing.
     */
    private const val COARSE_RATIO: Double = 2.0

    /**
     * The rung for [spanMs] at [plotWidthPx] when there is no previous choice.
     * Null for a degenerate window.
     */
    fun preferred(spanMs: Double, plotWidthPx: Float): Int? {
        if (!spanMs.isFinite() || spanMs <= 0.0 || !plotWidthPx.isFinite() || plotWidthPx <= 0f) {
            return null
        }
        val targetCandles = targetCandles(plotWidthPx)
        val idealBucketMs = spanMs / targetCandles
        // Round to the nearest power of two: the bucket is the nearest ladder
        // rung to the density target, so the resulting candle count sits within
        // ~1.4x of it rather than always at the sparse side of a floor.
        val log2 = ln(idealBucketMs) / ln(2.0)
        return floor(log2 + 0.5).toInt().coerceIn(0, RatingHistoryTiles.MAX_TILE_LEVEL)
    }

    /**
     * The rung to request for a window.
     *
     * At all time the rung is the server's own choice
     * ([inego.zungjunggame.net.RatingHistoryPublication.allTimeLevel]): only the
     * parameterless answer knows the whole span, and asking for anything else
     * would throw away the one body that was built for exactly that window. The
     * server rung is still clamped to the tile-addressable ladder and to the
     * visible-tile ceiling.
     *
     * Otherwise a previous choice is kept until it leaves the hysteresis band;
     * only then does it jump to [preferred]. The result is always coarsened until
     * the window touches at most [RatingHistoryClient.MAX_VISIBLE_TILES] tiles.
     */
    fun select(
        previous: Int?,
        spanMs: Double,
        plotWidthPx: Float,
        atAllTime: Boolean,
        allTimeLevel: Int?,
        fromMs: Double,
        toMs: Double,
    ): Int? {
        if (!spanMs.isFinite() || spanMs <= 0.0) return previous
        if (atAllTime && allTimeLevel != null) {
            return tileClamped(
                allTimeLevel.coerceIn(0, RatingHistoryTiles.MAX_TILE_LEVEL),
                fromMs,
                toMs,
            )
        }
        val preferred = preferred(spanMs, plotWidthPx) ?: return previous
        val next = if (previous == null || previous !in 0..RatingHistoryTiles.MAX_TILE_LEVEL) {
            preferred
        } else {
            val currentCandles = spanMs / 2.0.pow(previous.toDouble())
            val target = targetCandles(plotWidthPx)
            when {
                currentCandles < target * FINE_RATIO -> preferred
                currentCandles > target * COARSE_RATIO -> preferred
                else -> previous
            }
        }
        return tileClamped(next, fromMs, toMs)
    }

    private fun targetCandles(plotWidthPx: Float): Double =
        ((plotWidthPx / TARGET_CANDLE_PX).toDouble())
            .coerceIn(MIN_TARGET_CANDLES, MAX_TARGET_CANDLES)

    /**
     * Coarsen [level] until the half-open window touches at most
     * [RatingHistoryClient.MAX_VISIBLE_TILES] canonical tiles. A rung at which E2
     * cannot hold the window is not a usable display rung, so this is a hard
     * ceiling rather than a preference.
     */
    private fun tileClamped(level: Int, fromMs: Double, toMs: Double): Int {
        if (!fromMs.isFinite() || !toMs.isFinite() || toMs <= fromMs) return level
        var rung = level.coerceIn(0, RatingHistoryTiles.MAX_TILE_LEVEL)
        val from = floor(fromMs).toLong()
        val to = ceil(toMs).toLong()
        while (rung < RatingHistoryTiles.MAX_TILE_LEVEL) {
            val run = RatingHistoryTiles.tilesCovering(rung, from, to)
            if (run.last - run.first + 1 <= RatingHistoryClient.MAX_VISIBLE_TILES) break
            rung += 1
        }
        return rung
    }
}
