package inego.zungjunggame.online

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.net.ChallengeRounds
import inego.zungjunggame.net.ChallengeStart
import inego.zungjunggame.net.ShadowRankedStartClient
import inego.zungjunggame.net.ShadowRankedStartFailure
import inego.zungjunggame.net.ShadowRankedStartWire
import inego.zungjunggame.net.ShadowRankedWire
import inego.zungjunggame.net.ShadowRankedWireError
import inego.zungjunggame.net.ShadowRankedWireException
import inego.zungjunggame.net.shadowRankedTransportFailure
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShadowRankedStarterTest {
    private class FakeSender : ShadowRankedStartWire {
        data class Call(val token: String, val rounds: ChallengeRounds, val allowChicken: Boolean)

        val calls = mutableListOf<Call>()
        private val pending = mutableListOf<(Result<ChallengeStart>) -> Unit>()

        override fun start(
            token: String,
            rounds: ChallengeRounds,
            allowChicken: Boolean,
            onDone: (Result<ChallengeStart>) -> Unit,
        ) {
            calls += Call(token, rounds, allowChicken)
            pending += onDone
        }

        fun complete(result: Result<ChallengeStart>) {
            pending.removeFirst()(result)
        }
    }

    private val start = ChallengeStart(
        publicId = "7K3H-M9PQ",
        tableId = "table-1",
        seat = 0,
        ticket = "ticket",
    )

    private fun starter(
        token: String?,
        sender: FakeSender,
        onStarted: (ChallengeStart) -> Unit = {},
        onUnauthorized: (String?) -> Unit = {},
    ): ShadowRankedStarter = ShadowRankedStarter(
        token = { token },
        client = ShadowRankedStartClient(sender = sender, onUnauthorized = onUnauthorized),
        onStarted = onStarted,
    )

    @Test
    fun successReachesStartedAndNotifies() {
        val sender = FakeSender()
        var started: ChallengeStart? = null
        val starter = starter("tok", sender, onStarted = { started = it })

        starter.start(GameConfig(rounds = 2, allowChickenHands = true))
        assertEquals(ChallengeRounds.Two, sender.calls.single().rounds)
        assertTrue(sender.calls.single().allowChicken)

        sender.complete(Result.success(start))

        assertEquals(ShadowRankedStartState.Started(start), starter.snapshot())
        assertEquals(start, started)
    }

    @Test
    fun theDraftRoundCountMapsToOneOrTwo() {
        val sender = FakeSender()
        val starter = starter("tok", sender)

        starter.start(GameConfig(rounds = 1))
        starter.cancel()
        starter.start(GameConfig(rounds = 2))

        assertEquals(listOf(ChallengeRounds.One, ChallengeRounds.Two), sender.calls.map { it.rounds })
    }

    @Test
    fun unauthorizedRefreshesAndFails() {
        val sender = FakeSender()
        var refreshes = 0
        val starter = starter("tok", sender, onUnauthorized = { refreshes++ })

        starter.start(GameConfig(rounds = 1))
        sender.complete(Result.failure(ShadowRankedWireException("no", ShadowRankedWireError.Unauthorized)))

        assertEquals(ShadowRankedStartState.Failed(ShadowRankedStartFailure.Unauthorized), starter.snapshot())
        assertEquals(1, refreshes)
    }

    @Test
    fun aTimeoutFailsAsTimeout() {
        val sender = FakeSender()
        val starter = starter("tok", sender)

        starter.start(GameConfig(rounds = 1))
        // Drive the wire's real classification seam: the platform hands back the
        // failure after the request's own TIMEOUT_MS has elapsed.
        sender.complete(
            Result.failure(
                shadowRankedTransportFailure(SocketTimeoutException(), ShadowRankedWire.TIMEOUT_MS.toLong()),
            ),
        )

        assertEquals(ShadowRankedStartState.Failed(ShadowRankedStartFailure.Timeout), starter.snapshot())
    }

    @Test
    fun cancelMidFlightIgnoresTheLateResult() {
        val sender = FakeSender()
        var started: ChallengeStart? = null
        val starter = starter("tok", sender, onStarted = { started = it })

        starter.start(GameConfig(rounds = 1))
        assertEquals(ShadowRankedStartState.Starting, starter.snapshot())
        starter.cancel()
        assertEquals(ShadowRankedStartState.Idle, starter.snapshot())

        sender.complete(Result.success(start))

        assertEquals(ShadowRankedStartState.Idle, starter.snapshot())
        assertNull(started)
    }

    @Test
    fun aNullTokenFailsWithoutSending() {
        val sender = FakeSender()
        val starter = starter(null, sender)

        starter.start(GameConfig(rounds = 1))

        assertEquals(ShadowRankedStartState.Failed(ShadowRankedStartFailure.NoSession), starter.snapshot())
        assertTrue(sender.calls.isEmpty())
    }

    @Test
    fun retryReplaysTheRememberedDraftOnlyFromFailed() {
        val sender = FakeSender()
        val starter = starter("tok", sender)

        starter.retry()
        assertTrue(sender.calls.isEmpty(), "nothing to retry while idle")

        starter.start(GameConfig(rounds = 2, allowChickenHands = true))
        sender.complete(Result.failure(ShadowRankedWireException("server said no", ShadowRankedWireError.Refused)))
        assertEquals(ShadowRankedStartState.Failed(ShadowRankedStartFailure.Unavailable), starter.snapshot())

        starter.retry()
        assertEquals(ChallengeRounds.Two, sender.calls.last().rounds)
        assertTrue(sender.calls.last().allowChicken)
        sender.complete(Result.success(start))

        assertEquals(ShadowRankedStartState.Started(start), starter.snapshot())
    }
}
