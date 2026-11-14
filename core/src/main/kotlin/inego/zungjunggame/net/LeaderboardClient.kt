package inego.zungjunggame.net

import com.badlogic.gdx.utils.Disposable

/**
 * In-memory leaderboard for the hub's Leaderboard card.
 *
 * One [reading] at a time: opening fetches the first page of the newest snapshot
 * and pins its generation. Scrolling then fetches further windows of that same
 * generation and adds them to the reading; a response that says the generation was
 * replaced drops the cached windows and `self` and starts the reading over. No Gdx
 * app required when [api] is injected.
 */
class LeaderboardClient(
    private val api: LeaderboardSender = LeaderboardApi,
) : Disposable {
    var reading: LeaderboardReading? = null
        private set
    var loading: Boolean = false
        private set
    var error: String? = null
        private set

    /** The first page's rows of the current reading; empty before one lands. */
    val rows: List<LeaderboardRow> get() = reading?.rows.orEmpty()

    /** The viewer's own row in the reading, or null when not listed or anonymous. */
    val self: LeaderboardSelf? get() = reading?.self

    val total: Int get() = reading?.total ?: 0

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    private var gen = 0

    /**
     * The window a fetch already failed for. Scrolling re-reports the same missing
     * window every frame, so remembering the failure keeps one bad window from
     * hammering the server; a different window (or a new reading) clears it.
     */
    private var failedWindow: Int? = null

    /** Drop the reading and ignore any response still in flight. */
    fun clear() {
        gen += 1
        reading = null
        loading = false
        error = null
        failedWindow = null
    }

    /**
     * Fetch the first page of a new reading. [token] is optional: a signed-out
     * player gets the same list with a null `self`.
     */
    fun loadFirstPage(token: String?) {
        val request = ++gen
        loading = true
        error = null
        failedWindow = null
        api.list(token = token, generation = null, limit = PAGE_SIZE, offset = 0) { result ->
            if (request != gen) return@list
            loading = false
            result.fold(
                onSuccess = {
                    reading = it.toReading()
                    error = null
                },
                onFailure = { err ->
                    error = err.message
                    if ((err as? LeaderboardException)?.unauthorized == true) {
                        onUnauthorized?.invoke(token)
                    }
                },
            )
        }
    }

    /**
     * Fetch the window of the pinned reading that starts at [offset]. No-op when
     * the window is already held, a fetch is in flight, or this window already
     * failed.
     *
     * A response with `replaced: true`, or one whose generation is not the pinned
     * one, drops the cached windows and `self` and shows the returned window as the
     * whole reading. The route keeps its scroll offset; the overlay clamps it to
     * the new total rather than jumping to the top.
     */
    fun loadWindow(offset: Int, token: String?) {
        val current = reading ?: return
        if (current.windows.containsKey(offset)) return
        if (loading || offset == failedWindow) return
        val request = ++gen
        loading = true
        error = null
        api.list(token = token, generation = current.generation, limit = PAGE_SIZE, offset = offset) { result ->
            if (request != gen) return@list
            loading = false
            result.fold(
                onSuccess = { page ->
                    failedWindow = null
                    reading = if (page.replaced || page.generation != current.generation) {
                        // A different reading: the pinned generation is gone. Keep
                        // only the returned window; the route's scroll offset stays
                        // put and the overlay clamps it to the new total.
                        page.toReading()
                    } else {
                        current.withWindow(page.offset, page.rows)
                    }
                    error = null
                },
                onFailure = { err ->
                    failedWindow = offset
                    error = err.message
                    if ((err as? LeaderboardException)?.unauthorized == true) {
                        onUnauthorized?.invoke(token)
                    }
                },
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }

    /** Drop the reading; the scope closing must not leave a response to land. */
    override fun dispose() {
        clear()
    }
}
