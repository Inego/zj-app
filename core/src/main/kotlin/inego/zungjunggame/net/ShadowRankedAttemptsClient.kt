package inego.zungjunggame.net

import com.badlogic.gdx.utils.Disposable

/**
 * In-memory Shadow Ranked attempts list, the home of the wire's `GET /challenges`
 * rows. It reuses the wire [ChallengeEntry] model.
 *
 * A [load] replaces the stored entries with the first page; [loadMore] fetches the
 * next page via the cursor and appends it to [entries]. A stale response from a
 * superseded request is dropped by the [gate]. A null or blank [token] clears the
 * list and sends nothing, and [clear] is the log-out / expiry path, so a second
 * account never sees the first's attempts.
 */
class ShadowRankedAttemptsClient(
    private val api: ShadowRankedAttemptsWire = ShadowRankedWire,
) : Disposable {
    var entries: List<ChallengeEntry> = emptyList()
        private set
    var nextCursor: String? = null
        private set
    var hasMore: Boolean = false
        private set

    var loading: Boolean = false
        private set
    var loadingMore: Boolean = false
        private set
    var error: String? = null
        private set
    var loadMoreError: String? = null
        private set

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    private val gate = RequestGate()

    fun clear() {
        gate.supersede()
        entries = emptyList()
        nextCursor = null
        hasMore = false
        loading = false
        loadingMore = false
        error = null
        loadMoreError = null
    }

    /** Scope close: drop a response that is still in flight. */
    override fun dispose() = clear()

    fun load(token: String?) {
        if (token.isNullOrBlank()) {
            clear()
            return
        }
        loading = true
        loadingMore = false
        error = null
        loadMoreError = null
        api.list(
            token = token,
            limit = PAGE_SIZE,
            cursor = null,
            onDone = gate.begin { result ->
                loading = false
                result.fold(
                    onSuccess = {
                        entries = it.challenges
                        nextCursor = it.nextCursor
                        hasMore = it.hasMore
                        error = null
                    },
                    onFailure = { err ->
                        error = err.message
                        if ((err as? ShadowRankedWireException)?.error == ShadowRankedWireError.Unauthorized) {
                            onUnauthorized?.invoke(token)
                        }
                    },
                )
            },
        )
    }

    fun loadMore(token: String?) {
        val cursor = nextCursor
        if (token.isNullOrBlank() || loading || loadingMore || !hasMore || cursor == null) {
            return
        }
        loadingMore = true
        loadMoreError = null
        api.list(
            token = token,
            limit = PAGE_SIZE,
            cursor = cursor,
            onDone = gate.begin { result ->
                loadingMore = false
                result.fold(
                    onSuccess = {
                        entries = entries + it.challenges
                        nextCursor = it.nextCursor
                        hasMore = it.hasMore
                        loadMoreError = null
                    },
                    onFailure = { err ->
                        loadMoreError = err.message
                        if ((err as? ShadowRankedWireException)?.error == ShadowRankedWireError.Unauthorized) {
                            onUnauthorized?.invoke(token)
                        }
                    },
                )
            },
        )
    }

    companion object {
        const val PAGE_SIZE = 20
        const val LOAD_MORE_ID = "shadow_ranked_load_more"
    }
}
