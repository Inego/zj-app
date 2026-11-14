package inego.zungjunggame.net

/**
 * One retained tile at one level: what the server said about the tile and the
 * candles the client kept for it.
 *
 * [candles] is immutable and ascending by bucket start. A
 * [RatingTileStatus.Covered] tile with **no** candle is a *loaded-empty* interval
 * — the published history has no event in those buckets — so a renderer carries
 * [carryRating] flat across the whole tile and never invents a candle or a gap.
 * The other statuses are not emptiness: [RatingTileStatus.BeforeHistory] and
 * [RatingTileStatus.AfterHistory] are explicitly missing (draw nothing, the
 * publication's bounds say why), and [RatingTileStatus.Unavailable] means nothing
 * is published at all — [RatingHistoryClient.publication]'s status says which
 * absence it is.
 *
 * [carryRating] is the rating in effect immediately **before** this tile's first
 * bucket: the close of the last candle before it in the same response, or the
 * response's own carry (the baseline, or the published close past the end). It is
 * exact for every status, so a renderer never has to look at a neighbour to draw
 * the interval from the tile's start to its first candle.
 *
 * [epoch] and [publishedSequence] are the publication this tile's candles came
 * from. The owner uses the pair to reject a response that is older than what it
 * already holds, so a late answer can never overwrite newer mutable candles.
 */
class RatingTileCoverage(
    val level: Int,
    val index: Long,
    /** Inclusive start of the tile, as the server echoed it. */
    val startMs: Long,
    /** Exclusive end of the tile, as the server echoed it. */
    val endMs: Long,
    val status: RatingTileStatus,
    val candles: List<RatingCandle>,
    val carryRating: Double?,
    val epoch: Long,
    val publishedSequence: Long,
) {
    /**
     * The tile's share of the cache's byte ceiling: its immutable data plus an
     * estimate of the object headers and list slots that hold it. Deliberately
     * conservative — the ceiling bounds *parsed* candles, it is not a promise
     * about the JVM's or the Wasm heap's exact layout.
     */
    val bytes: Int get() = TILE_BYTES + candles.size * CANDLE_BYTES

    companion object {
        /** Fixed per-tile cost: coordinates, carry, provenance, and headers. */
        const val TILE_BYTES: Int = 256

        /**
         * Per-candle cost: four doubles, a start and a count (48 payload bytes)
         * plus the array slot and the object header.
         */
        const val CANDLE_BYTES: Int = 64
    }
}

/**
 * One level's retained coverage for the current target — what a renderer reads
 * every frame without waiting for the network.
 *
 * [tiles] is ascending by index and holds **only** what the client retained at
 * [level]; gaps are tiles that were never fetched or were evicted, which is why a
 * renderer asks [RatingHistoryClient.holds] before treating the heap as the whole
 * level. [loading] is true while a request for this level is in flight; the
 * parameterless all-time fetch has no level until it answers, so a chart watches
 * [RatingHistoryClient.publication] for that one.
 */
class RatingCoverage(
    val level: Int,
    /** The bucket width at [level], `2^level` ms. */
    val bucketMs: Long,
    val tiles: List<RatingTileCoverage>,
    val loading: Boolean,
) {
    /** True when nothing at this level is retained (a cold level, or one just cleared). */
    val isEmpty: Boolean get() = tiles.isEmpty()
}
