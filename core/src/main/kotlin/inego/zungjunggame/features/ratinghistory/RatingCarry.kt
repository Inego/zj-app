package inego.zungjunggame.features.ratinghistory

/**
 * One flat stretch of the rating plot: the rating carried between two candles,
 * or from a tile edge to the first candle after it (and from the last candle to
 * the tile's end).
 *
 * [fromMs] is the end of the bucket before the stretch (the tile's own start when
 * no earlier candle is known) and [toMs] the next bucket's start (or the tile's
 * end). The rating is constant across the span.
 *
 * The span is **bucket-granular**: the wire carries neither the instant of the
 * last event in the bucket before nor the first event in the bucket after, so a
 * reader can name the span the line covers, not the exact instant the rating
 * changed. It is the same granularity a candle's time range has.
 *
 * [fromMs] and [toMs] are the span the line represents, not the clipped part a
 * particular window happens to show: a carry that runs off the screen still names
 * its full period.
 */
data class RatingCarry(
    val fromMs: Long,
    val toMs: Long,
    val rating: Double,
)
