package inego.zungjunggame.online

import inego.zungjunggame.net.AuthLogin
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.net.MatchSummary
import inego.zungjunggame.net.MatchmakingCooldown
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.PresenceActivity
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.net.PresenceSelf
import inego.zungjunggame.net.PresenceSender
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.net.PresenceTable
import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.net.TableTicketException
import inego.zungjunggame.persist.FindGamePreferences
import inego.zungjunggame.persist.FindGamePreferencesKey
import inego.zungjunggame.persist.NotificationPrefsKey
import inego.zungjunggame.persist.OnlineTableIdKey
import inego.zungjunggame.session.online.ConnectionFailure
import inego.zungjunggame.session.FakeWebSocketClient
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory find-preferences slot, so coordinator tests never touch Gdx. */
private fun testFindSlot(
    backing: InMemoryStateBackend = InMemoryStateBackend(),
): StateSlot<FindGamePreferences> =
    StateSlot(DefaultStateStore(backing), FindGamePreferencesKey)

private fun testPresence(
    api: PresenceSender,
    clock: () -> Long = { 0L },
    prefs: StateSlot<FindGamePreferences> = testFindSlot(),
): PresenceClient = PresenceClient(api = api, clock = clock, findPreferences = prefs)

/** In-memory last-table-id slot, standing in for the `online.table` key. */
class FakeOnlineTableStore {
    val slot: StateSlot<String> = StateSlot(DefaultStateStore(InMemoryStateBackend()), OnlineTableIdKey)
    val stored: String? get() = slot.get()?.takeIf { it.isNotEmpty() }
}

/** A notification-prefs client over an in-memory `notification.prefs` slot. */
private fun testNotificationPrefs(): NotificationPrefsClient =
    NotificationPrefsClient(
        slot = StateSlot(DefaultStateStore(InMemoryStateBackend()), NotificationPrefsKey),
    )

/** Stack-membership fake for the away overlay; tracks the announced visibility. */
class FakeAwayPort : OnlineAwayPort {
    var visible = false
    override fun show() { visible = true }
    override fun hide() { visible = false }
}

class FakePlayHost(override val humanSeat: Int = 0) : PlayHost {
    override val tableView = null
    override val myDecision = null
    override val decisionTimeLeftMs = null
    override val connectionStatus = ConnectionStatus.Local
    override fun submitChoice(choice: inego.zungjunggame.engine.Choice) = true
    override fun submitDiscard(tileType: Int, slotIndex: Int?, isDrawn: Boolean) = true
    override fun requestNextHand() {}
    override fun leave() {}
}

class OnlineCoordinatorTest {

    /** Records what the app was told about the live table; see [OnlineTablePort]. */
    private class FakeTablePort : OnlineTablePort {
        var joins = 0
        var exits = 0
        val joinedOrigins = mutableListOf<OnlineTab>()
        override fun tableJoined(origin: OnlineTab) {
            joins += 1
            joinedOrigins += origin
        }
        override fun tableExited() { exits += 1 }
    }

    private class TestHooks(
        val port: FakeTablePort = FakeTablePort(),
    ) {
        var currentKind: SessionKind = SessionKind.Menu
        val localHost = FakePlayHost()
        var localRestoreCalls = 0

        fun sessionKind() = currentKind
        fun onRestoreLocalHost(): PlayHost {
            localRestoreCalls++
            return localHost
        }
        var startingLevelPromptRequested = false
        fun onStartingLevelPromptRequested() {
            startingLevelPromptRequested = true
        }
        var settledMatch: MatchSummary? = null
        fun onSettledMatch(match: MatchSummary) {
            settledMatch = match
        }
    }

    /**
     * One coordinator plus everything a test asserts on. Destructurable, so
     * `val (coordinator, hooks) = ...` keeps reading as before.
     */
    private class Scenario(val coordinator: OnlineCoordinator, val hooks: TestHooks) {
        operator fun component1() = coordinator
        operator fun component2() = hooks
    }

    private class RecordingPresenceSender : PresenceSender {
        data class Hb(val activity: PresenceActivity, val minOtherHumans: Int?)
        val heartbeats = ArrayList<Hb>()
        private val waiters = ArrayList<(Result<PresenceSnapshot>) -> Unit>()

        override fun heartbeat(
            token: String,
            activity: PresenceActivity,
            minOtherHumans: Int?,
            rounds: QueueRounds?,
            chicken: QueueChicken?,
            onDone: (Result<PresenceSnapshot>) -> Unit,
        ) {
            heartbeats += Hb(activity, minOtherHumans)
            waiters += onDone
        }

        val leaves = ArrayList<String>()

        override fun leave(token: String, onDone: (Result<Unit>) -> Unit) {
            leaves += token
            onDone(Result.success(Unit))
        }

        fun completeLast(snapshot: PresenceSnapshot) {
            waiters.removeAt(0).invoke(Result.success(snapshot))
        }
    }

    private fun idleSnapshot() = PresenceSnapshot(
        online = 1,
        playing = 0,
        waiting = 0,
        self = PresenceSelf("u1", "Alice", PresenceActivity.Idle),
    )

    private fun liveSeatSnapshot(): SeatSnapshotWire {
        val seats = (0..3).map { s ->
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == 0) listOf(0) else null,
                drawnTile = -1,
                hasDrawnTile = false,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 70,
            currentPlayer = 0,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = seats,
        )
    }

    private fun seatedSnapshot(tableId: String) = PresenceSnapshot(
        online = 1,
        waiting = 0,
        playing = 1,
        self = PresenceSelf(
            userId = "u1",
            displayName = "Alice",
            activity = PresenceActivity.Playing,
            table = PresenceTable(
                id = tableId,
                rounds = QueueRounds.One,
                allowChicken = true,
                humans = 4,
                bots = 0,
            ),
        ),
    )

    private fun createCoordinator(
        hooks: TestHooks = TestHooks(),
        store: FakeOnlineTableStore = FakeOnlineTableStore(),
        presenceClient: PresenceClient = testPresence(api = RecordingPresenceSender()),
        ws: FakeWebSocketClient = FakeWebSocketClient(),
        presenceScheduler: inego.zungjunggame.platform.PresenceScheduler = inego.zungjunggame.platform.NoOpPresenceScheduler,
        away: FakeAwayPort = FakeAwayPort(),
        auth: AuthSession = signedInTestAuth(),
    ): Scenario {
        val coordinator = OnlineCoordinator(
            authSession = auth,
            presenceClient = presenceClient,
            onlineTableId = store.slot,
            notificationPrefs = testNotificationPrefs(),
            presenceScheduler = presenceScheduler,
            hostFactory = { tableId, token, startsAway, onLeave ->
                OnlinePlayHost(
                    tableId = tableId,
                    token = token,
                    wsClient = ws,
                    onLeaveCallback = onLeave,
                    initialAway = startsAway,
                    requestTicket = { id, _, onDone ->
                        onDone(Result.success(TableTicket(ticket = "test_ticket", tableId = id, seat = 0)))
                    },
                )
            },
            away = away,
            sessionKind = hooks::sessionKind,
            tablePort = hooks.port,
            onRestoreLocalHost = hooks::onRestoreLocalHost,
            onStartingLevelPromptRequested = hooks::onStartingLevelPromptRequested,
            onSettledMatch = hooks::onSettledMatch,
        )
        return Scenario(coordinator, hooks)
    }

    @Test
    fun enterOnlineTableWiresHostAndTellsTheAppTheTableIsLive() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)

        coordinator.enterOnlineTable("tbl_42")

        assertEquals("tbl_42", store.stored)
        assertEquals(1, hooks.port.joins)
        assertNotNull(coordinator.onlineHost)
    }

    @Test
    fun enterOnlineTableCarriesTheOriginTabToTheApp() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)

        // The queue / rejoin paths are live tables.
        coordinator.enterOnlineTable("tbl_live")
        assertEquals(listOf(OnlineTab.Live), hooks.port.joinedOrigins)

        // A Shadow Ranked start says which tab it belongs to.
        coordinator.enterOnlineTable("tbl_ranked", origin = OnlineTab.ShadowRanked)
        assertEquals(listOf(OnlineTab.Live, OnlineTab.ShadowRanked), hooks.port.joinedOrigins)
    }

    @Test
    fun enterOnlineTableWithoutATokenReportsASessionNoticeOnTheHub() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store, auth = testAuth())

        coordinator.enterOnlineTable("tbl_gone")

        assertEquals(
            OnlineCoordinator.HomeNoticeKind.SessionExpired,
            coordinator.homeNotice?.kind,
        )
        assertNull(coordinator.onlineHost)
        assertEquals(0, hooks.port.joins)
        assertNull(store.stored)
    }

    @Test
    fun leaveOnlineTableDoesNotRejoinFromStaleSeatedSnapshot() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")

        coordinator.leaveOnlineTable()
        coordinator.reconcilePresence()

        assertNull(coordinator.onlineHost)
        assertNull(store.stored)
        assertEquals(SessionKind.Menu, hooks.currentKind)
        assertNull(coordinator.presenceClient.snapshot?.self?.table)
        assertEquals(PresenceActivity.Idle, coordinator.presenceClient.snapshot?.self?.activity)
        assertNull(coordinator.homeNotice)
        assertNull(coordinator.lastLoggedTableId)
    }

    @Test
    fun leaveOnlineTableClearsStoreAndRestoresLocal() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)

        coordinator.enterOnlineTable("tbl_42")
        coordinator.leaveOnlineTable()

        assertNull(store.stored)
        assertNull(coordinator.onlineHost)
        assertEquals(1, hooks.localRestoreCalls)
        assertEquals(1, hooks.port.exits)
    }

    @Test
    fun leaveOnlineForLocalPlaySuppressesMenuReturn() {
        val store = FakeOnlineTableStore()
        val ws = FakeWebSocketClient()
        val (coordinator, hooks) = createCoordinator(store = store, ws = ws)

        coordinator.enterOnlineTable("tbl_42")
        coordinator.leaveOnlineForLocalPlay()

        assertNull(coordinator.onlineHost)
        assertEquals("tbl_42", store.stored)
        assertEquals(1, hooks.localRestoreCalls)
        assertEquals(0, hooks.port.exits)
        assertFalse(ws.sentMessages.any { it.contains("\"type\":\"leave_game\"") })
    }

    @Test
    fun parkOnlineTableKeepsSeatAndStore() {
        val store = FakeOnlineTableStore()
        val ws = FakeWebSocketClient()
        val (coordinator, hooks) = createCoordinator(store = store, ws = ws)

        coordinator.enterOnlineTable("tbl_42")
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")
        coordinator.reconcilePresence()
        coordinator.parkOnlineTable()

        assertNull(coordinator.onlineHost)
        assertEquals("tbl_42", store.stored)
        assertEquals("tbl_42", coordinator.lastLoggedTableId)
        assertEquals(0, hooks.port.exits)
        assertFalse(ws.sentMessages.any { it.contains("\"type\":\"leave_game\"") })
    }

    @Test
    fun explicitLeaveNotifiesOnceDespiteHostCallback() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)

        coordinator.enterOnlineTable("tbl_42")
        coordinator.leaveOnlineTable()

        assertEquals(1, hooks.port.exits)
        assertNull(coordinator.onlineHost)
        assertNull(store.stored)
    }

    @Test
    fun reconcilePresenceDoesNotAutoJoinSeatedTableOnHome() {
        val (coordinator, hooks) = createCoordinator()
        hooks.currentKind = SessionKind.Menu
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_auto")

        coordinator.reconcilePresence()

        assertEquals("tbl_auto", coordinator.lastLoggedTableId)
        assertEquals(0, hooks.port.joins)
        assertNull(coordinator.onlineHost)
        assertTrue(coordinator.isSeated())
    }

    @Test
    fun rejoinActiveTableJoinsPresenceSeatWithoutPriorHost() {
        val (coordinator, hooks) = createCoordinator()
        hooks.currentKind = SessionKind.Menu
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_auto")

        coordinator.rejoinActiveTable()

        assertEquals(1, hooks.port.joins)
        assertEquals("tbl_auto", coordinator.onlineHost?.tableId)
    }

    @Test
    fun reconcilePresenceJoinsWhileSearchingEvenIfHomeHidden() {
        val (coordinator, hooks) = createCoordinator()
        hooks.currentKind = SessionKind.Menu
        coordinator.presenceClient.startLooking()
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_search")

        coordinator.reconcilePresence()

        assertNotNull(coordinator.onlineHost)
        assertEquals("tbl_search", coordinator.onlineHost?.tableId)
    }

    @Test
    fun reconcilePresenceDoesNotJoinParkedTableOnMenu() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.parkOnlineTable()
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")

        coordinator.reconcilePresence()

        assertNull(coordinator.onlineHost)
        assertEquals("tbl_42", store.stored)
        assertEquals(SessionKind.Menu, hooks.currentKind)
        assertTrue(coordinator.isSeated())
    }

    @Test
    fun restoredOnlineContextIsAdoptedByTheSeatPresenceReports() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        hooks.currentKind = SessionKind.Online
        store.slot.set("tbl_42")

        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")
        coordinator.reconcilePresence()

        assertEquals(1, hooks.port.joins)
        assertEquals("tbl_42", coordinator.onlineHost?.tableId)
    }

    @Test
    fun restoredOnlineContextWhoseSeatIsGoneComesDown() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        hooks.currentKind = SessionKind.Online
        store.slot.set("tbl_42")

        coordinator.presenceClient.snapshot = idleSnapshot()
        coordinator.reconcilePresence()

        assertNull(coordinator.onlineHost)
        assertNull(store.stored)
        assertEquals(1, hooks.port.exits)
    }

    /**
     * A restored table presence never adopts must not hold the screen: after the
     * deadline the layer comes down and the reservation is kept, so the hub it
     * reveals offers Rejoin.
     */
    @Test
    fun anUnadoptedRestoredTableReturnsToTheHubAfterTheDeadline() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        hooks.currentKind = SessionKind.Online
        store.slot.set("tbl_42")

        coordinator.tick(0L)
        assertEquals(0, hooks.port.exits, "the deadline has not elapsed")

        coordinator.tick(OnlineCoordinator.ADOPTION_TIMEOUT_MS)

        assertEquals(1, hooks.port.exits, "the table layer comes down")
        assertNull(coordinator.onlineHost)
        assertEquals("tbl_42", store.stored, "the reservation is kept for Rejoin")
    }

    @Test
    fun anAdoptedTableNeverHitsTheAdoptionDeadline() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        hooks.currentKind = SessionKind.Online
        coordinator.enterOnlineTable("tbl_42")

        coordinator.tick(0L)
        coordinator.tick(OnlineCoordinator.ADOPTION_TIMEOUT_MS * 10)

        assertEquals(0, hooks.port.exits, "a hosted table is never timed out")
        assertNotNull(coordinator.onlineHost)
    }

    @Test
    fun aDroppedSessionTakesAnUnattachedRestoredTableDown() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        hooks.currentKind = SessionKind.Online
        store.slot.set("tbl_42")

        coordinator.abandonRestoredTable()

        assertNull(store.stored)
        assertEquals(1, hooks.port.exits)
    }

    @Test
    fun aDroppedSessionLeavesALiveMatchToItsHost() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        hooks.currentKind = SessionKind.Online

        coordinator.abandonRestoredTable()

        assertNotNull(coordinator.onlineHost)
        assertEquals(0, hooks.port.exits)
    }

    @Test
    fun onlyARestoredCredentialKeepsTheBootsOnlineTable() {
        // The state every cold start is in: the refresh token came back from the store
        // and the access token is memory-only, so it does not exist until the rotation
        // lands. The old predicate (isSignedIn) was false here, which is how a live seat
        // came to be reported gone without ever being asked about.
        val restored = testAuth()
        restored.applyPersisted(
            refreshToken = "refresh-1",
            user = AuthUser(id = "u1", displayName = "Alice", aliasCustomizedAt = "2026-09-10T12:00:00Z"),
        )
        assertFalse(restored.isSignedIn, "no access token before the rotation")
        assertTrue(createCoordinator(auth = restored).coordinator.canRestoreOnlineTable())

        // Nothing restored: no session is coming, so the boot pops the layer.
        assertFalse(createCoordinator(auth = testAuth()).coordinator.canRestoreOnlineTable())
    }

    @Test
    fun aProcessExitParksTheSeatInsteadOfForfeitingIt() {
        val store = FakeOnlineTableStore()
        val ws = FakeWebSocketClient()
        val (coordinator, _) = createCoordinator(store = store, ws = ws)
        coordinator.enterOnlineTable("tbl_42")

        coordinator.dispose()

        assertFalse(ws.sentMessages.any { it.contains("\"type\":\"leave_game\"") })
        assertEquals("tbl_42", store.stored)
    }

    @Test
    fun rejoinActiveTableJoinsParkedSeat() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.parkOnlineTable()
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")

        coordinator.rejoinActiveTable()

        // Two live-table announcements: the initial enter and the rejoin.
        assertEquals(2, hooks.port.joins)
        assertNotNull(coordinator.onlineHost)
        assertEquals("tbl_42", coordinator.onlineHost?.tableId)
        assertEquals("tbl_42", store.stored)
    }

    @Test
    fun rejoinActiveTableResumesExistingHost() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")
        hooks.currentKind = SessionKind.Menu

        coordinator.rejoinActiveTable()

        assertEquals(2, hooks.port.joins)
        assertEquals("tbl_42", coordinator.onlineHost?.tableId)
    }

    @Test
    fun reconcilePresenceDoesNotJoinOverFreePlay() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.leaveOnlineForLocalPlay()
        hooks.currentKind = SessionKind.FreePlay
        coordinator.presenceClient.snapshot = seatedSnapshot("tbl_42")

        coordinator.reconcilePresence()

        assertNull(coordinator.onlineHost)
        assertEquals(SessionKind.FreePlay, hooks.currentKind)
        assertEquals("tbl_42", store.stored)
    }

    @Test
    fun reconcilePresenceClearsStoreWhenPresenceDropsParkedTable() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        coordinator.parkOnlineTable()
        coordinator.presenceClient.snapshot = idleSnapshot()

        coordinator.reconcilePresence()

        assertNull(store.stored)
        assertNull(coordinator.lastLoggedTableId)
        assertEquals(SessionKind.Menu, hooks.currentKind)
        assertNull(coordinator.onlineHost)
    }

    @Test
    fun reservationKeepsLookingUntilFirstLiveSnapshot() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 1_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)

        presence.start("test_token")
        presence.toggleWaiting()
        coordinator.tick(1_000L)
        sender.completeLast(idleSnapshot())
        assertEquals(PresenceActivity.Waiting, sender.heartbeats.single().activity)

        coordinator.enterOnlineTable("tbl_42")
        val host = coordinator.onlineHost
        assertNotNull(host)
        assertTrue(presence.waitingEnabled)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_42",
                seat = 0,
                rounds = "EastOnly",
                allowChicken = false,
            ),
        )
        assertTrue(presence.waitingEnabled, "Reserved seat still Looking")
        assertEquals(1, sender.heartbeats.size)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(liveSeatSnapshot()))
        assertFalse(presence.waitingEnabled)
        assertEquals(PresenceActivity.Idle, sender.heartbeats.last().activity)
        assertEquals(2, sender.heartbeats.size)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(liveSeatSnapshot()))
        assertFalse(presence.waitingEnabled)
        assertEquals(2, sender.heartbeats.size)
    }

    @Test
    fun startSearchAndCancelSearchAreExplicit() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 1_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")

        coordinator.startSearch()
        coordinator.tick(1_000L)
        assertTrue(presence.waitingEnabled)
        assertEquals(PresenceActivity.Waiting, sender.heartbeats.single().activity)

        coordinator.startSearch()
        assertEquals(1, sender.heartbeats.size, "already searching is a no-op")

        coordinator.cancelSearch()
        sender.completeLast(idleSnapshot())
        assertFalse(presence.waitingEnabled)
        assertEquals(listOf("test_token"), sender.leaves)
        assertEquals(PresenceActivity.Idle, sender.heartbeats.last().activity)
    }

    @Test
    fun cancelSearchWhileParkedRunningSeatDoesNotLeave() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 1_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")
        presence.snapshot = seatedSnapshot("tbl_1")
        assertTrue(coordinator.isSeated())
        assertFalse(presence.waitingEnabled)

        coordinator.cancelSearch()

        assertTrue(sender.leaves.isEmpty())
        assertTrue(coordinator.isSeated())
        assertEquals("tbl_1", presence.snapshot?.self?.table?.id)
        assertEquals(PresenceActivity.Playing, presence.snapshot?.self?.activity)

        coordinator.startSearch()
        assertFalse(presence.waitingEnabled, "Find game is a no-op while seated")
        assertTrue(sender.heartbeats.isEmpty())
    }

    @Test
    fun cancelSearchWhileReservedStillLeaves() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 1_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")
        presence.startLooking()
        presence.snapshot = seatedSnapshot("tbl_reserved")
        assertTrue(coordinator.isSeated())
        assertTrue(presence.waitingEnabled)

        coordinator.cancelSearch()
        sender.completeLast(idleSnapshot())

        assertEquals(listOf("test_token"), sender.leaves)
        assertFalse(presence.waitingEnabled)
        assertNull(presence.snapshot?.self?.table)
    }

    @Test
    fun cyclePrefsAllowedWhileIdleMinOtherAllowedWhileSearchingRoundsChickenFrozen() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 10_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)

        presence.start("test_token")

        // While idle: all cycle methods are allowed
        coordinator.cycleMinOtherHumans()
        coordinator.cycleRounds()
        coordinator.cycleChicken()
        assertEquals(2, presence.minOtherHumans)
        assertEquals(QueueRounds.One, presence.queueRounds)
        assertEquals(QueueChicken.Allowed, presence.queueChicken)

        // Start searching
        coordinator.startSearch()
        assertTrue(presence.waitingEnabled)
        val searchStartTime = presence.searchStartedAtMs
        assertNotNull(searchStartTime)
        // Complete the in-flight heartbeat from startSearch
        sender.completeLast(idleSnapshot())
        val initialHbCount = sender.heartbeats.size

        // While searching: minOtherHumans is allowed, flushes immediately, preserves searchStartedAtMs
        coordinator.cycleMinOtherHumans()
        assertEquals(3, presence.minOtherHumans)
        assertEquals(searchStartTime, presence.searchStartedAtMs)
        assertTrue(sender.heartbeats.size > initialHbCount)
        assertEquals(3, sender.heartbeats.last().minOtherHumans)

        // While searching: rounds and chicken remain frozen
        coordinator.cycleRounds()
        coordinator.cycleChicken()
        assertEquals(QueueRounds.One, presence.queueRounds)
        assertEquals(QueueChicken.Allowed, presence.queueChicken)

        // Once seated: all controls are frozen (including minOtherHumans)
        presence.tick(15_000L, PresenceActivity.Waiting)
        sender.completeLast(seatedSnapshot("tbl_seated"))
        assertTrue(coordinator.isSeated())
        coordinator.cycleMinOtherHumans()
        coordinator.cycleRounds()
        coordinator.cycleChicken()
        assertEquals(3, presence.minOtherHumans)
        assertEquals(QueueRounds.One, presence.queueRounds)
        assertEquals(QueueChicken.Allowed, presence.queueChicken)
    }

    @Test
    fun setMinOtherHumansUpdatesDirectlyAndFlushesWhenSearching() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 10_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)

        presence.start("test_token")

        // While idle: can set directly
        coordinator.setMinOtherHumans(3)
        assertEquals(3, presence.minOtherHumans)
        coordinator.setMinOtherHumans(0)
        assertEquals(0, presence.minOtherHumans)

        // Start searching
        coordinator.startSearch()
        sender.completeLast(idleSnapshot())
        val initialHbCount = sender.heartbeats.size

        // Setting to a new value flushes heartbeat immediately
        coordinator.setMinOtherHumans(2)
        assertEquals(2, presence.minOtherHumans)
        assertTrue(sender.heartbeats.size > initialHbCount)
        assertEquals(2, sender.heartbeats.last().minOtherHumans)

        // Setting to same value does not re-flush
        val hbCountBefore = sender.heartbeats.size
        coordinator.setMinOtherHumans(2)
        assertEquals(hbCountBefore, sender.heartbeats.size)

        // Once seated: frozen
        presence.tick(15_000L, PresenceActivity.Waiting)
        sender.completeLast(seatedSnapshot("tbl_seated"))
        assertTrue(coordinator.isSeated())
        coordinator.setMinOtherHumans(0)
        assertEquals(2, presence.minOtherHumans)
    }

    @Test
    fun abortedTableReturnsHomeWithNotice() {
        val store = FakeOnlineTableStore()
        val (coordinator, hooks) = createCoordinator(store = store)
        coordinator.enterOnlineTable("tbl_42")
        hooks.currentKind = SessionKind.Online
        val host = coordinator.onlineHost
        assertNotNull(host)

        host.handleServerMessage(ServerMessageWire.TableAborted("Player disconnected"))
        coordinator.reconcilePresence()

        assertNull(coordinator.onlineHost)
        assertNull(store.stored)
        assertEquals(1, hooks.port.exits)
        assertEquals(OnlineCoordinator.HomeNoticeKind.TableAborted, coordinator.homeNotice?.kind)
        assertEquals("Player disconnected", coordinator.homeNotice?.detail)
    }

    @Test
    fun unavailableTableLeaveSetsNoticeAndStartSearchClearsIt() {
        val store = FakeOnlineTableStore()
        val hooks = TestHooks()
        val auth = testAuth()
        auth.signIn(
            AuthLogin(
                token = "test_token",
                user = AuthUser(
                    id = "u1",
                    displayName = "Alice",
                    aliasCustomizedAt = "2026-09-10T12:00:00Z",
                    startingLevelSetAt = "2026-09-11T12:00:00Z",
                ),
            ),
        )
        val coordinator = OnlineCoordinator(
            authSession = auth,
            presenceClient = testPresence(api = RecordingPresenceSender()),
            onlineTableId = store.slot,
            notificationPrefs = testNotificationPrefs(),
            hostFactory = { tableId, token, _, onLeave ->
                OnlinePlayHost(
                    tableId = tableId,
                    token = token,
                    wsClient = FakeWebSocketClient(),
                    onLeaveCallback = onLeave,
                    requestTicket = { _, _, onDone ->
                        onDone(Result.failure(TableTicketException(404, "not found")))
                    },
                )
            },
            away = FakeAwayPort(),
            sessionKind = hooks::sessionKind,
            tablePort = hooks.port,
            onRestoreLocalHost = hooks::onRestoreLocalHost,
            onStartingLevelPromptRequested = hooks::onStartingLevelPromptRequested,
            onSettledMatch = hooks::onSettledMatch,
        )
        coordinator.enterOnlineTable("tbl_gone")
        val host = coordinator.onlineHost
        assertNotNull(host)
        host.advance(0.016f)
        assertEquals(
            ConnectionFailure.TableUnavailable,
            host.connectionFailure,
        )

        coordinator.leaveOnlineTable()
        assertEquals(OnlineCoordinator.HomeNoticeKind.GameUnavailable, coordinator.homeNotice?.kind)

        coordinator.startSearch()
        assertNull(coordinator.homeNotice)
        assertTrue(coordinator.presenceClient.waitingEnabled)
    }

    @Test
    fun awayOverlayShownOnResumeIfWasAwayAndDismissedOnResumeFromAway() {
        val away = FakeAwayPort()
        val (coordinator, _) = createCoordinator(away = away)
        coordinator.enterOnlineTable("tbl_1")
        assertFalse(away.visible)

        coordinator.onAppPaused()
        assertEquals(true, coordinator.onlineHost?.wasAway)

        coordinator.onAppResumed()
        assertTrue(away.visible)

        coordinator.resumeFromAway()
        assertFalse(away.visible)
        assertNotEquals(coordinator.onlineHost?.wasAway, true)
    }

    @Test
    fun enterOnlineTableStartsAwayWhenAppIsPaused() {
        val (coordinator, _) = createCoordinator()
        coordinator.onAppPaused()
        assertTrue(coordinator.isAppPaused)
        assertFalse(coordinator.isAppVisible())

        coordinator.enterOnlineTable("tbl_paused")
        val host = coordinator.onlineHost
        assertNotNull(host)
        assertTrue(host.wasAway)
    }

    @Test
    fun enterOnlineTableStartsAwayWhenPresenceSchedulerReportsHidden() {
        val hiddenScheduler = object : inego.zungjunggame.platform.PresenceScheduler {
            override fun isAppVisible(): Boolean = false
            override fun schedulePeriodic(intervalMs: Long, task: () -> Unit) = com.badlogic.gdx.utils.Disposable { }
            override fun scheduleAfter(delayMs: Long, task: () -> Unit) = com.badlogic.gdx.utils.Disposable { }
            override fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit) = com.badlogic.gdx.utils.Disposable { }
        }
        val (coordinator, _) = createCoordinator(presenceScheduler = hiddenScheduler)
        assertFalse(coordinator.isAppVisible())

        coordinator.enterOnlineTable("tbl_hidden")
        val host = coordinator.onlineHost
        assertNotNull(host)
        assertTrue(host.wasAway)
    }

    @Test
    fun startSearchGatedWhenStartingLevelUnset() {
        val hooks = TestHooks()
        val (coordinator, _) = createCoordinator(hooks = hooks)
        coordinator.authSession.updateUser(
            coordinator.authSession.user!!.copy(startingLevelSetAt = null),
        )

        coordinator.startSearch()

        assertFalse(coordinator.presenceClient.waitingEnabled)
        assertTrue(hooks.startingLevelPromptRequested)
    }

    @Test
    fun toggleWaitingGatedWhenStartingLevelUnset() {
        val hooks = TestHooks()
        val (coordinator, _) = createCoordinator(hooks = hooks)
        coordinator.authSession.updateUser(
            coordinator.authSession.user!!.copy(startingLevelSetAt = null),
        )

        coordinator.toggleWaiting()

        assertFalse(coordinator.presenceClient.waitingEnabled)
        assertTrue(hooks.startingLevelPromptRequested)
    }

    @Test
    fun reconcilePresenceStopsSearchWhenNeedsStartingLevel() {
        val hooks = TestHooks()
        val (coordinator, _) = createCoordinator(hooks = hooks)
        coordinator.presenceClient.startLooking()
        coordinator.presenceClient.snapshot = PresenceSnapshot(
            online = 1,
            playing = 0,
            waiting = 0,
            self = PresenceSelf("u1", "Alice", PresenceActivity.Idle),
            needsStartingLevel = true,
        )

        coordinator.reconcilePresence()

        assertFalse(coordinator.presenceClient.waitingEnabled)
        assertTrue(hooks.startingLevelPromptRequested)
    }

    // A server-side matchmaking cooldown must never be silently ignored.

    private fun cooldownSnapshot(seconds: Int) = PresenceSnapshot(
        online = 1,
        playing = 0,
        waiting = 0,
        self = PresenceSelf(
            userId = "u1",
            displayName = "Alice",
            activity = PresenceActivity.Idle,
            matchmakingCooldown = MatchmakingCooldown(seconds, "forfeit"),
        ),
    )

    @Test
    fun findGameWhileCoolingDownIsExplainedInsteadOfSearching() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 10_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")
        presence.snapshot = cooldownSnapshot(60)

        coordinator.startSearch()

        assertFalse(presence.waitingEnabled, "the search is not armed")
        assertEquals(0, sender.heartbeats.size, "and nothing is asked of the server")
        assertEquals(
            OnlineCoordinator.HomeNoticeKind.MatchmakingCooldown,
            coordinator.homeNotice?.kind,
        )
        assertEquals("60", coordinator.homeNotice?.detail)

        // The same tap through the keyboard path.
        coordinator.toggleWaiting()
        assertFalse(presence.waitingEnabled)
        assertEquals(0, sender.heartbeats.size)
        assertEquals(
            OnlineCoordinator.HomeNoticeKind.MatchmakingCooldown,
            coordinator.homeNotice?.kind,
        )
    }

    @Test
    fun aSearchRefusedByTheServerStopsLocallyAndSaysWhy() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 10_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")

        // The tap believes it is free — the cooldown was not in the previous snapshot — so the
        // request goes out, and the answer is what refuses it.
        coordinator.startSearch()
        assertTrue(presence.waitingEnabled)
        assertEquals(1, sender.heartbeats.size)

        sender.completeLast(cooldownSnapshot(30))

        assertFalse(presence.waitingEnabled, "a refused search does not keep spinning")
        assertNull(presence.searchStartedAtMs)
        assertEquals(
            OnlineCoordinator.HomeNoticeKind.MatchmakingCooldown,
            coordinator.homeNotice?.kind,
        )
        assertEquals("30", coordinator.homeNotice?.detail)
    }

    private fun shutdownSnapshot(
        phase: String = "warning",
        remainingMs: Long = 120_000L,
        message: String? = null,
    ) = PresenceSnapshot(
        online = 1,
        playing = 0,
        waiting = 0,
        self = PresenceSelf("u1", "Alice", PresenceActivity.Idle),
        serverShutdown = inego.zungjunggame.net.PresenceServerShutdown(phase, remainingMs, message),
    )

    @Test
    fun serverShutdownInPresenceSnapshotStopsActiveSearch() {
        val sender = RecordingPresenceSender()
        val presence = testPresence(api = sender, clock = { 10_000L })
        val (coordinator, _) = createCoordinator(presenceClient = presence)
        presence.start("test_token")

        coordinator.startSearch()
        assertTrue(presence.waitingEnabled)
        assertEquals(1, sender.heartbeats.size)

        // Presence snapshot arrives indicating server shutdown
        sender.completeLast(shutdownSnapshot(phase = "warning", remainingMs = 60_000L, message = "Restarting"))

        assertFalse(presence.waitingEnabled, "search stopped on server shutdown")
        assertTrue(coordinator.isServerShutdown())
    }

    @Test
    fun hostExitDueToShutdownSetsServerShutdownNoticeOnHome() {
        val fakeWs = FakeWebSocketClient()
        val (coordinator, hooks) = createCoordinator(ws = fakeWs)
        coordinator.enterOnlineTable("tbl-shutdown")
        val host = coordinator.onlineHost as OnlinePlayHost

        // Server announces shutdown and socket drops
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "final",
                remainingMs = 10_000L,
                message = "Maintenance in progress",
            )
        )
        fakeWs.close(1001, "Server shutdown")
        assertTrue(host.shutdownEndedMatch)

        // reconcilePresence detects shutdownEndedMatch and leaves to menu
        coordinator.reconcilePresence()

        assertEquals(SessionKind.Menu, hooks.sessionKind())
        assertEquals(
            OnlineCoordinator.HomeNoticeKind.ServerShutdown,
            coordinator.homeNotice?.kind,
        )
        assertEquals("Maintenance in progress", coordinator.homeNotice?.detail)
    }
}

/** An auth session over in-memory storage: these tests never need it to persist. */
private fun testAuth(): AuthSession = AuthSession(
    StateSlot(DefaultStateStore(InMemoryStateBackend()), AuthPersistedSessionKey),
)

/** The credential a signed-in client holds: access token and profile in memory. */
private fun signedInTestAuth(): AuthSession = testAuth().apply {
    signIn(
        AuthLogin(
            token = "test_token",
            user = AuthUser(
                id = "u1",
                displayName = "Alice",
                aliasCustomizedAt = "2026-09-10T12:00:00Z",
                startingLevelSetAt = "2026-09-11T12:00:00Z",
            ),
        ),
    )
}
