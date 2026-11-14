package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRefreshGateTest {
    private val user = AuthUser(id = "u1", displayName = "Alice")

    @Test
    fun concurrentRequestsShareOneSend() {
        var sendCount = 0
        var complete: ((Result<AuthLogin>) -> Unit)? = null
        val gate = AuthRefreshGate { token, done ->
            sendCount += 1
            assertEquals("ref-1", token)
            complete = done
        }
        val results = mutableListOf<String>()
        val started = gate.request("ref-1") { results.add("a:" + it.getOrThrow().token) }
        val joinedB = gate.request("ref-1") { results.add("b:" + it.getOrThrow().token) }
        val joinedC = gate.request("ref-1") { results.add("c:" + it.getOrThrow().token) }

        assertEquals(1, sendCount)
        assertTrue(started, "the first request starts the send")
        assertFalse(joinedB, "a request while one runs joins it")
        assertFalse(joinedC)
        assertTrue(gate.inFlight)
        complete!!(Result.success(AuthLogin("access-2", user, "ref-2")))
        assertFalse(gate.inFlight)
        assertEquals(listOf("a:access-2", "b:access-2", "c:access-2"), results)
    }

    @Test
    fun laterRequestAfterCompletionSendsAgain() {
        var sendCount = 0
        val gate = AuthRefreshGate { _, done ->
            sendCount += 1
            done(Result.success(AuthLogin("tok-$sendCount", user, "ref-$sendCount")))
        }
        gate.request("r") { }
        gate.request("r") { }
        assertEquals(2, sendCount)
    }
}
