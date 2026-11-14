package inego.zungjunggame.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FanOutDispatcherTest {

    private class RecordingHandler(
        private val consumes: Boolean,
        private val log: MutableList<String>,
        private val name: String,
    ) : IntentHandler {
        override fun handle(intent: Intent): Boolean {
            log += name
            return consumes
        }
    }

    @Test
    fun firstConsumingHandlerWinsAndStopsDispatch() {
        val log = mutableListOf<String>()
        val dispatcher = FanOutDispatcher(
            listOf(
                RecordingHandler(false, log, "first"),
                RecordingHandler(true, log, "second"),
                RecordingHandler(true, log, "third"),
            ),
        )

        assertTrue(dispatcher.dispatch(Intent.Hint))
        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun unconsumedIntentCallsEveryHandlerAndReturnsFalse() {
        val log = mutableListOf<String>()
        val dispatcher = FanOutDispatcher(
            listOf(
                RecordingHandler(false, log, "a"),
                RecordingHandler(false, log, "b"),
            ),
        )

        assertFalse(dispatcher.dispatch(Intent.Back))
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun copiesHandlerListAtConstruction() {
        val log = mutableListOf<String>()
        val source = mutableListOf<IntentHandler>(RecordingHandler(true, log, "only"))
        val dispatcher = FanOutDispatcher(source)
        source.clear()

        assertTrue(dispatcher.dispatch(Intent.ConfirmNo))
        assertEquals(listOf("only"), log)
    }

}
