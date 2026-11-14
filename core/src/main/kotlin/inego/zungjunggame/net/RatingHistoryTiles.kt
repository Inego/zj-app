package inego.zungjunggame.net

/**
 * The canonical rating-history tile grid: buckets `2^level` milliseconds wide and
 * tiles of [BUCKETS_PER_TILE] consecutive buckets.
 *
 * This is the client's **one** copy of the server's addressing rules
 * (the zj-server rating-history API contract). A request must name *canonical*
 * tiles — epoch-aligned, half-open, and nested by powers of two — because the
 * server validates the run against its own grid (`400` on a gap, a repeat or an
 * unrepresentable start), and an echoed tile is only meaningful where both sides
 * agree on where a bucket begins. Nothing else in the client may do this
 * arithmetic by hand.
 *
 * All values are integer milliseconds since the Unix epoch. [tileIndex] rounds
 * **down**, so an instant before 1970 (a negative millisecond) belongs to a
 * negative tile index rather than to tile 0.
 *
 * The contract's worked examples, which this mirrors (§12): at level 3 (`8` ms
 * buckets) tile `1660156250` holds `[1700000000000, 1700000001024)`; at level 4
 * (`16` ms buckets) tile `830078126` holds `[1700000002048, 1700000004096)`, and
 * the bucket holding `1700000003000` starts at `1700000002992`.
 */
object RatingHistoryTiles {
    /** One tile is this many consecutive buckets — the server's unit of coverage. */
    const val BUCKETS_PER_TILE: Int = 128

    /** A request names at most this many tiles: `4 * 128 = 512` bucket slots. */
    const val MAX_TILES_PER_REQUEST: Int = 4

    /** The ladder's finest rung, 1 ms buckets (`?level=0`). */
    const val MIN_LEVEL: Int = 0

    /** The ladder's coarsest rung the wire accepts (`?level=62`). */
    const val MAX_LEVEL: Int = 62

    /**
     * The coarsest rung whose **whole tile** still fits a signed 64-bit
     * millisecond span: `128 * 2^54 = 2^61` ms. Above it a tile's `start_ms`
     * leaves the representable domain (the contract documents the server's
     * saturation and its `400` for a start it cannot name), so the client cannot
     * address one. Real histories never come near this rung — today's epoch is
     * about `2^41` ms and a lifetime fits in a handful of `2^35`-ish buckets.
     */
    const val MAX_TILE_LEVEL: Int = 54

    /** The bucket width at [level], `2^level` milliseconds. */
    fun bucketMs(level: Int): Long {
        require(level in MIN_LEVEL..MAX_LEVEL) { "level $level is off the 0..=$MAX_LEVEL ladder" }
        return 1L shl level
    }

    /** The width of one whole tile at [level], `128 * 2^level` milliseconds. */
    fun tileSpanMs(level: Int): Long {
        require(level in MIN_LEVEL..MAX_TILE_LEVEL) {
            "level $level cannot be addressed as 128-bucket tiles"
        }
        return BUCKETS_PER_TILE.toLong() shl level
    }

    /**
     * The start of the bucket holding [epochMs] at [level] — the floor the server
     * uses for `bucket_start_ms` (the contract's `1700000002992` at level 4).
     */
    fun bucketStartMs(epochMs: Long, level: Int): Long {
        val width = bucketMs(level)
        return epochMs.floorDiv(width) * width
    }

    /**
     * The canonical tile index holding [epochMs] at [level]:
     * `floor(epochMs / (128 * 2^level))`.
     */
    fun tileIndex(epochMs: Long, level: Int): Long = epochMs.floorDiv(tileSpanMs(level))

    /** Tile [index]'s inclusive start, `index * 128 * 2^level`. */
    fun tileStartMs(index: Long, level: Int): Long = index * tileSpanMs(level)

    /** Tile [index]'s **exclusive** end. */
    fun tileEndMs(index: Long, level: Int): Long = tileStartMs(index, level) + tileSpanMs(level)

    /**
     * Every canonical tile the half-open span `[fromMs, toMs)` touches, ascending
     * and contiguous — the run a request for that window names. A degenerate span
     * (`toMs <= fromMs`) names the one tile holding [fromMs].
     */
    fun tilesCovering(level: Int, fromMs: Long, toMs: Long): LongRange {
        val first = tileIndex(fromMs, level)
        val last = if (toMs > fromMs) tileIndex(toMs - 1, level) else first
        return first..last
    }
}
