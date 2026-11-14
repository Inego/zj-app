package inego.zungjunggame.features.ratinghistory

import inego.zungjunggame.net.RatingCandle
import inego.zungjunggame.net.RatingCoverage
import inego.zungjunggame.net.RatingTileCoverage
import inego.zungjunggame.net.RatingTileStatus

/**
 * One level's retained coverage, prepared for painting and cached by the
 * component against [inego.zungjunggame.net.RatingHistoryClient.coverageVersion].
 *
 * E2's [RatingCoverage] already is the flattened draw list: tiles ascending, each
 * tile's candles ascending, with the tile's status and opening carry travelling
 * beside its candles. Building this wrapper is the only
 * per-version work; the frame path then binary-searches the retained tiles and the
 * candles inside each one, so a pan costs O(visible candles), never O(retained
 * candles), and a settled frame allocates nothing here.
 *
 * Nothing is merged between tiles and no candle is derived from another rung: a
 * series is always exactly one level's server candles. Carries are drawn from
 * [RatingTileCoverage.carryRating] and a candle's own close — piecewise-constant
 * rating values, never synthetic OHLC.
 */
class RatingChartSeries private constructor(
    val level: Int,
    val bucketMs: Long,
    val tiles: List<RatingTileCoverage>,
    /** True when at least one tile is [RatingTileStatus.Covered] (drawable). */
    val hasCoveredTiles: Boolean,
) {

    /** The first tile whose exclusive end is after [epochMs], or [tiles].size. */
    fun firstTileEndingAfter(epochMs: Double): Int {
        var low = 0
        var high = tiles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tiles[mid].endMs <= epochMs) low = mid + 1 else high = mid
        }
        return low
    }

    /** The last tile that starts before [epochMs], or -1. */
    fun lastTileStartingBefore(epochMs: Double): Int {
        var low = 0
        var high = tiles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tiles[mid].startMs < epochMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /**
     * The first candle in [candles] whose bucket may still overlap [fromMs]: its
     * start is at or after the bucket immediately before the window. The index is
     * [candles].size when nothing overlaps.
     */
    fun firstCandleOverlapping(candles: List<RatingCandle>, fromMs: Double): Int {
        val floorMs = fromMs - bucketMs + 1.0
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (candles[mid].startMs.toDouble() < floorMs) low = mid + 1 else high = mid
        }
        return low
    }

    /** The last candle in [candles] whose bucket starts before [toMs], or -1. */
    fun lastCandleStartingBefore(
        candles: List<RatingCandle>,
        toMs: Double,
    ): Int {
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (candles[mid].startMs.toDouble() < toMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /**
     * The candle nearest [epochMs]: the one whose bucket contains it, or the
     * closest one on either side within [toleranceMs]. Null when no candle is
     * that close — an empty stretch of history has no candle to name.
     *
     * The three tiles around the instant are searched, because the nearest
     * candle can sit in the tile before (the last one before a gap) or in the
     * tile after (the first one after a stretch of missing history). The
     * tolerance decides how far that may be.
     */
    fun candleNear(epochMs: Double, toleranceMs: Double): RatingCandle? {
        if (!epochMs.isFinite() || toleranceMs < 0.0) return null
        var index = firstTileEndingAfter(epochMs)
        if (index >= tiles.size) index = tiles.size - 1
        var best: RatingCandle? = null
        var bestDistance = Double.MAX_VALUE
        val first = maxOf(0, index - 1)
        val last = minOf(tiles.size - 1, index + 1)
        for (tileIndex in first..last) {
            val candles = tiles[tileIndex].candles
            if (candles.isEmpty()) continue
            // The first candle whose bucket has not ended before the instant.
            val at = firstCandleOverlapping(candles, epochMs)
            if (at < candles.size) {
                val candle = candles[at]
                val distance = maxOf(0.0, candle.startMs - epochMs)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candle
                }
            }
            if (at > 0) {
                val candle = candles[at - 1]
                val distance = maxOf(0.0, epochMs - (candle.startMs + bucketMs))
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candle
                }
            }
        }
        return if (best != null && bestDistance <= toleranceMs) best else null
    }

    /**
     * True when a candle this series **owns** and will paint overlaps
     * `[fromMs, toMs)`. Only a [RatingTileStatus.Covered] tile contributes; an
     * empty covered interval has no candle and therefore does not count.
     *
     * This is the loaded-empty test next to the loader's [inego.zungjunggame.net.RatingHistoryClient.holds]:
     * a held window with no candle in view is "no games in this range", not
     * "still loading".
     */
    fun hasCandleBetween(fromMs: Double, toMs: Double): Boolean {
        if (toMs <= fromMs) return false
        var index = firstTileEndingAfter(fromMs)
        val last = lastTileStartingBefore(toMs)
        while (index <= last) {
            val tile = tiles[index]
            if (tile.status == RatingTileStatus.Covered) {
                val first = firstCandleOverlapping(tile.candles, fromMs)
                val end = lastCandleStartingBefore(tile.candles, toMs)
                if (first <= end) return true
            }
            index++
        }
        return false
    }

    /**
     * True when a [RatingTileStatus.Covered] tile overlaps the window, so the
     * chart has something it may paint (a candle or a flat carry). Non-covered
     * statuses are deliberately excluded: they are missing history, not a flat
     * value.
     */
    fun hasCoveredTileBetween(fromMs: Double, toMs: Double): Boolean {
        if (toMs <= fromMs) return false
        var index = firstTileEndingAfter(fromMs)
        val last = lastTileStartingBefore(toMs)
        while (index <= last) {
            if (tiles[index].status == RatingTileStatus.Covered) return true
            index++
        }
        return false
    }

    companion object {
        /**
         * Wrap [coverage] for painting. This copies nothing: the tiles list is
         * E2's own immutable, ascending snapshot, and each tile owns its candles.
         */
        fun of(coverage: RatingCoverage): RatingChartSeries = RatingChartSeries(
            level = coverage.level,
            bucketMs = coverage.bucketMs,
            tiles = coverage.tiles,
            hasCoveredTiles = coverage.tiles.any { it.status == RatingTileStatus.Covered },
        )
    }
}
