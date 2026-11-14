package inego.zungjunggame.net

/**
 * Orders asynchronous responses against newer intent: only the request that
 * started last may touch the model, and every other response is dropped instead
 * of overwriting truth that has moved on.
 *
 * The rule is always the same across the clients in this package — a response may
 * not outrank an edit or a request that came after it — so it lives here once
 * rather than as a counter each client copies. Starting a request **is**
 * superseding whatever is in flight ([begin]), so "remember to invalidate first"
 * is not a step a caller can forget; a local edit that sends nothing says so with
 * [supersede].
 */
class RequestGate {
    private var current = 0

    /**
     * Start a request that supersedes everything in flight, and wrap [onDone] so
     * it runs only while this request is still the newest. The client keeps its
     * own busy/error policy; the gate only decides which response is allowed to
     * apply.
     */
    fun <T> begin(onDone: (Result<T>) -> Unit): (Result<T>) -> Unit {
        val mine = ++current
        return { result -> if (mine == current) onDone(result) }
    }

    /** Newer intent that sends nothing (a local edit, a logout): drop in-flight work. */
    fun supersede() {
        current++
    }
}
