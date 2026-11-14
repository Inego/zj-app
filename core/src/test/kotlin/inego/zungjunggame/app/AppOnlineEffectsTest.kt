package inego.zungjunggame.app

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.features.online.hub.OnlineHubCodec
import inego.zungjunggame.features.online.hub.OnlineHubState
import inego.zungjunggame.net.AuthLogin
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.net.ChallengeRounds
import inego.zungjunggame.net.ChallengeStart
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.PresenceActivity
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.net.PresenceSender
import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.net.ShadowRankedAttemptsClient
import inego.zungjunggame.net.ShadowRankedHandsClient
import inego.zungjunggame.net.ShadowRankedStartClient
import inego.zungjunggame.net.ShadowRankedStartWire
import inego.zungjunggame.online.AccountController
import inego.zungjunggame.online.FakePlayHost
import inego.zungjunggame.online.OnlineAwayPort
import inego.zungjunggame.online.OnlineCoordinator
import inego.zungjunggame.online.OnlineTablePort
import inego.zungjunggame.online.ShadowRankedStarter
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.persist.DismissedFirstLoginPromptsKey
import inego.zungjunggame.persist.FindGamePreferencesKey
import inego.zungjunggame.persist.NotificationPrefsKey
import inego.zungjunggame.persist.OnlineTableIdKey
import inego.zungjunggame.platform.NoOpPresenceScheduler
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hub-leave search policy.
 *
 * The real [OnlineCoordinator] and a real [ScreenTransitions]/`Router` are driven
 * through [AppOnlineEffects], so the assertions are the observable interaction —
 * the presence search state and the stack — not a restatement of the one line that
 * cancels. The hub's load seam has its own test; this one covers the leave side.
 */
class AppOnlineEffectsTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private class RecordingPresenceSender : PresenceSender {
        val leaves = mutableListOf<String>()

        override fun heartbeat(
            token: String,
            activity: PresenceActivity,
            minOtherHumans: Int?,
            rounds: QueueRounds?,
            chicken: QueueChicken?,
            onDone: (Result<inego.zungjunggame.net.PresenceSnapshot>) -> Unit,
        ) {}

        override fun leave(token: String, onDone: (Result<Unit>) -> Unit) {
            leaves += token
            onDone(Result.success(Unit))
        }
    }

    /** Records the wire start a rematch issues; the request itself is irrelevant. */
    private class RecordingStartWire : ShadowRankedStartWire {
        val starts = mutableListOf<Triple<String, ChallengeRounds, Boolean>>()

        override fun start(
            token: String,
            rounds: ChallengeRounds,
            allowChicken: Boolean,
            onDone: (Result<ChallengeStart>) -> Unit,
        ) {
            starts += Triple(token, rounds, allowChicken)
        }
    }

    private class FakeView : AccountController.View {
        override fun showPresence() {}
        override fun hidePresence() {}
    }

    private class Env {
        private val router = Router(
            Route.Screen(RouteIds.ROOT_MENU),
            { StubComponent(ComponentId(it.key.value)) },
            { RouteSpecs.specFor(it) },
        )
        private val auth = AuthSession(
            StateSlot(DefaultStateStore(InMemoryStateBackend()), AuthPersistedSessionKey),
        )
        val sender = RecordingPresenceSender()
        val presence = PresenceClient(
            api = sender,
            clock = { 0L },
            findPreferences = StateSlot(
                DefaultStateStore(InMemoryStateBackend()),
                FindGamePreferencesKey,
            ),
        )
        private val coordinator = OnlineCoordinator(
            authSession = auth,
            presenceClient = presence,
            onlineTableId = StateSlot(
                DefaultStateStore(InMemoryStateBackend()),
                OnlineTableIdKey,
            ),
            notificationPrefs = NotificationPrefsClient(
                slot = StateSlot(DefaultStateStore(InMemoryStateBackend()), NotificationPrefsKey),
            ),
            presenceScheduler = NoOpPresenceScheduler,
            away = object : OnlineAwayPort {
                override fun show() {}
                override fun hide() {}
            },
            sessionKind = { SessionKind.Menu },
            tablePort = object : OnlineTablePort {
                override fun tableJoined(origin: OnlineTab) {}
                override fun tableExited() {}
            },
            onRestoreLocalHost = { FakePlayHost() },
        )
        private val routes = RouteController(router)
        private val account = AccountController(
            authSession = auth,
            presenceClient = presence,
            view = FakeView(),
            profileRequester = { _, _ -> },
            firstLoginPrompts = StateSlot(
                DefaultStateStore(InMemoryStateBackend()),
                DismissedFirstLoginPromptsKey,
            ),
        )
        val routeUi = RouteUiState()
        private val transitions = ScreenTransitions(routes)
        val startWire = RecordingStartWire()
        private val registry = SessionRegistry(
            stack = { router.stack },
            localHostSource = { null },
            tutorialHostSource = { null },
            onlineHostSource = { coordinator.onlineHost },
            handSource = { null },
        )
        val effects = AppOnlineEffects(
            onlineCoordinator = coordinator,
            accountController = account,
            sessionRegistry = registry,
            routes = routes,
            transitions = transitions,
            routeUi = routeUi,
            shadowRankedStarter = ShadowRankedStarter(
                token = { auth.token },
                client = ShadowRankedStartClient(sender = startWire),
            ),
            shadowRankedAttempts = ShadowRankedAttemptsClient(),
            shadowRankedHands = ShadowRankedHandsClient(),
        )

        fun startSearching() {
            // The hub is a signed-in surface, so a search under way implies a
            // session; the leave policy below is the subject here.
            signIn()
            presence.start("tok")
            presence.startLooking()
            effects.hubOpen(OnlineTab.Live)
        }

        fun signIn() {
            auth.signIn(
                AuthLogin(
                    token = "tok",
                    refreshToken = "refresh",
                    user = AuthUser(
                        id = "u1",
                        displayName = "Player",
                        aliasCustomizedAt = "2020-01-01T00:00:00Z",
                        startingLevelSetAt = "2020-01-01T00:00:00Z",
                    ),
                ),
            )
        }

        /** Put a live table on the stack the way its own join did. */
        fun enterTable(origin: OnlineTab) = transitions.onlineTable(origin)

        fun onHub(): Boolean = router.stack.contains(Route.Layer(RouteIds.ONLINE_HUB).key)
    }

    @Test
    fun switchingTabsKeepsTheLiveSearchRunning() {
        val env = Env()
        env.startSearching()

        env.effects.hubSelectTab(OnlineTab.ShadowRanked)

        assertTrue(env.presence.waitingEnabled, "a tab switch is not a leave")
        assertTrue(env.onHub(), "the hub stays up")
        assertEquals(0, env.sender.leaves.size, "no leave was sent")
    }

    /**
     * The first tab switch is the one that writes the hub's state, so its null
     * fallback must be the hub's default (Live), not the requested tab — otherwise
     * the switch is a no-op and the hub is stuck on Live for good.
     */
    @Test
    fun selectingATabStoresItBeforeAnyStateWasWritten() {
        val env = Env()
        val key = Route.Layer(RouteIds.ONLINE_HUB).key

        env.effects.hubSelectTab(OnlineTab.ShadowRanked)

        assertEquals(
            OnlineTab.ShadowRanked,
            assertNotNull(env.routeUi.read(key, OnlineHubCodec), "the switch was stored").tab,
        )
    }

    @Test
    fun leavingTheHubCancelsTheLiveSearch() {
        val env = Env()
        env.startSearching()
        assertTrue(env.presence.waitingEnabled)

        env.effects.hubLeave()

        assertFalse(env.presence.waitingEnabled, "leaving the screen ends the search")
        assertFalse(env.onHub(), "the hub is dismissed")
        assertEquals(listOf("tok"), env.sender.leaves, "the search leave was sent")
    }

    @Test
    fun playAgainFromAShadowRankedTableStartsAnotherAttemptWithTheDraft() {
        val env = Env()
        env.signIn()
        val draft = GameConfig(rounds = 2, allowChickenHands = true)
        env.routeUi.write(
            Route.Layer(RouteIds.ONLINE_HUB).key,
            OnlineHubCodec,
            OnlineHubState(tab = OnlineTab.ShadowRanked, draft = draft),
        )
        env.enterTable(OnlineTab.ShadowRanked)

        env.effects.playAgain()

        assertEquals(
            listOf(Triple("tok", ChallengeRounds.Two, true)),
            env.startWire.starts,
            "the rematch starts another attempt from the remembered draft",
        )
        assertFalse(
            env.presence.waitingEnabled,
            "a Shadow Ranked rematch must not start a Live search",
        )
    }

    @Test
    fun playAgainFromALiveTableSearchesAgain() {
        val env = Env()
        env.signIn()
        env.enterTable(OnlineTab.Live)

        env.effects.playAgain()

        assertTrue(env.presence.waitingEnabled, "a Live table rematches by searching again")
        assertEquals(emptyList(), env.startWire.starts, "no Shadow Ranked start for a Live table")
    }

    @Test
    fun loggingOutResetsTheHubTabToLiveAndKeepsTheDraft() {
        val env = Env()
        val key = Route.Layer(RouteIds.ONLINE_HUB).key
        val draft = GameConfig(rounds = 2, allowChickenHands = true)
        env.routeUi.write(
            key,
            OnlineHubCodec,
            OnlineHubState(
                tab = OnlineTab.ShadowRanked,
                liveScroll = 4f,
                shadowScroll = 12f,
                draft = draft,
            ),
        )

        env.effects.resetHubTabForAccountChange()

        val after = assertNotNull(
            env.routeUi.read(key, OnlineHubCodec),
            "the hub state survives the account reset",
        )
        assertEquals(OnlineTab.Live, after.tab, "a new account must not inherit the last-read tab")
        assertEquals(draft, after.draft, "the draft is not account state")
        assertEquals(12f, after.shadowScroll)
    }
}
