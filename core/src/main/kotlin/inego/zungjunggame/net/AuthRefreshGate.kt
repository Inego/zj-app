package inego.zungjunggame.net

/**
 * Deduplicates concurrent refresh attempts into a single in-flight HTTP call.
 *
 * Presenting the same refresh token twice would revoke the token family on the
 * server, so overlapping 401s register here instead of sending again: the first
 * [request] starts the send, and every later one joins it. Each waiter — the
 * starter and each joiner — receives that one send's result.
 */
class AuthRefreshGate(
    private val send: (
        refreshToken: String,
        onDone: (Result<AuthLogin>) -> Unit,
    ) -> Unit,
) {
    private val waiters = ArrayList<(Result<AuthLogin>) -> Unit>()

    val inFlight: Boolean
        get() = waiters.isNotEmpty()

    /**
     * Joins the refresh in flight, or starts one when none is running. When
     * joining, [refreshToken] is ignored: the running send already presented its
     * token.
     *
     * @return true when this call started the send, false when it joined one.
     */
    fun request(refreshToken: String, onDone: (Result<AuthLogin>) -> Unit): Boolean {
        val start = waiters.isEmpty()
        waiters.add(onDone)
        if (!start) return false
        send(refreshToken) { result ->
            val callbacks = waiters.toList()
            waiters.clear()
            for (cb in callbacks) cb(result)
        }
        return true
    }
}
