package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadowRankedStartClientTest {
    private class FakeSender : ShadowRankedStartWire {
        private val pending = mutableListOf<(Result<ChallengeStart>) -> Unit>()
        val calls = mutableListOf<Pair<String, ChallengeRounds>>()

        override fun start(
            token: String,
            rounds: ChallengeRounds,
            allowChicken: Boolean,
            onDone: (Result<ChallengeStart>) -> Unit,
        ) {
            calls += token to rounds
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

    @Test
    fun successReturnsStarted() {
        val fake = FakeSender()
        val client = ShadowRankedStartClient(sender = fake)
        var outcome: ShadowRankedStartOutcome? = null

        client.start("tok", ChallengeRounds.One, allowChicken = true) { outcome = it }
        fake.complete(Result.success(start))

        assertEquals(ShadowRankedStartOutcome.Started(start), outcome)
    }

    @Test
    fun blankTokenFailsWithoutSending() {
        val fake = FakeSender()
        val client = ShadowRankedStartClient(sender = fake)
        var outcome: ShadowRankedStartOutcome? = null

        client.start("  ", ChallengeRounds.Two, allowChicken = false) { outcome = it }

        assertEquals(ShadowRankedStartOutcome.Failed(ShadowRankedStartFailure.NoSession), outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun unauthorizedRefreshesAndReportsUnauthorized() {
        val fake = FakeSender()
        var refreshes = 0
        val client = ShadowRankedStartClient(sender = fake, onUnauthorized = { refreshes++ })
        var outcome: ShadowRankedStartOutcome? = null

        client.start("tok", ChallengeRounds.One, allowChicken = false) { outcome = it }
        fake.complete(Result.failure(ShadowRankedWireException("no", ShadowRankedWireError.Unauthorized)))

        assertEquals(ShadowRankedStartOutcome.Failed(ShadowRankedStartFailure.Unauthorized), outcome)
        assertEquals(1, refreshes)
    }

    @Test
    fun wireFailuresMapToTheirTypedKinds() {
        val fake = FakeSender()
        val client = ShadowRankedStartClient(sender = fake)
        val failures = mutableListOf<ShadowRankedStartFailure>()
        repeat(4) {
            client.start("tok", ChallengeRounds.One, allowChicken = false) { outcome ->
                failures += (outcome as ShadowRankedStartOutcome.Failed).failure
            }
        }

        // A refusal is typed by the status; a transport failure is classified by the
        // wire from how long it ran against its own ShadowRankedWire.TIMEOUT_MS.
        fake.complete(Result.failure(ShadowRankedWireException("server said no", ShadowRankedWireError.Refused)))
        fake.complete(
            Result.failure(
                shadowRankedTransportFailure(IllegalStateException("io"), ShadowRankedWire.TIMEOUT_MS.toLong() - 1L),
            ),
        )
        fake.complete(
            Result.failure(
                shadowRankedTransportFailure(IllegalStateException("timed out"), ShadowRankedWire.TIMEOUT_MS.toLong()),
            ),
        )
        // A response the wire could not parse is not a transport failure.
        fake.complete(Result.failure(IllegalArgumentException("bad body")))

        assertEquals(
            listOf(
                ShadowRankedStartFailure.Unavailable,
                ShadowRankedStartFailure.Network,
                ShadowRankedStartFailure.Timeout,
                ShadowRankedStartFailure.Unavailable,
            ),
            failures,
        )
    }
}
