package inego.zungjunggame.input

/**
 * Fans an [Intent] out to handlers in order; the first handler to consume it wins.
 *
 * The handler list is copied at construction so later mutation of the source does
 * not affect dispatch.
 */
class FanOutDispatcher(handlers: List<IntentHandler>) : IntentSink {
    private val handlers: List<IntentHandler> = handlers.toList()

    override fun emit(intent: Intent) {
        dispatch(intent)
    }

    /** Dispatches [intent]; returns true when a handler consumed it. */
    fun dispatch(intent: Intent): Boolean {
        for (handler in handlers) {
            if (handler.handle(intent)) return true
        }
        return false
    }
}
