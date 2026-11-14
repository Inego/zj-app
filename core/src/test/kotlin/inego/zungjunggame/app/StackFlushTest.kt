package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackFlushTest {

    private val interval = 60_000L

    @Test
    fun nothingChangedIsNeverDue() {
        assertFalse(
            stackFlushDue(
                stackChanged = false,
                routeUiChanged = false,
                nowMs = 10 * interval,
                lastFlushMs = 0L,
                intervalMs = interval,
            ),
        )
    }

    @Test
    fun stackChangeAfterIntervalIsDue() {
        assertTrue(
            stackFlushDue(
                stackChanged = true,
                routeUiChanged = false,
                nowMs = interval,
                lastFlushMs = 0L,
                intervalMs = interval,
            ),
        )
    }

    @Test
    fun routeUiChangeAfterIntervalIsDue() {
        assertTrue(
            stackFlushDue(
                stackChanged = false,
                routeUiChanged = true,
                nowMs = interval,
                lastFlushMs = 0L,
                intervalMs = interval,
            ),
        )
    }

    @Test
    fun changeBeforeIntervalIsThrottled() {
        assertFalse(
            stackFlushDue(
                stackChanged = true,
                routeUiChanged = false,
                nowMs = interval - 1,
                lastFlushMs = 0L,
                intervalMs = interval,
            ),
        )
        assertFalse(
            stackFlushDue(
                stackChanged = false,
                routeUiChanged = true,
                nowMs = interval - 1,
                lastFlushMs = 0L,
                intervalMs = interval,
            ),
        )
    }
}
