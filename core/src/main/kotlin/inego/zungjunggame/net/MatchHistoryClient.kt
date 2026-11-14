package inego.zungjunggame.net

/**
 * In-memory paginated match history behind the hub's **Past games** link. No
 * Gdx app required when [api] is injected.
 */
class MatchHistoryClient(
    private val api: MatchHistorySender = MatchHistoryApi,
) {
    var all: List<MatchSummary> = emptyList()
        private set
    var allTotal: Int = 0
        private set
    var allError: String? = null
        private set
    var allLoading: Boolean = false
        private set

    val hasMore: Boolean
        get() = all.size < allTotal

    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    private var allGen = 0

    fun clear() {
        allGen += 1
        all = emptyList()
        allTotal = 0
        allError = null
        allLoading = false
    }

    /** Refetch the Past games list if it was opened this session. */
    fun reloadAllIfLoaded(token: String) {
        if (allGen == 0) return
        loadFirstPage(token)
    }

    fun loadFirstPage(token: String) {
        val gen = ++allGen
        allLoading = true
        allError = null
        api.list(token, PAGE_SIZE, 0) { result ->
            if (gen != allGen) return@list
            allLoading = false
            result.fold(
                onSuccess = {
                    all = it.matches
                    allTotal = it.total
                    allError = null
                },
                onFailure = { err ->
                    allError = err.message
                    if ((err as? MatchHistoryException)?.unauthorized == true) {
                        onUnauthorized?.invoke(token)
                    }
                },
            )
        }
    }

    fun loadMore(token: String) {
        if (allLoading || !hasMore) return
        val gen = allGen
        val offset = all.size
        allLoading = true
        allError = null
        api.list(token, PAGE_SIZE, offset) { result ->
            if (gen != allGen) return@list
            allLoading = false
            result.fold(
                onSuccess = {
                    all = all + it.matches
                    allTotal = it.total
                    allError = null
                },
                onFailure = { err ->
                    allError = err.message
                    if ((err as? MatchHistoryException)?.unauthorized == true) {
                        onUnauthorized?.invoke(token)
                    }
                },
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        const val LOAD_MORE_ID = "load_more"
    }
}
