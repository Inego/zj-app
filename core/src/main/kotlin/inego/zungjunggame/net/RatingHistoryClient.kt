package inego.zungjunggame.net

import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * The rating-history chart's data owner: a byte-bounded store of fetched tiles
 * plus the scheduler that decides which tiles to ask for next.
 *
 * **Ownership.** The chart's *window* is the route's state and the tiles are this
 * owner's: server data that is large, regenerable, and meaningless in a persisted
 * stack snapshot. One instance serves one target at a time — opening a different
 * target discards the retained coverage — and it outlives a close/reopen of the
 * same chart, so coming back to a player costs no request.
 *
 * **What it answers.** [coverage] is the per-frame read: the tiles retained at one
 * level with their candles, carry and status, in ascending order, with no network
 * wait. [holds] answers "is this window fully answered", which is what a level
 * transition waits for. [publication] is the live document (the four-state status,
 * the all-time bounds and rung), and [error]/[retry] are
 * the recovery path. Nothing here refreshes on a timer or on a settlement: a
 * reading is taken when the chart opens, and reopening it takes another.
 *
 * **Scheduling.** [requestCoverage] is the only way tiles are asked for. A caller
 * pushes the visible rung and window every frame; the requests are *coalesced*
 * (the newest window wins) and the scheduler:
 *
 * - dispatches at most one request per [DISPATCH_INTERVAL_MS] while a gesture is
 *   in motion (`immediate = false`), and fills every free slot at once when the
 *   caller reports a settle (`immediate = true`), which latches until a new
 *   non-immediate request arrives;
 * - keeps at most [MAX_IN_FLIGHT] requests open, and never asks for a tile that is
 *   in flight, retained, or inside its retry backoff;
 * - prefetches one neighbouring tile on each side, but only into the published
 *   span: a tile entirely before or after the history is a documented
 *   `before_history`/`after_history` answer with nothing to draw, so it is not
 *   worth a request;
 * - never requests more than [MAX_VISIBLE_TILES] visible tiles for one window: a
 *   rung that would need more is too fine for the plot, and the caller's level
 *   choice is what keeps that from happening.
 *
 * **Replacement is atomic.** A response replaces whole tile entries, never
 * splices its candles into an older list. A body from an **older epoch** is
 * discarded wholesale — a rebuild renumbers every candle of the account — and a
 * newer epoch drops everything retained. Within one epoch the pair
 * `(epoch, published_sequence)` is monotonic per tile, so a response that arrives
 * late can never overwrite newer mutable candles; a stale watermark also never
 * regresses [publication]. The parameterless answer's collection is replaced as a
 * whole when its rung coarsens, so two resolutions are never merged.
 *
 * **Threading.** Everything here runs on the libGDX application thread: the
 * scheduler is driven from the frame, and [RatingHistoryApi] posts its callbacks
 * there. Nothing blocks, nothing is locked, and a response that lands after its
 * reading ended is dropped by generation rather than applied to a newer one.
 *
 * **Timeouts and cancellation.** Every dispatched request records the monotonic
 * time it was sent. A request that outlives [REQUEST_DEADLINE_MS] is retired (its
 * slot released, its tiles put behind the retry backoff, [error] set to
 * [RatingHistoryFailure.Timeout]) and cancelled through its handle. The web
 * backend's transport never times a request out and reports no failure for a
 * request that hangs, so that deadline is the only recovery there; where the
 * platform does enforce `timeOut`, its own, earlier failure is classified by
 * [RatingHistoryApi] and the deadline is just the backstop. Cancelling is best
 * effort: a late response is dropped whether or not the platform managed to stop
 * it.
 */
class RatingHistoryClient(
    private val api: RatingHistorySender = RatingHistoryApi,
    /**
     * The access token a [RatingHistoryTarget.Self] request carries. A public
     * player read never sends it. Read at send time, so a rotated token is used
     * by the next request without re-opening the chart.
     */
    private val token: () -> String? = { null },
    /** Monotonic milliseconds; injectable so a harness can drive the throttle. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Parsed-candle ceiling; see [RatingTileCoverage.bytes]. */
    private val byteBudget: Int = DEFAULT_BYTE_BUDGET,
) : Disposable {

    /** Whose history is loaded; null before [open] and after [clear]. */
    var target: RatingHistoryTarget? = null
        private set

    /** The newest publication the target's server documents described. */
    var publication: RatingHistoryPublication? = null
        private set

    /** True while any request of the current reading is open. */
    var loading: Boolean = false
        private set

    /**
     * Milliseconds until the earliest open request's deadline retires it, or null
     * when nothing is in flight.
     *
     * A chart with no gesture, inertia or easing uses this to schedule one frame
     * at the deadline instead of presenting continuously; the retirement in
     * [tick] is the web backend's only failure signal.
     */
    val nextDeadlineInMs: Long?
        get() {
            if (inFlight.isEmpty()) return null
            val now = nowMs()
            return inFlight.minOf { it.dispatchedAtMs + REQUEST_DEADLINE_MS - now }
                .coerceAtLeast(0L)
        }

    /** The newest failure of the current reading, or null after a successful one. */
    var error: RatingHistoryFailure? = null
        private set

    /**
     * Bumped whenever the retained coverage or [publication] changes. A renderer
     * that caches a flattened candle list compares this instead of diffing tiles.
     */
    var coverageVersion: Long = 0L
        private set

    /**
     * A `401` from this endpoint is the same rejected session every signed-in read
     * reports, so it runs the account lifecycle's one refresh path. The credential
     * the refused request presented rides along: a session that has already been
     * rotated away from it re-reads instead of rotating again. Wired by the
     * composition root; a public player read never sends a credential.
     */
    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    // ── Reading generation ───────────────────────────────────────────────────

    /**
     * The open's generation. Every request captures it, and a response from a
     * different generation is dropped: the chart closed, reopened for another
     * target, or cleared while it was in flight.
     */
    private var generation = 0

    private val entries = HashMap<TileKey, RatingTileCoverage>()
    private val lastUsed = HashMap<TileKey, Long>()
    private var usedBytes = 0
    private var useStamp = 0L

    /** The tiles the newest all-time answer named; replaced as one collection. */
    private var allTimeKeys: Set<TileKey> = emptySet()

    /** One stored tile's identity: its rung and its canonical index. */
    private data class TileKey(val level: Int, val index: Long)

    // ── Scheduler state ──────────────────────────────────────────────────────

    private class Desired(val level: Int, val visible: LongRange)

    private class InFlight(
        val generation: Int,
        /** The requested level, or null for the parameterless all-time answer. */
        val level: Int?,
        val tiles: List<Long>,
        val dispatchedAtMs: Long,
        /** The Bearer sent, so a `401` can be told from a stale credential. */
        val credential: String?,
    ) {
        var handle: RatingHistoryHandle = RatingHistoryHandle.None
    }

    private class Retry(val attempts: Int, val untilMs: Long)

    private var desired: Desired? = null
    private val inFlight = ArrayList<InFlight>()

    /** Failed tiles and when they may be asked for again. Bounded by [MAX_RETRY_ENTRIES]. */
    private val retries = HashMap<TileKey, Retry>()
    private var lastDispatchMs = Long.MIN_VALUE / 2
    /** The last viewport request was a settle: a freed slot is refilled at once. */
    private var settled = false

    // ── Opening, reading, recovering ─────────────────────────────────────────

    /**
     * Open [target]'s history: the parameterless all-time answer is fetched
     * immediately, because it is the only body that names the server-chosen rung
     * and the bounds a viewport is built from.
     *
     * A different target discards the retained coverage (one reading at a time); the
     * same target keeps it, so a close and reopen draws from the cache while the
     * fresh answer replaces the collection. Every open supersedes what was in
     * flight — starting a reading **is** superseding it, so a caller cannot forget
     * to invalidate first — and forgets the previous reading's failed tiles: a new
     * reading is a new attempt, not an inherited backoff. That is what makes a
     * re-read under a rotated token actually ask again.
     */
    fun open(target: RatingHistoryTarget) {
        generation += 1
        abandon()
        if (this.target != target) {
            this.target = target
            clearCoverage()
            publication = null
        }
        retries.clear()
        desired = null
        error = null
        settled = true
        loadAllTime(nowMs())
    }

    /**
     * Push the window the chart shows and let the scheduler fill it.
     *
     * [fromMs] and [toMs] are epoch milliseconds of the half-open visible span —
     * in practice `ChartViewport.fromEpochMs`/`toEpochMs` over the plot rect
     * `RatingHistoryOverlay.measure(...).plot`. [level] is the rung the caller chose.
     * Passing `immediate = false` while a gesture is in motion coalesces this call
     * with the rest of the gesture; `immediate = true` on the settle asks for the
     * final coverage without waiting for the throttle.
     *
     * A degenerate span and a request before [open] do nothing. Retained tiles are
     * never re-requested: this fills what the window needs.
     */
    fun requestCoverage(level: Int, fromMs: Long, toMs: Long, immediate: Boolean = false) {
        if (target == null) return
        require(level in RatingHistoryTiles.MIN_LEVEL..RatingHistoryTiles.MAX_TILE_LEVEL) {
            "level $level cannot be addressed as 128-bucket tiles"
        }
        if (toMs <= fromMs) return
        val visible = visibleRun(level, fromMs, toMs)
        desired = Desired(level, visible)
        settled = immediate
        pump(nowMs(), immediate)
    }

    /**
     * The retained coverage of [level], ascending, with no network wait.
     *
     * Reading also retires a request that has outlived its deadline, so a chart
     * that is being drawn but not gestured still recovers from a request the web
     * backend never failed. The read itself never trims the store: eviction is
     * driven by the budget, and the visible run of the last request is what is
     * pinned against it.
     */
    fun coverage(level: Int): RatingCoverage {
        retireStalled(nowMs())
        val tiles = entries.values.filter { it.level == level }.sortedBy { it.index }
        return RatingCoverage(
            level = level,
            bucketMs = RatingHistoryTiles.bucketMs(level),
            tiles = tiles,
            loading = inFlight.any { it.level == level },
        )
    }

    /**
     * True when every canonical tile the half-open span touches is retained at
     * [level] — including tiles the server marked `before_history`,
     * `after_history` or `unavailable`, which are answers rather than gaps.
     *
     * False for a span that would need more than [MAX_VISIBLE_TILES] tiles: such a
     * rung is too fine for a plot and the scheduler never fills one run of that
     * length, so it is not "held" in any useful sense.
     */
    fun holds(level: Int, fromMs: Long, toMs: Long): Boolean {
        if (target == null || toMs <= fromMs) return false
        val run = RatingHistoryTiles.tilesCovering(level, fromMs, toMs)
        if (run.last - run.first + 1 > MAX_VISIBLE_TILES) return false
        for (index in run) if (!entries.containsKey(TileKey(level, index))) return false
        return true
    }

    /**
     * Advance the owner's own clock: retire a request that outlived
     * [REQUEST_DEADLINE_MS], release its slot and put its tiles behind the retry
     * backoff.
     *
     * A chart that is only being drawn — no gesture, no viewport change — calls
     * this once per presented frame. [requestCoverage] and [coverage] retire
     * stalled requests themselves, so a caller that drives the scheduler
     * every frame never needs this; one that draws a still chart does, because
     * the web backend never fails a request on its own and the deadline is the
     * only thing that can turn a hung request into a visible error.
     */
    fun tick() {
        retireStalled(nowMs())
    }

    /**
     * Retry after a failure: forget the backoff and the error, then ask again.
     *
     * If the all-time answer never landed, it is fetched again (nothing can be
     * scoped without its bounds). Otherwise the current window's missing tiles are
     * dispatched immediately, as a settle would.
     */
    fun retry() {
        if (target == null) return
        error = null
        retries.clear()
        val now = nowMs()
        retireStalled(now)
        if (publication == null && inFlight.none { it.level == null }) {
            loadAllTime(now)
        }
        pump(now, immediate = true)
    }

    /**
     * Drop the reading and every request still in flight; the owner stays usable.
     */
    fun clear() {
        generation += 1
        abandon()
        target = null
        publication = null
        desired = null
        settled = false
        error = null
        retries.clear()
        clearCoverage()
    }

    /** A closing scope must not leave a response to land on a dead chart. */
    override fun dispose() {
        clear()
    }

    // ── Response handling ────────────────────────────────────────────────────

    private fun loadAllTime(now: Long): Boolean {
        val target = target ?: return false
        if (inFlight.size >= MAX_IN_FLIGHT) return false
        dispatch(RatingHistoryQuery.AllTime(target), level = null, tiles = emptyList(), now = now)
        return true
    }

    private fun dispatch(query: RatingHistoryQuery, level: Int?, tiles: List<Long>, now: Long) {
        val credential = if (query.target == RatingHistoryTarget.Self) token() else null
        val request = InFlight(generation, level, tiles, now, credential)
        inFlight += request
        lastDispatchMs = now
        loading = true
        val handle = api.fetch(query, credential) { result -> onResult(request, result, nowMs()) }
        request.handle = handle
    }

    private fun onResult(request: InFlight, result: Result<RatingHistoryPage>, now: Long) {
        if (request.generation != generation) return
        if (!inFlight.remove(request)) return
        val page = result.getOrNull()
        val failure = result.exceptionOrNull()
        if (page != null) {
            if (applyPage(page, allTime = request.level == null)) error = null
        } else {
            fail(request, failure as? RatingHistoryFailure ?: transportFailure(failure), now)
        }
        loading = inFlight.isNotEmpty()
        // A freed slot is refilled now on a settled chart, and by the next frame's
        // request during a gesture (the throttle owns that cadence).
        pump(now, settled)
    }

    /**
     * Apply one body, or reject it. True when the body was applied — a body from
     * an older epoch is discarded wholesale, tiles and metadata both.
     */
    private fun applyPage(page: RatingHistoryPage, allTime: Boolean): Boolean {
        val held = publication
        val heldEpoch = held?.epoch
        val pageEpoch = page.epoch
        if (pageEpoch == null && heldEpoch != null) return false
        if (pageEpoch != null && heldEpoch != null && pageEpoch < heldEpoch) return false

        // A rebuild changes every candle of the account, so nothing retained survives it.
        if (pageEpoch != null && (heldEpoch == null || pageEpoch > heldEpoch)) {
            clearCoverage()
            retries.clear()
        }

        val pageSeq = page.publishedSequence ?: -1L
        val heldSeq = held?.publishedSequence ?: -1L
        val newerMetadata = when {
            held == null -> true
            pageEpoch == null -> heldEpoch == null
            heldEpoch == null -> true
            pageEpoch != heldEpoch -> pageEpoch > heldEpoch
            else -> pageSeq >= heldSeq
        }
        if (newerMetadata) {
            publication = page.toPublication(allTime, held)
            bumpCoverage()
        }

        val resolution = page.resolution ?: return true
        val epoch = page.epoch ?: return true
        val sequence = page.publishedSequence ?: 0L
        storeTiles(page, resolution.level, epoch, sequence, replaceAllTime = allTime && newerMetadata)
        evictIfNeeded()
        return true
    }

    /**
     * Store one body's tiles, splitting its candles by tile and deriving each
     * tile's carry from the response itself: the first tile carries the body's
     * `carry_rating` (the baseline, or the published close past the end) and every
     * later tile carries the previous tile's last close.
     *
     * A tile whose retained entry is at least as new as this body is kept; the
     * body's other tiles are still stored, because a stale watermark only speaks
     * for the candles it actually carries.
     */
    private fun storeTiles(
        page: RatingHistoryPage,
        level: Int,
        epoch: Long,
        sequence: Long,
        replaceAllTime: Boolean,
    ) {
        val keys = ArrayList<TileKey>(page.tiles.size)
        var carry = page.carryRating
        var cursor = 0
        val candles = page.candles
        for (tile in page.tiles) {
            // Candles are ascending and a bucket never straddles a tile boundary,
            // so one cursor serves the whole run.
            while (cursor < candles.size && candles[cursor].startMs < tile.startMs) cursor += 1
            val slice = ArrayList<RatingCandle>()
            while (cursor < candles.size && candles[cursor].startMs < tile.endMs) {
                slice += candles[cursor]
                cursor += 1
            }
            val key = TileKey(level, tile.index)
            keys += key
            val retained = entries[key]
            if (retained == null || !olderOrEqual(retained, epoch, sequence)) {
                put(
                    RatingTileCoverage(
                        level = level,
                        index = tile.index,
                        startMs = tile.startMs,
                        endMs = tile.endMs,
                        status = tile.status,
                        candles = slice,
                        carryRating = carry,
                        epoch = epoch,
                        publishedSequence = sequence,
                    ),
                )
            }
            if (slice.isNotEmpty()) carry = slice.last().close
        }
        if (replaceAllTime) {
            for (stale in allTimeKeys) if (stale !in keys) remove(stale)
            allTimeKeys = keys.toSet()
        }
    }

    /** True when [retained] is at least as new as the incoming `(epoch, sequence)`. */
    private fun olderOrEqual(retained: RatingTileCoverage, epoch: Long, sequence: Long): Boolean =
        if (retained.epoch != epoch) retained.epoch > epoch else retained.publishedSequence >= sequence

    private fun RatingHistoryPage.toPublication(
        allTime: Boolean,
        previous: RatingHistoryPublication?,
    ): RatingHistoryPublication = RatingHistoryPublication(
        state = state,
        displayName = displayName,
        epoch = epoch,
        publishedSequence = publishedSequence,
        eventCount = eventCount,
        initialRating = initialRating,
        lastRating = lastRating,
        firstEventAt = firstEventAt,
        lastEventAt = lastEventAt,
        // Only the parameterless answer names the server-picked rung; an explicit
        // request echoes the one it was asked for.
        allTimeLevel = if (allTime) resolution?.level else previous?.allTimeLevel,
    )

    private fun fail(request: InFlight, failure: RatingHistoryFailure, now: Long) {
        // Record the failure before reporting it: the account owner may re-read this
        // chart synchronously (a `401` for a token it has already replaced), and the
        // new reading's state must be the one that stands.
        error = failure
        val level = request.level
        if (level != null) {
            for (index in request.tiles) {
                val key = TileKey(level, index)
                val attempts = (retries[key]?.attempts ?: 0) + 1
                val until = if (failure.retryable) now + backoffMs(attempts) else Long.MAX_VALUE
                rememberRetry(key, Retry(attempts, until))
            }
        }
        if (failure is RatingHistoryFailure.Unauthorized) onUnauthorized?.invoke(request.credential)
    }

    private fun rememberRetry(key: TileKey, retry: Retry) {
        if (retries.size >= MAX_RETRY_ENTRIES && key !in retries) {
            retries.remove(retries.keys.first())
        }
        retries[key] = retry
    }

    private fun backoffMs(attempts: Int): Long =
        if (attempts >= MAX_BACKOFF_DOUBLINGS) {
            MAX_BACKOFF_MS
        } else {
            (BASE_BACKOFF_MS shl (attempts - 1)).coerceAtMost(MAX_BACKOFF_MS)
        }

    private fun transportFailure(cause: Throwable?): RatingHistoryFailure =
        RatingHistoryFailure.Network(cause?.message ?: "Rating history request failed", cause)

    // ── Dispatching ──────────────────────────────────────────────────────────

    /**
     * Fill the free slots from [desired]: one request per call while throttled,
     * every free slot on a settle. Tiles are considered in ascending order starting
     * at the earlier prefetch margin, so a contiguous run (including that margin)
     * becomes one request.
     */
    private fun pump(now: Long, immediate: Boolean) {
        retireStalled(now)
        val wanted = desired ?: return
        val free = MAX_IN_FLIGHT - inFlight.size
        if (free <= 0) return
        if (!immediate && now - lastDispatchMs < DISPATCH_INTERVAL_MS) return
        val missing = missingTiles(wanted, now)
        if (missing.isEmpty()) return
        var slots = if (immediate) free else 1
        for (batch in batches(missing)) {
            if (slots == 0) return
            val target = target ?: return
            dispatch(
                RatingHistoryQuery.Tiles(target, wanted.level, batch),
                level = wanted.level,
                tiles = batch,
                now = now,
            )
            slots -= 1
        }
    }

    /**
     * The tiles [wanted] needs, in request order: the earlier prefetch margin, the
     * visible run, then the later margin. A tile already retained, already in
     * flight, or still backing off is not missing.
     */
    private fun missingTiles(wanted: Desired, now: Long): List<Long> {
        val claimed = HashSet<TileKey>()
        for (request in inFlight) {
            val level = request.level ?: continue
            for (index in request.tiles) claimed += TileKey(level, index)
        }
        val out = ArrayList<Long>()
        val earlierMargin = wanted.visible.first - 1
        if (prefetchable(wanted.level, earlierMargin)) consider(wanted, earlierMargin, claimed, now, out)
        for (index in wanted.visible) consider(wanted, index, claimed, now, out)
        val laterMargin = wanted.visible.last + 1
        if (prefetchable(wanted.level, laterMargin)) consider(wanted, laterMargin, claimed, now, out)
        return out
    }

    private fun consider(
        wanted: Desired,
        index: Long,
        claimed: Set<TileKey>,
        now: Long,
        out: MutableList<Long>,
    ) {
        val key = TileKey(wanted.level, index)
        if (entries.containsKey(key) || key in claimed) return
        val retry = retries[key]
        if (retry != null && now < retry.untilMs) return
        out += index
    }

    /**
     * True when [index]'s tile at [level] intersects the published history. A tile
     * entirely before or after it is a documented "nothing to draw" answer, so the
     * one-tile margin is only worth a request inside the span. Without bounds (no
     * publication yet, or a non-ready account) nothing is a useful margin.
     */
    private fun prefetchable(level: Int, index: Long): Boolean {
        val live = publication ?: return false
        if (live.state != RatingHistoryStatus.Ready) return false
        val first = live.firstEventAt ?: return false
        val last = live.lastEventAt ?: return false
        val start = RatingHistoryTiles.tileStartMs(index, level)
        val end = RatingHistoryTiles.tileEndMs(index, level)
        return start <= last && first < end
    }

    /** Split [tiles] (ascending) into maximal contiguous runs of at most four. */
    private fun batches(tiles: List<Long>): List<List<Long>> {
        val out = ArrayList<List<Long>>()
        var index = 0
        while (index < tiles.size) {
            val run = ArrayList<Long>(RatingHistoryTiles.MAX_TILES_PER_REQUEST)
            run += tiles[index]
            index += 1
            while (index < tiles.size &&
                run.size < RatingHistoryTiles.MAX_TILES_PER_REQUEST &&
                tiles[index] == run.last() + 1
            ) {
                run += tiles[index]
                index += 1
            }
            out += run
        }
        return out
    }

    private fun visibleRun(level: Int, fromMs: Long, toMs: Long): LongRange {
        val run = RatingHistoryTiles.tilesCovering(level, fromMs, toMs)
        val count = run.last - run.first + 1
        if (count <= MAX_VISIBLE_TILES) return run
        return run.first..(run.first + MAX_VISIBLE_TILES - 1)
    }

    // ── Deadlines ────────────────────────────────────────────────────────────

    private fun retireStalled(now: Long) {
        if (inFlight.isEmpty()) return
        val retired = ArrayList<InFlight>()
        val iterator = inFlight.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (now - request.dispatchedAtMs >= REQUEST_DEADLINE_MS) {
                iterator.remove()
                retired += request
            }
        }
        if (retired.isEmpty()) return
        for (request in retired) {
            request.handle.cancel()
            fail(
                request,
                RatingHistoryFailure.Timeout("Rating history request timed out"),
                now,
            )
        }
        loading = inFlight.isNotEmpty()
    }

    private fun abandon() {
        val requests = inFlight.toList()
        inFlight.clear()
        loading = false
        for (request in requests) request.handle.cancel()
    }

    // ── Retained coverage ────────────────────────────────────────────────────

    private fun put(entry: RatingTileCoverage) {
        val key = TileKey(entry.level, entry.index)
        entries.put(key, entry)?.let { usedBytes -= it.bytes }
        usedBytes += entry.bytes
        lastUsed[key] = ++useStamp
        bumpCoverage()
    }

    private fun remove(key: TileKey) {
        val removed = entries.remove(key) ?: return
        usedBytes -= removed.bytes
        lastUsed.remove(key)
        bumpCoverage()
    }

    private fun clearCoverage() {
        entries.clear()
        lastUsed.clear()
        allTimeKeys = emptySet()
        usedBytes = 0
        bumpCoverage()
    }

    /**
     * Evict least-recently-requested tiles until the store fits [byteBudget].
     *
     * The visible run of the last viewport request is pinned first, because a
     * gap under the plot is visible while a gap in a retained coarse level or a
     * prefetched neighbour is not. If the pinned run alone exceeds the budget it
     * is evicted too, oldest first: the ceiling is the promise, and the next frame
     * re-requests what the plot lost.
     */
    private fun evictIfNeeded() {
        if (usedBytes <= byteBudget) return
        val pinned = desired?.let { wanted ->
            wanted.visible.mapTo(HashSet()) { TileKey(wanted.level, it) }
        } ?: emptySet()
        while (usedBytes > byteBudget && entries.isNotEmpty()) {
            val victim = entries.keys.asSequence()
                .filter { it !in pinned }
                .minByOrNull { lastUsed[it] ?: 0L }
                ?: entries.keys.minByOrNull { lastUsed[it] ?: 0L }
                ?: return
            remove(victim)
        }
    }

    private fun bumpCoverage() {
        coverageVersion += 1
    }

    companion object {
        /** The default parsed-candle ceiling: 4 MiB, about 125 full 512-candle responses. */
        const val DEFAULT_BYTE_BUDGET: Int = 4 * 1024 * 1024

        /** Dispatch at most one request per this interval while a gesture is moving. */
        const val DISPATCH_INTERVAL_MS: Long = 150L

        /** The scheduler's open-request ceiling, the all-time answer included. */
        const val MAX_IN_FLIGHT: Int = 2

        /**
         * How long a dispatched request may stay open before the owner retires it.
         * Matches [RatingHistoryApi.TIMEOUT_MS]: the platform's own timeout reports
         * first where it exists, and this is the web backend's only deadline.
         */
        const val REQUEST_DEADLINE_MS: Long = RatingHistoryApi.TIMEOUT_MS

        /** A window wider than this many tiles at one level is too fine to fill as a run. */
        const val MAX_VISIBLE_TILES: Int = 8

        private const val BASE_BACKOFF_MS: Long = 2_000L
        private const val MAX_BACKOFF_MS: Long = 30_000L
        private const val MAX_BACKOFF_DOUBLINGS: Int = 8
        private const val MAX_RETRY_ENTRIES: Int = 128
    }
}
