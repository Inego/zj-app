package inego.zungjunggame.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopCrashHookTest {
    @Test
    fun `run reports then rethrows`() {
        val previous = DesktopCrashHook.onThrowable
        try {
            var capturedWhere: String? = null
            var captured: Throwable? = null
            DesktopCrashHook.onThrowable = { _, error, where ->
                capturedWhere = where
                captured = error
            }
            val boom = IllegalStateException("posted runnable")
            val thrown = assertFailsWith<IllegalStateException> {
                DesktopCrashHook.run("JvmWebSocketClient") { throw boom }
            }
            assertSame(boom, thrown)
            assertSame(boom, captured)
            assertEquals("JvmWebSocketClient", capturedWhere)
        } finally {
            DesktopCrashHook.onThrowable = previous
        }
    }
}
