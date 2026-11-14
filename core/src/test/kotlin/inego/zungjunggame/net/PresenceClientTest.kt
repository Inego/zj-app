package inego.zungjunggame.net

import inego.zungjunggame.persist.FindGamePreferences
import inego.zungjunggame.persist.FindGamePreferencesKey
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceClientTest {

    private fun findSlot(
        backing: InMemoryStateBackend = InMemoryStateBackend(),
    ): StateSlot<FindGamePreferences> =
        StateSlot(DefaultStateStore(backing), FindGamePreferencesKey)

    /** The client under test; persistence is in-memory unless a slot is supplied. */
    private fun testClient(
        api: PresenceSender = FakeSender(),
        clock: () -> Long = { 0L },
        prefs: StateSlot<FindGamePreferences> = findSlot(),
    ): PresenceClient = PresenceClient(api = api, clock = clock, findPreferences = prefs)

    @Test
    fun startAndStopResetLookingButKeepPrefs() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        assertTrue(!client.waitingEnabled)
        assertEquals(1, client.minOtherHumans)
        assertEquals(QueueRounds.Either, client.queueRounds)
        assertEquals(QueueChicken.Either, client.queueChicken)

        client.start("tok")
        client.startLooking()
        client.cycleMinOtherHumans()
        client.cycleRounds()
        client.cycleChicken()
        assertTrue(client.waitingEnabled)
        assertEquals(1_000L, client.searchStartedAtMs)
        assertEquals(2, client.minOtherHumans)
        assertEquals(QueueRounds.One, client.queueRounds)
        assertEquals(QueueChicken.Allowed, client.queueChicken)

        client.start("tok-b")
        assertTrue(!client.waitingEnabled)
        assertEquals(null, client.searchStartedAtMs)
        assertEquals(2, client.minOtherHumans)
        assertEquals(QueueRounds.One, client.queueRounds)
        assertEquals(QueueChicken.Allowed, client.queueChicken)

        client.startLooking()
        client.stop()
        assertTrue(!client.waitingEnabled)
        assertEquals(null, client.searchStartedAtMs)
        assertEquals(2, client.minOtherHumans)
        assertEquals(QueueRounds.One, client.queueRounds)
        assertEquals(QueueChicken.Allowed, client.queueChicken)
    }

    @Test
    fun requestNowSendsCurrentLookingState() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("tok")
        client.toggleWaiting()
        client.requestNow()
        assertEquals(PresenceActivity.Waiting, fake.heartbeats.single().activity)
        fake.completeHeartbeat(okSnapshot())

        client.stopLooking()
        client.requestNow()
        assertEquals(PresenceActivity.Idle, fake.heartbeats.last().activity)
        assertEquals(2, fake.heartbeats.size)
    }

    @Test
    fun requestNowAfterStopLookingFlushesIdleOverInFlightWaiting() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("tok")
        client.toggleWaiting()
        client.tick(1_000L, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)

        client.stopLooking()
        client.requestNow()
        assertEquals(1, fake.heartbeats.size, "in-flight Waiting is not replaced")
        fake.completeHeartbeat(okSnapshot())
        assertEquals(PresenceActivity.Idle, fake.heartbeats.last().activity)
        assertEquals(2, fake.heartbeats.size)
    }

    @Test
    fun stopLookingLeavesQueueWithoutResettingPrefs() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("tok")
        client.toggleWaiting()
        client.cycleMinOtherHumans()
        assertTrue(client.waitingEnabled)
        assertEquals(2, client.minOtherHumans)

        client.stopLooking()
        assertTrue(!client.waitingEnabled)
        assertEquals(2, client.minOtherHumans)

        client.stopLooking()
        assertTrue(!client.waitingEnabled)
    }

    @Test
    fun logoutDuringInFlightPostsLeaveWithCapturedBearer() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.tick(1_000L, PresenceActivity.Waiting)
        assertEquals("token-a", fake.heartbeats.single().token)
        assertEquals(1, fake.heartbeats.single().minOtherHumans)
        assertEquals(QueueRounds.Either, fake.heartbeats.single().rounds)
        assertEquals(QueueChicken.Either, fake.heartbeats.single().chicken)

        client.stopAndLeave("token-a")
        assertTrue(fake.leaves.isEmpty(), "leave waits for in-flight heartbeat")
        fake.completeHeartbeat(okSnapshot())
        assertEquals(listOf("token-a"), fake.leaves.map { it.token })
        assertNull(client.snapshot, "stale 200 after stop must not apply")
    }

    @Test
    fun startKeepsOwedLeaveForPreviousToken() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.tick(1_000L, PresenceActivity.Waiting)
        client.stopAndLeave("token-a")
        client.start("token-b")
        assertEquals(1, fake.heartbeats.size)
        fake.completeHeartbeat(okSnapshot(online = 99))
        assertEquals(listOf("token-a"), fake.leaves.map { it.token })
        assertNull(client.snapshot)
        assertEquals(1, fake.heartbeats.size, "start() does not POST; next tick sends")
        client.tick(1_000L, PresenceActivity.Playing)
        assertEquals("token-b", fake.heartbeats.last().token)
        assertEquals(PresenceActivity.Playing, fake.heartbeats.last().activity)
        fake.completeHeartbeat(okSnapshot(online = 1))
        assertEquals(1, client.snapshot?.online)
    }

    @Test
    fun replaceTokenKeepsLookingAndSnapshot() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.startLooking()
        client.tick(1_000L, PresenceActivity.Waiting)
        fake.completeHeartbeat(okSnapshot(online = 3))
        assertEquals(3, client.snapshot?.online)
        assertTrue(client.waitingEnabled)

        client.replaceToken("token-b")
        assertTrue(client.waitingEnabled)
        assertEquals(3, client.snapshot?.online)
        assertTrue(client.signedIn)

        client.requestNow()
        fake.completeHeartbeat(okSnapshot(online = 4))
        assertEquals("token-b", fake.heartbeats.last().token)
        assertEquals(PresenceActivity.Waiting, fake.heartbeats.last().activity)
        assertEquals(4, client.snapshot?.online)
        assertTrue(client.waitingEnabled)
    }

    @Test
    fun cancelAndLeaveDropsLocalSeatedTableImmediately() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.snapshot = PresenceSnapshot(
            online = 4,
            playing = 4,
            waiting = 0,
            self = PresenceSelf(
                userId = "id",
                displayName = "1",
                activity = PresenceActivity.Playing,
                table = PresenceTable("tbl-1", QueueRounds.One, allowChicken = false, humans = 1, bots = 3),
            ),
        )

        client.cancelAndLeave()

        assertNull(client.snapshot?.self?.table)
        assertEquals(PresenceActivity.Idle, client.snapshot?.self?.activity)
        assertEquals(listOf("token-a"), fake.leaves.map { it.token })
    }

    @Test
    fun cancelAndLeaveStripsSeatedHeartbeatUntilServerUnseats() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.snapshot = PresenceSnapshot(
            online = 4,
            playing = 4,
            waiting = 0,
            self = PresenceSelf(
                userId = "id",
                displayName = "1",
                activity = PresenceActivity.Playing,
                table = PresenceTable("tbl-1", QueueRounds.One, allowChicken = false, humans = 1, bots = 3),
            ),
        )

        client.cancelAndLeave()
        assertEquals(1, fake.heartbeats.size)

        fake.completeHeartbeat(
            Result.success(
                PresenceSnapshot(
                    online = 4,
                    playing = 3,
                    waiting = 0,
                    self = PresenceSelf(
                        userId = "id",
                        displayName = "1",
                        activity = PresenceActivity.Playing,
                        table = PresenceTable("tbl-1", QueueRounds.One, allowChicken = false, humans = 1, bots = 3),
                    ),
                ),
            ),
        )
        assertNull(client.snapshot?.self?.table)
        assertEquals(PresenceActivity.Idle, client.snapshot?.self?.activity)

        client.requestNow()
        fake.completeHeartbeat(
            Result.success(
                PresenceSnapshot(
                    online = 4,
                    playing = 3,
                    waiting = 0,
                    self = PresenceSelf("id", "1", PresenceActivity.Idle),
                ),
            ),
        )
        assertNull(client.snapshot?.self?.table)
        assertEquals(PresenceActivity.Idle, client.snapshot?.self?.activity)
    }

    @Test
    fun cancelAndLeaveSendsLeaveAndImmediatelyHeartbeatsIdle() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.startLooking()
        assertTrue(client.waitingEnabled)

        client.cancelAndLeave()
        assertFalse(client.waitingEnabled)
        assertEquals(listOf("token-a"), fake.leaves.map { it.token })
        assertEquals(1, fake.heartbeats.size)
        assertEquals(PresenceActivity.Idle, fake.heartbeats.single().activity)
    }

    @Test
    fun cancelAndLeaveWhileInFlightWaitsForHeartbeatThenLeavesAndHeartbeatsIdle() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("token-a")
        client.startLooking()
        client.tick(1_000L, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)
        assertEquals(PresenceActivity.Waiting, fake.heartbeats.single().activity)

        client.cancelAndLeave()
        assertFalse(client.waitingEnabled)
        assertTrue(fake.leaves.isEmpty(), "leave waits for in-flight heartbeat")

        fake.completeHeartbeat(okSnapshot(online = 10))
        // Stale snapshot from cancelled search must not apply (generation bumped)
        assertNull(client.snapshot)
        assertEquals(listOf("token-a"), fake.leaves.map { it.token })
        assertEquals(2, fake.heartbeats.size)
        assertEquals(PresenceActivity.Idle, fake.heartbeats.last().activity)
    }

    @Test
    fun replaceTokenWhileStoppedStarts() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.replaceToken("token-a")
        assertTrue(client.signedIn)
        client.tick(1_000L, PresenceActivity.Idle)
        assertEquals("token-a", fake.heartbeats.single().token)
    }

    @Test
    fun firstTickAfterStartSendsPassedActivity() {
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { 1_000L })
        client.start("tok")
        assertTrue(fake.heartbeats.isEmpty())
        client.tick(1_000L, PresenceActivity.Playing)
        assertEquals(PresenceActivity.Playing, fake.heartbeats.single().activity)
    }

    @Test
    fun failedHeartbeatDoesNotTightLoop() {
        val fake = FakeSender()
        var now = 10_000L
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)
        fake.completeHeartbeat(Result.failure(PresenceException("down")))
        assertEquals(1, fake.heartbeats.size)
        now = 11_000L
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)
        now = 15_000L
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(2, fake.heartbeats.size)
    }

    @Test
    fun activityChangeSendsBeforeInterval() {
        val fake = FakeSender()
        var now = 10_000L
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.tick(now, PresenceActivity.Waiting)
        fake.completeHeartbeat(okSnapshot())
        now = 10_200L
        client.tick(now, PresenceActivity.Playing)
        assertEquals(PresenceActivity.Playing, fake.heartbeats.last().activity)
    }

    @Test
    fun prefChangeSendsBeforeInterval() {
        val fake = FakeSender()
        var now = 10_000L
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.toggleWaiting()
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)
        assertEquals(1, fake.heartbeats.last().minOtherHumans)
        assertEquals(QueueChicken.Either, fake.heartbeats.last().chicken)
        fake.completeHeartbeat(okSnapshot())

        // Cycle min-other at +200ms
        now = 10_200L
        client.cycleMinOtherHumans()
        assertEquals(2, client.minOtherHumans)
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(2, fake.heartbeats.size)
        assertEquals(2, fake.heartbeats.last().minOtherHumans)
        fake.completeHeartbeat(okSnapshot())

        // Cycle chicken at +400ms
        now = 10_400L
        client.cycleChicken()
        assertEquals(QueueChicken.Allowed, client.queueChicken)
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(3, fake.heartbeats.size)
        assertEquals(QueueChicken.Allowed, fake.heartbeats.last().chicken)
    }

    @Test
    fun seatedSnapshotStillSendsWaitingIfLookingEnabled() {
        val fake = FakeSender()
        var now = 10_000L
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.toggleWaiting()
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(1, fake.heartbeats.size)

        // Server responds with seated table snapshot
        val seatedSnapshot = Result.success(
            PresenceSnapshot(
                online = 4,
                playing = 4,
                waiting = 0,
                self = PresenceSelf(
                    userId = "id",
                    displayName = "1",
                    activity = PresenceActivity.Playing,
                    table = PresenceTable("tbl-1", QueueRounds.One, allowChicken = false, humans = 4, bots = 0),
                ),
            ),
        )
        fake.completeHeartbeat(seatedSnapshot)
        assertEquals(PresenceActivity.Playing, client.snapshot?.self?.activity)

        // Next tick sends Waiting (keep-alive)
        now = 15_000L
        client.tick(now, PresenceActivity.Waiting)
        assertEquals(2, fake.heartbeats.size)
        assertEquals(PresenceActivity.Waiting, fake.heartbeats.last().activity)
    }

    @Test
    fun cycleMinOtherHumansCycles0To3() {
        val client = testClient(api = FakeSender())
        assertEquals(1, client.minOtherHumans)
        client.cycleMinOtherHumans()
        assertEquals(2, client.minOtherHumans)
        client.cycleMinOtherHumans()
        assertEquals(3, client.minOtherHumans)
        client.cycleMinOtherHumans()
        assertEquals(0, client.minOtherHumans)
        client.cycleMinOtherHumans()
        assertEquals(1, client.minOtherHumans)
    }

    @Test
    fun setMinOtherHumansClampsAndPersists() {
        val slot = findSlot()
        val client = testClient(api = FakeSender(), prefs = slot)

        client.setMinOtherHumans(3)
        assertEquals(3, client.minOtherHumans)
        assertEquals(3, slot.requireValue().minOtherHumans)

        // Clamping
        client.setMinOtherHumans(10)
        assertEquals(3, client.minOtherHumans)

        client.setMinOtherHumans(-1)
        assertEquals(0, client.minOtherHumans)
        assertEquals(0, slot.requireValue().minOtherHumans)
    }

    @Test
    fun cyclePreferencesPersistAndRestoreAcrossInstances() {
        val backing = InMemoryStateBackend()
        val client1 = testClient(api = FakeSender(), prefs = findSlot(backing))

        client1.cycleMinOtherHumans() // 1 -> 2
        client1.cycleRounds()         // Either -> One
        client1.cycleChicken()        // Either -> Allowed

        assertEquals(2, client1.minOtherHumans)
        assertEquals(QueueRounds.One, client1.queueRounds)
        assertEquals(QueueChicken.Allowed, client1.queueChicken)

        // Simulate app restart by constructing client2 over the same backing store
        val client2 = testClient(api = FakeSender(), prefs = findSlot(backing))
        assertEquals(2, client2.minOtherHumans)
        assertEquals(QueueRounds.One, client2.queueRounds)
        assertEquals(QueueChicken.Allowed, client2.queueChicken)
    }

    @Test
    fun successfulHeartbeatRecordsRttFromExistingRequest() {
        var now = 10_000L
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        assertNull(client.lastRttMs)

        client.tick(now, PresenceActivity.Idle)
        now = 10_042L
        fake.completeHeartbeat(okSnapshot())
        assertEquals(42, client.lastRttMs)

        now = 15_000L
        client.tick(now, PresenceActivity.Idle)
        now = 15_118L
        fake.completeHeartbeat(okSnapshot())
        assertEquals(118, client.lastRttMs)
    }

    @Test
    fun failedHeartbeatKeepsLastRtt() {
        var now = 10_000L
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.tick(now, PresenceActivity.Idle)
        now = 10_050L
        fake.completeHeartbeat(okSnapshot())
        assertEquals(50, client.lastRttMs)

        now = 15_000L
        client.tick(now, PresenceActivity.Idle)
        now = 15_400L
        fake.completeHeartbeat(Result.failure(PresenceException("down")))
        assertEquals(50, client.lastRttMs)
    }

    @Test
    fun startAndStopClearRtt() {
        var now = 10_000L
        val fake = FakeSender()
        val client = testClient(api = fake, clock = { now })
        client.start("tok")
        client.tick(now, PresenceActivity.Idle)
        now = 10_030L
        fake.completeHeartbeat(okSnapshot())
        assertEquals(30, client.lastRttMs)

        client.stop()
        assertNull(client.lastRttMs)

        now = 20_000L
        client.start("tok")
        assertNull(client.lastRttMs)
        client.tick(now, PresenceActivity.Idle)
        now = 20_025L
        fake.completeHeartbeat(okSnapshot())
        assertEquals(25, client.lastRttMs)

        client.stopAndLeave("tok")
        assertNull(client.lastRttMs)
    }

    private fun okSnapshot(online: Int = 1) = Result.success(
        PresenceSnapshot(
            online = online,
            playing = 0,
            waiting = online,
            self = PresenceSelf("id", "1", PresenceActivity.Waiting),
        ),
    )

    private class FakeSender : PresenceSender {
        data class Hb(
            val token: String,
            val activity: PresenceActivity,
            val minOtherHumans: Int?,
            val rounds: QueueRounds?,
            val chicken: QueueChicken?,
        )
        data class Lv(val token: String)

        val heartbeats = ArrayList<Hb>()
        val leaves = ArrayList<Lv>()
        private val hbWaiters = ArrayList<(Result<PresenceSnapshot>) -> Unit>()

        override fun heartbeat(
            token: String,
            activity: PresenceActivity,
            minOtherHumans: Int?,
            rounds: QueueRounds?,
            chicken: QueueChicken?,
            onDone: (Result<PresenceSnapshot>) -> Unit,
        ) {
            heartbeats += Hb(token, activity, minOtherHumans, rounds, chicken)
            hbWaiters += onDone
        }

        override fun leave(token: String, onDone: (Result<Unit>) -> Unit) {
            leaves += Lv(token)
            onDone(Result.success(Unit))
        }

        fun completeHeartbeat(result: Result<PresenceSnapshot>) {
            val cb = hbWaiters.removeAt(0)
            cb(result)
        }
    }
}
