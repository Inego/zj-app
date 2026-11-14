package inego.zungjunggame.net

import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * The four mutually exclusive states the server publishes for a history (the
 * zj-server rating-history API contract) — the client never derives one from the
 * candlesticks, because the three empty-looking states mean different things:
 *
 * - [Ready]: candles are served (and more may be settling).
 * - [Empty]: the published history is complete and holds no event at all —
 *   "no games yet".
 * - [Pending]: *publication lag*, not emptiness — a settlement opened the epoch
 *   but the worker has not folded it yet; the player has played.
 * - [NotReady]: nothing is published for the account yet (a fresh account, or one
 *   waiting for the backfill) — never "no games".
 */
enum class RatingHistoryStatus { Ready, Empty, Pending, NotReady }

/**
 * What the server says about one echoed tile (§7).
 *
 * [Covered] with no candle is a **loaded-empty** interval (the published history
 * has no event there) and a renderer carries the rating flat across it.
 * [BeforeHistory] and [AfterHistory] are explicitly *missing*, not empty: nothing
 * is drawn into them. [Unavailable] means nothing is published at all — the
 * response's [RatingHistoryStatus] says which absence it is.
 */
enum class RatingTileStatus { Covered, BeforeHistory, AfterHistory, Unavailable }

/**
 * One rating candle: the compact wire tuple
 * `[bucket_start_ms, open, high, low, close, count]`.
 *
 * [open] is the rating immediately before the bucket's first event and [close]
 * the rating after its last; [high]/[low] include [open] and every event's
 * before- and after-value, so a spike survives a zoom-out. [count] is the number
 * of events, so two settlements in one millisecond remain two events.
 *
 * Values keep full precision: no field is rounded for display here.
 */
data class RatingCandle(
    val startMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val count: Long,
)

/** The rung a response's candles are addressed at, as the server echoed it. */
data class RatingHistoryLevel(
    /** `0..=62`; the bucket width is `2^level` ms. */
    val level: Int,
    /** The echoed width, `2^level` ms. */
    val bucketMs: Long,
)

/** One echoed tile: its canonical index, its real aligned bounds, and its status. */
data class RatingHistoryTile(
    val index: Long,
    /** Inclusive start, never clipped to the history. */
    val startMs: Long,
    /** Exclusive end, never clipped to the history. */
    val endMs: Long,
    val status: RatingTileStatus,
)

/**
 * One parsed rating-history document — the body of either endpoint in either
 * request form.
 *
 * [epoch] and [publishedSequence] are the wire's decimal **strings** parsed into
 * exact `Long`s: they are 64-bit revisions, so nothing here ever passes through a
 * `Double`. [epoch] is null exactly when nothing is published (`not_ready`), and
 * [eventCount] is null in the same body (the contract's `not_ready` example),
 * which is why it is nullable even though published accounts always carry it.
 *
 * [tiles] is ascending and contiguous, one to four entries for a parameterless
 * answer or for an explicit request; [candles] is ascending and **sparse** — an
 * absent bucket inside a [RatingTileStatus.Covered] tile had no published event.
 * [carryRating] is the rating immediately before the response's first bucket.
 */
data class RatingHistoryPage(
    val state: RatingHistoryStatus,
    val displayName: String,
    val epoch: Long?,
    val publishedSequence: Long?,
    val eventCount: Long?,
    val initialRating: Double?,
    val lastRating: Double?,
    val firstEventAt: Long?,
    val lastEventAt: Long?,
    val resolution: RatingHistoryLevel?,
    val tiles: List<RatingHistoryTile>,
    val carryRating: Double?,
    val candles: List<RatingCandle>,
)

/**
 * The live publication behind a chart, independent of which tiles are retained:
 * the four-state status and the bounds an all-time viewport is built from.
 *
 * [allTimeLevel] is the rung the **parameterless** answer named, which is the one
 * rung only the server can choose (§5) and which can coarsen as the span grows
 * (§9). An explicit tile request echoes the rung it was asked for, so it never
 * overwrites this value: a renderer keeps opening on the server-picked rung while
 * it asks for any other.
 */
data class RatingHistoryPublication(
    val state: RatingHistoryStatus,
    val displayName: String,
    val epoch: Long?,
    val publishedSequence: Long?,
    val eventCount: Long?,
    val initialRating: Double?,
    val lastRating: Double?,
    val firstEventAt: Long?,
    val lastEventAt: Long?,
    val allTimeLevel: Int?,
)

/**
 * One rating-history request: exactly the two forms the endpoint accepts (§2).
 *
 * The ambiguous shape — `level` without `tiles`, or the reverse — is a `400`
 * there, so it is unrepresentable here: a caller either asks for the whole
 * history at the server's own rung ([AllTime]) or names a rung and a canonical
 * run ([Tiles]).
 */
sealed interface RatingHistoryQuery {
    val target: RatingHistoryTarget

    /** No parameters: the whole published history at the rung the server picks. */
    data class AllTime(override val target: RatingHistoryTarget) : RatingHistoryQuery

    /**
     * `?level=<level>&tiles=<t1>,<t2>,...`: one to four **ascending contiguous**
     * canonical tile indices at [level]. The run is checked here because a gap, a
     * repeat or a fifth tile is a `400` on the wire and would only hide a
     * scheduler bug behind an error state.
     */
    data class Tiles(
        override val target: RatingHistoryTarget,
        val level: Int,
        val tiles: List<Long>,
    ) : RatingHistoryQuery {
        init {
            require(level in RatingHistoryTiles.MIN_LEVEL..RatingHistoryTiles.MAX_TILE_LEVEL) {
                "level $level cannot be addressed as tiles"
            }
            require(tiles.size in 1..RatingHistoryTiles.MAX_TILES_PER_REQUEST) {
                "a request carries 1..${RatingHistoryTiles.MAX_TILES_PER_REQUEST} tiles, got ${tiles.size}"
            }
            require(tiles.zipWithNext().all { (earlier, later) -> later == earlier + 1 }) {
                "tiles must be an ascending contiguous run: $tiles"
            }
        }
    }
}

/**
 * A typed rating-history failure: the wire's verdicts, not a message to parse.
 *
 * The transport maps statuses and transport failures onto these once; every
 * caller reads the kind. [retryable] says whether sending **the same request
 * again** can plausibly succeed — a [Rejected] request is the client's own bug, a
 * [NotFound] handle is absent, unlisted or banned and stays so, and an
 * [Unreadable] body will not parse a second time either.
 */
sealed class RatingHistoryFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** `401`: the session is not a valid, enabled one; the auth lifecycle refreshes. */
    class Unauthorized(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** `404` on a public handle: absent, unlisted and banned are deliberately identical. */
    class NotFound(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** `400`: the request is malformed (an off-ladder level, a bad tile run). */
    class Rejected(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** The server faulted (`500`, or a `429`/`503` once C3 adds them): back off and retry. */
    class Server(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** The request outlived its deadline without an answer. */
    class Timeout(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** The transport failed before the deadline (no route, DNS, a dropped connection). */
    class Network(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** A `200` whose body does not match the contract. */
    class Unreadable(message: String, cause: Throwable? = null) : RatingHistoryFailure(message, cause)

    /** Sending this same request again could succeed. */
    val retryable: Boolean
        get() = this !is NotFound && this !is Rejected && this !is Unreadable
}
