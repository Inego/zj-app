package inego.zungjunggame.online

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.AuthException
import inego.zungjunggame.net.AuthLogin
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.net.ChallengeEntry
import inego.zungjunggame.net.ChallengeList
import inego.zungjunggame.net.ChallengeRounds
import inego.zungjunggame.net.ChallengeStart
import inego.zungjunggame.net.ChallengeStatus
import inego.zungjunggame.net.GoogleChallenge
import inego.zungjunggame.net.GoogleVerifier
import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.NotificationPrefsReader
import inego.zungjunggame.net.NotificationPrefsWriter
import inego.zungjunggame.net.unsignedAccessJwt
import inego.zungjunggame.net.PresenceActivity
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.net.PresenceSender
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.net.QueueChicken
import inego.zungjunggame.net.QueueRounds
import inego.zungjunggame.net.ShadowRankedAttemptsClient
import inego.zungjunggame.net.ShadowRankedAttemptsWire
import inego.zungjunggame.net.ShadowRankedStartClient
import inego.zungjunggame.net.ShadowRankedStartWire
import inego.zungjunggame.persist.FindGamePreferences
import inego.zungjunggame.persist.FindGamePreferencesKey
import inego.zungjunggame.persist.AuthPersistedSession
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.persist.DismissedFirstLoginPromptsKey
import inego.zungjunggame.persist.NotificationPrefsKey
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.PlatformTimeZone
import inego.zungjunggame.platform.GoogleSignInCancelled
import inego.zungjunggame.platform.GoogleSignInException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePresenceSender : PresenceSender {
    val leaves = mutableListOf<String>()
    override fun heartbeat(
        token: String,
        activity: PresenceActivity,
        minOtherHumans: Int?,
        rounds: QueueRounds?,
        chicken: QueueChicken?,
        onDone: (Result<PresenceSnapshot>) -> Unit,
    ) {}

    override fun leave(token: String, onDone: (Result<Unit>) -> Unit) {
        leaves.add(token)
        onDone(Result.success(Unit))
    }
}

class FakeAccountView : AccountController.View {
    var presenceShown: Boolean = false
    var presenceHidden: Boolean = false
    var firstLoginShownName: String? = null
    var firstLoginHidden: Boolean = false
    var firstLoginBusy: Boolean = false
    var firstLoginError: String? = null
    override var firstLoginAliasText: String = ""

    override val isFirstLoginAliasVisible: Boolean get() = firstLoginShownName != null && !firstLoginHidden
    override val isFirstLoginAliasBusy: Boolean get() = firstLoginBusy
    override fun showFirstLoginAlias(displayName: String) {
        firstLoginShownName = displayName
        firstLoginHidden = false
        firstLoginAliasText = displayName
        firstLoginError = null
    }
    override fun hideFirstLoginAlias() {
        firstLoginHidden = true
        firstLoginBusy = false
    }
    override fun setFirstLoginAliasBusy(busy: Boolean) {
        firstLoginBusy = busy
        if (busy) firstLoginError = null
    }
    override fun setFirstLoginAliasError(message: String) {
        firstLoginBusy = false
        firstLoginError = message
    }

    var aliasEditVisible: Boolean = false
    var aliasBusyState: Boolean = false
    var aliasErrorSet: String? = null
    override var aliasEditText: String = ""
    override val isAliasEditVisible: Boolean get() = aliasEditVisible
    override val isAliasEditBusy: Boolean get() = aliasBusyState
    override fun showAliasEdit(displayName: String) {
        aliasEditVisible = true
        aliasBusyState = false
        aliasErrorSet = null
        aliasEditText = displayName
    }
    override fun cancelAliasEdit() {
        aliasEditVisible = false
        aliasBusyState = false
        aliasErrorSet = null
    }
    override fun setAliasEditBusy(busy: Boolean) {
        aliasBusyState = busy
        if (busy) aliasErrorSet = null
    }
    override fun setAliasEditError(message: String) {
        aliasBusyState = false
        aliasErrorSet = message
    }

    override fun showPresence() {
        presenceShown = true
    }

    override fun hidePresence() {
        presenceHidden = true
    }

    var menuHidden: Boolean = false

    override fun hideMenu() {
        menuHidden = true
    }

    /** A live session floor is up; the account must not cover it. */
    var entrySurfaceFree: Boolean = true

    override fun isEntrySurfaceFree(): Boolean = entrySurfaceFree

    var startingLevelShown: Boolean = false
    var startingLevelHidden: Boolean = false
    var startingLevelBusyState: Boolean = false
    var startingLevelErrorSet: String? = null

    override val isStartingLevelVisible: Boolean get() = startingLevelShown && !startingLevelHidden
    override val isStartingLevelBusy: Boolean get() = startingLevelBusyState
    override fun showStartingLevel() {
        startingLevelShown = true
        startingLevelHidden = false
        startingLevelErrorSet = null
    }
    override fun hideStartingLevel() {
        startingLevelHidden = true
        startingLevelBusyState = false
    }
    override fun setStartingLevelBusy(busy: Boolean) {
        startingLevelBusyState = busy
        if (busy) startingLevelErrorSet = null
    }
    override fun setStartingLevelError(message: String) {
        startingLevelBusyState = false
        startingLevelErrorSet = message
    }
}

class FakeGoogleAuthProvider(
    override val clientId: String = "web-client.apps.googleusercontent.com",
    override var isAvailable: Boolean = true,
) : GoogleAuthProvider {
    var lastNonce: String? = null
    var cancelCount = 0
    var clearCount = 0
    var pendingIdToken: ((Result<String>) -> Unit)? = null
    var autoIdToken: String? = "id-token"

    override fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit) {
        lastNonce = nonce
        pendingIdToken = onDone
        autoIdToken?.let { onDone(Result.success(it)) }
    }

    override fun cancel() {
        cancelCount += 1
        pendingIdToken = null
    }

    override fun clearCredentialState() {
        clearCount += 1
    }
}

class AccountControllerTest {
    /** Each test gets its own first-login slots. */
    @BeforeTest
    fun clearFirstLoginSlots() {
        firstLoginSlots.clear()
    }


    private fun testUser(
        id: String = "u1",
        startingLevelSetAt: String? = "2026-09-11T12:00:00Z",
    ) = AuthUser(
        id = id,
        displayName = "Alice",
        aliasCustomizedAt = "2026-09-10T12:00:00Z",
        startingLevelSetAt = startingLevelSetAt,
    )

    private fun googleUser(
        displayName: String = "user-k7m4p9q2",
        aliasCustomizedAt: String? = null,
        startingLevelSetAt: String? = "2026-09-11T12:00:00Z",
    ) = AuthUser(
        id = "g1",
        displayName = displayName,
        aliasCustomizedAt = aliasCustomizedAt,
        startingLevelSetAt = startingLevelSetAt,
    )

    private fun createPresenceClient(
        sender: FakePresenceSender = FakePresenceSender(),
        prefs: StateSlot<FindGamePreferences> = findSlot(),
    ) = PresenceClient(
        api = sender,
        clock = { 1000L },
        findPreferences = prefs,
    )

    @Test
    fun restoreSessionStartsPresenceWhenTokenPresent() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "restored_tok", user = testUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val logs = mutableListOf<String>()

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            logLifecycle = { logs.add(it) },
        )

        controller.restoreSession()

        assertTrue(presence.signedIn)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertTrue(logs.any { it.contains("presence start") })
        // The stack owns the screen: the account restore never moves it, so no
        // lobby is presented and the menu the boot chose stays up.
        assertFalse(view.presenceShown)
        assertFalse(view.menuHidden)
    }

    @Test
    fun restoreSessionStartsPresenceWithoutCoveringARestoredSession() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "restored_tok", user = testUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView().apply { entrySurfaceFree = false }

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.restoreSession()

        // Presence still comes up; the screen belongs to the restored game.
        assertTrue(presence.signedIn)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertFalse(view.presenceShown, "the lobby must not cover a live game")
        assertFalse(view.menuHidden, "the restored screen is not the menu")
    }

    @Test
    fun restoreSessionDoesNotPromptForAccountSetupOverARestoredSession() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "restored_tok", user = googleUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView().apply { entrySurfaceFree = false }

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.restoreSession()

        // Answering the alias card would move the player to the lobby and drop
        // the game they are in, so no card is raised over a session screen.
        assertFalse(controller.awaitingFirstLoginAlias)
        assertNull(view.firstLoginShownName)
        assertTrue(presence.signedIn)
    }

    @Test
    fun restoreSessionRotatesStoredSessionOnReload() {
        val slot = authSlot()
        AuthSession(slot).signIn(AuthLogin("access-1", testUser(), "ref-1"))

        // A restart: only the refresh token survives in the store. The boot reads
        // it back before the account surfaces restore (AppGraph does this before
        // the online-table probe), so the session has no access token here.
        val auth = AuthSession(slot)
        auth.load(slot.get())

        val presence = createPresenceClient()
        var sentRefresh: String? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            refreshRequester = { token, done ->
                sentRefresh = token
                done(Result.success(AuthLogin("access-2", testUser(), "ref-2")))
            },
        )

        controller.restoreSession()

        assertEquals("ref-1", sentRefresh)
        assertEquals("access-2", auth.token)
        assertEquals("ref-2", auth.refreshToken)
        assertEquals(
            "ref-2",
            assertNotNull(slot.get()).refreshToken,
            "the rotated token must persist for the next reload",
        )
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertTrue(presence.signedIn)
    }

    @Test
    fun restoreSessionNoOpWhenNotSignedIn() {
        val auth = testAuth()
        val presence = createPresenceClient()
        val view = FakeAccountView()

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.restoreSession()

        assertFalse(presence.signedIn)
    }

    @Test
    fun logOutStopsPresenceAndClearsCredentials() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val sender = FakePresenceSender()
        val presence = createPresenceClient(sender)
        presence.start("tok")
        val view = FakeAccountView()
        val logs = mutableListOf<String>()
        var logoutToken: String? = null
        var droppedSockets = 0

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            logoutRequester = { token, done ->
                logoutToken = token
                done(Result.success(Unit))
            },
            logLifecycle = { logs.add(it) },
            onLogOut = { droppedSockets += 1 },
        )

        controller.logOut()

        assertFalse(controller.isSignedIn)
        assertNull(controller.token)
        assertFalse(presence.signedIn)
        assertTrue(sender.leaves.contains("tok"))
        assertTrue(view.presenceHidden)
        assertEquals("tok", logoutToken)
        assertEquals(1, droppedSockets)
        assertTrue(logs.any { it.contains("presence stop logout") })
    }

    @Test
    fun logOutCancelsInFlightRefresh() {
        val auth = testAuth()
        auth.signIn(AuthLogin("old-tok", testUser(), "ref-1"))
        val presence = createPresenceClient()
        presence.start("old-tok")
        var complete: ((Result<AuthLogin>) -> Unit)? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            refreshRequester = { _, done -> complete = done },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.onUnauthorized()
        assertEquals(AuthLifecycle.Refreshing, controller.lifecycle)

        controller.logOut()
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertFalse(controller.isSignedIn)

        complete!!(Result.success(AuthLogin("new-tok", testUser(), "ref-2")))
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertNull(controller.token)
    }

    @Test
    fun logOutClearsShadowRankedAttempts() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val attempts = ShadowRankedAttemptsClient(
            api = object : ShadowRankedAttemptsWire {
                override fun list(token: String, onDone: (Result<ChallengeList>) -> Unit) {
                    onDone(Result.success(ChallengeList(listOf(attemptRow()))))
                }
            },
        )
        attempts.load("tok")
        assertEquals(1, attempts.entries.size)
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            shadowRankedAttempts = attempts,
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.logOut()

        assertTrue(
            attempts.entries.isEmpty(),
            "a second account must not see the first's attempts",
        )
        assertFalse(attempts.loading)
        assertNull(attempts.error)
    }

    @Test
    fun logOutResetsTheShadowRankedStarterToIdle() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val starter = ShadowRankedStarter(
            token = { "tok" },
            client = ShadowRankedStartClient(
                sender = object : ShadowRankedStartWire {
                    override fun start(
                        token: String,
                        rounds: ChallengeRounds,
                        allowChicken: Boolean,
                        onDone: (Result<ChallengeStart>) -> Unit,
                    ) {}
                },
            ),
        )
        starter.start(GameConfig(rounds = 2))
        assertEquals(ShadowRankedStartState.Starting, starter.snapshot())
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            shadowRankedStarter = starter,
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.logOut()

        assertEquals(ShadowRankedStartState.Idle, starter.snapshot())
    }

    @Test
    fun logOutResetsTheMatchmakingSelection() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val slot = findSlot()
        val presence = createPresenceClient(prefs = slot)
        presence.setMinOtherHumans(3)
        presence.cycleRounds()
        presence.cycleChicken()
        assertEquals(3, presence.minOtherHumans)
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.logOut()

        assertEquals(FindGamePreferences.DEFAULT_MIN_OTHER_HUMANS, presence.minOtherHumans)
        assertEquals(FindGamePreferences.DEFAULT_ROUNDS, presence.queueRounds)
        assertEquals(FindGamePreferences.DEFAULT_CHICKEN, presence.queueChicken)
    }

    @Test
    fun onUnauthorizedSignsOutAndHidesPresence() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val logs = mutableListOf<String>()

        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            logLifecycle = { logs.add(it) },
        )

        // Simulate presence 401 callback triggering
        presence.onUnauthorized?.invoke("tok")

        assertFalse(controller.isSignedIn)
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertTrue(view.presenceHidden)
        assertTrue(logs.any { it.contains("401 → signOut") })
    }

    @Test
    fun concurrentUnauthorizedCallsCoalesceIntoOneRefreshForAllSurfaces() {
        val auth = testAuth()
        auth.signIn(AuthLogin("old-tok", testUser(), "ref-1"))
        val presence = createPresenceClient()
        presence.start("old-tok")
        val prefsReads = mutableListOf<String>()
        val prefs = NotificationPrefsClient(
            slot = notificationPrefsSlot(),
            reader = object : NotificationPrefsReader {
                override fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit) {
                    prefsReads += token
                    onDone(Result.success(NotificationPrefs(emailOptIn = false, digestIntervalHours = 24)))
                }
            },
            writer = object : NotificationPrefsWriter {
                override fun put(
                    token: String,
                    prefs: NotificationPrefs,
                    onDone: (Result<NotificationPrefs>) -> Unit,
                ) {
                    onDone(Result.success(prefs))
                }
            },
        )
        val attemptReads = mutableListOf<String>()
        val attempts = ShadowRankedAttemptsClient(
            api = object : ShadowRankedAttemptsWire {
                override fun list(token: String, onDone: (Result<ChallengeList>) -> Unit) {
                    attemptReads += token
                    onDone(Result.success(ChallengeList(emptyList())))
                }
            },
        )
        val view = FakeAccountView()
        var sendCount = 0
        var complete: ((Result<AuthLogin>) -> Unit)? = null
        val logs = mutableListOf<String>()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            shadowRankedAttempts = attempts,
            notificationPrefs = prefs,
            refreshRequester = { token, done ->
                sendCount += 1
                assertEquals("ref-1", token)
                complete = done
            },
            logLifecycle = { logs.add(it) },
        )

        // The first 401 starts the refresh; the two later ones arrive while it is
        // in flight and must join it rather than silently doing nothing.
        presence.onUnauthorized?.invoke("old-tok")
        prefs.onUnauthorized?.invoke("old-tok")
        attempts.onUnauthorized?.invoke("old-tok")

        assertEquals(1, sendCount, "concurrent 401s send one refresh")
        assertEquals(AuthLifecycle.Refreshing, controller.lifecycle)
        assertEquals(3, logs.count { it.contains("401 → refresh") }, "every surface reaches the owner")

        complete!!(Result.success(AuthLogin("new-tok", testUser(), "ref-2")))

        assertEquals("new-tok", controller.token)
        assertEquals("ref-2", auth.refreshToken)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertTrue(presence.signedIn)
        assertEquals(listOf("new-tok"), prefsReads, "a joined surface reloads under the rotated token")
        assertEquals(listOf("new-tok"), attemptReads, "and so does the other one")
        assertTrue(logs.any { it.contains("refresh ok") })
    }

    @Test
    fun refreshAccountSurfacesUpdatesEloFromProfile() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { token, done ->
                assertEquals("tok", token)
                done(
                    Result.success(
                        testUser().copy(eloRating = 1482.0),
                    ),
                )
            },
        )

        controller.refreshAccountSurfaces()

        assertEquals(1482.0, controller.eloRating)
    }

    @Test
    fun refreshAccountSurfacesAlsoReadsNotificationPrefs() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val reads = mutableListOf<String>()
        val client = NotificationPrefsClient(
            slot = notificationPrefsSlot(),
            reader = object : NotificationPrefsReader {
                override fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit) {
                    reads += token
                    onDone(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 6)))
                }
            },
            writer = object : NotificationPrefsWriter {
                override fun put(
                    token: String,
                    prefs: NotificationPrefs,
                    onDone: (Result<NotificationPrefs>) -> Unit,
                ) {
                    onDone(Result.success(prefs))
                }
            },
        )
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            notificationPrefs = client,
        )

        controller.refreshAccountSurfaces()

        assertEquals(listOf("tok"), reads)
        assertTrue(client.prefs.emailOptIn, "a restored Settings card reads the server's value")
    }

    @Test
    fun refreshAccountSurfacesReportsTheDeviceZoneWithTheServerRowIntact() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val sent = mutableListOf<NotificationPrefs>()
        val client = NotificationPrefsClient(
            slot = notificationPrefsSlot(),
            reader = object : NotificationPrefsReader {
                override fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit) {
                    onDone(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 6, language = "en-US")))
                }
            },
            writer = object : NotificationPrefsWriter {
                override fun put(
                    token: String,
                    prefs: NotificationPrefs,
                    onDone: (Result<NotificationPrefs>) -> Unit,
                ) {
                    sent += prefs
                    onDone(Result.success(prefs))
                }
            },
        )
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            notificationPrefs = client,
        )
        PlatformTimeZone.provider = { "Europe/Berlin" }
        try {
            controller.refreshAccountSurfaces()
        } finally {
            PlatformTimeZone.provider = { null }
        }

        assertEquals(
            NotificationPrefs(true, 6, "en-US", "Europe/Berlin"),
            sent.single(),
            "the zone rides the server's opt-in and cadence",
        )
    }

    @Test
    fun aRefreshedSessionReloadsShadowRankedAttempts() {
        val auth = testAuth()
        auth.signIn(AuthLogin("old-tok", testUser(), "ref-1"))
        val reads = mutableListOf<String>()
        val attempts = ShadowRankedAttemptsClient(
            api = object : ShadowRankedAttemptsWire {
                override fun list(token: String, onDone: (Result<ChallengeList>) -> Unit) {
                    reads += token
                    onDone(Result.success(ChallengeList(emptyList())))
                }
            },
        )
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            shadowRankedAttempts = attempts,
            refreshRequester = { _, done ->
                done(Result.success(AuthLogin("new-tok", testUser(), "ref-2")))
            },
        )

        controller.onUnauthorized()

        assertEquals(listOf("new-tok"), reads, "the reload follows the rotated access token")
    }

    @Test
    fun tickRefreshesWhenAccessJwtNearExpiry() {
        val nowMs = 1_000_000L
        val exp = (nowMs + AccountController.REFRESH_SKEW_MS) / 1000L
        val auth = testAuth()
        auth.signIn(AuthLogin(unsignedAccessJwt(exp), testUser(), "ref-1"))
        val presence = createPresenceClient()
        var sendCount = 0
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            refreshRequester = { token, done ->
                sendCount += 1
                assertEquals("ref-1", token)
                done(Result.success(AuthLogin(unsignedAccessJwt(exp + 900), testUser(), "ref-2")))
            },
            clock = { nowMs },
        )
        controller.restoreSession()
        presence.startLooking()
        assertTrue(presence.waitingEnabled)

        controller.tick(nowMs)

        assertEquals(1, sendCount)
        assertEquals("ref-2", auth.refreshToken)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertTrue(presence.signedIn)
        assertTrue(presence.waitingEnabled, "proactive refresh must not reset search")
    }

    @Test
    fun tickDoesNotRefreshWhenAccessJwtHasTimeLeft() {
        val nowMs = 1_000_000L
        val exp = (nowMs + AccountController.REFRESH_SKEW_MS + 60_000L) / 1000L
        val auth = testAuth()
        auth.signIn(AuthLogin(unsignedAccessJwt(exp), testUser(), "ref-1"))
        var sendCount = 0
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            refreshRequester = { _, done ->
                sendCount += 1
                done(Result.success(AuthLogin(unsignedAccessJwt(exp), testUser(), "ref-2")))
            },
            clock = { nowMs },
        )
        // The cold-start restore exchanges the stored refresh token once.
        controller.restoreSession()
        val refreshesAfterRestore = sendCount

        controller.tick(nowMs)

        assertEquals(
            refreshesAfterRestore,
            sendCount,
            "a tick must not refresh while the access JWT has time left",
        )
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
    }

    @Test
    fun tickIgnoresNonJwtAccessTokens() {
        val auth = testAuth()
        auth.signIn(AuthLogin("plain-token", testUser(), "ref-1"))
        var sendCount = 0
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            refreshRequester = { _, done ->
                sendCount += 1
                done(Result.success(AuthLogin("plain-token", testUser(), "ref-2")))
            },
            clock = { 1_000_000L },
        )
        controller.restoreSession()
        val refreshesAfterRestore = sendCount

        controller.tick()

        assertEquals(
            refreshesAfterRestore,
            sendCount,
            "an opaque token has no expiry to read, so nothing schedules a refresh",
        )
    }

    @Test
    fun googleSignInSuccessRunsChallengeIdTokenExchange() {
        val auth = testAuth()
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val google = FakeGoogleAuthProvider()
        var capturedClientId: String? = null
        var capturedHash: String? = null
        var capturedVerifier: String? = null
        var capturedIdToken: String? = null
        val logs = mutableListOf<String>()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { clientId, hash, done ->
                capturedClientId = clientId
                capturedHash = hash
                done(Result.success(GoogleChallenge("nonce-1", 600)))
            },
            googleExchangeRequester = { verifier, idToken, done ->
                capturedVerifier = verifier
                capturedIdToken = idToken
                done(Result.success(AuthLogin("tok-g", testUser(), "ref-g")))
            },
            logLifecycle = { logs.add(it) },
        )

        controller.beginGoogleSignIn()

        assertEquals("web-client.apps.googleusercontent.com", capturedClientId)
        assertEquals(GoogleVerifier.sha256Hex(capturedVerifier!!), capturedHash)
        assertEquals("nonce-1", google.lastNonce)
        assertEquals("id-token", capturedIdToken)
        assertTrue(controller.isSignedIn)
        assertEquals("tok-g", controller.token)
        assertEquals("ref-g", auth.refreshToken)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertEquals(GoogleSignInStep.Idle, controller.googleSignInStep)
        assertNull(controller.lastAuthError)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
        assertTrue(logs.any { it.contains("google sign-in start") })
    }

    @Test
    fun googleSignInTracksProgressThenIdlesOnSuccess() {
        val google = FakeGoogleAuthProvider().apply { autoIdToken = null }
        var completeChallenge: ((Result<GoogleChallenge>) -> Unit)? = null
        var completeExchange: ((Result<AuthLogin>) -> Unit)? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, done -> completeChallenge = done },
            googleExchangeRequester = { _, _, done -> completeExchange = done },
        )

        controller.beginGoogleSignIn()
        assertEquals(AuthLifecycle.SigningIn, controller.lifecycle)
        assertEquals(GoogleSignInStep.Challenging, controller.googleSignInStep)

        completeChallenge!!(Result.success(GoogleChallenge("n", 600)))
        assertEquals(GoogleSignInStep.AwaitingGoogle, controller.googleSignInStep)
        assertEquals("n", google.lastNonce)

        google.pendingIdToken!!(Result.success("id-token"))
        assertEquals(GoogleSignInStep.Exchanging, controller.googleSignInStep)

        completeExchange!!(Result.success(AuthLogin("tok-g", testUser(), "ref-g")))
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertEquals(GoogleSignInStep.Idle, controller.googleSignInStep)
    }

    @Test
    fun googleSignInUnavailableIsNotRetryableAndStaysSignedOut() {
        val google = FakeGoogleAuthProvider(isAvailable = false)
        var challengeCalled = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, _ -> challengeCalled = true },
        )

        controller.beginGoogleSignIn()

        assertFalse(challengeCalled)
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertFalse(controller.lastAuthError!!.retryable)
        assertEquals(GoogleSignInStep.Idle, controller.googleSignInStep)
    }

    @Test
    fun googleSignInRateLimitedIsRetryable() {
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.failure(AuthException("Too many pending sign-in attempts", rateLimited = true)))
            },
        )

        controller.beginGoogleSignIn()

        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertTrue(controller.lastAuthError!!.retryable)
        assertEquals("Too many pending sign-in attempts", controller.lastAuthError!!.message)
    }

    @Test
    fun googleSignInNetworkErrorIsRetryable() {
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(
                    Result.failure(
                        AuthException("Could not reach the server", cause = RuntimeException("offline")),
                    ),
                )
            },
        )

        controller.beginGoogleSignIn()

        assertTrue(controller.lastAuthError!!.retryable)
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
    }

    @Test
    fun googleSignInRejectedTokenIsNotRetryable() {
        val google = FakeGoogleAuthProvider()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(
                    Result.failure(
                        AuthException("Google sign-in could not be verified", unauthorized = true),
                    ),
                )
            },
        )

        controller.beginGoogleSignIn()

        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertFalse(controller.lastAuthError!!.retryable)
    }

    @Test
    fun lateGoogleExchangeAfterLogoutIsIgnored() {
        val google = FakeGoogleAuthProvider()
        var completeExchange: ((Result<AuthLogin>) -> Unit)? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done -> completeExchange = done },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.beginGoogleSignIn()
        assertEquals(GoogleSignInStep.Exchanging, controller.googleSignInStep)

        controller.logOut()
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertEquals(1, google.clearCount)

        completeExchange!!(Result.success(AuthLogin("tok-g", testUser(), "ref-g")))
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertFalse(controller.isSignedIn)
        assertNull(controller.token)
    }

    @Test
    fun logOutClearsGoogleCredentialStateWithoutWaiting() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val google = FakeGoogleAuthProvider()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.logOut()

        assertEquals(1, google.clearCount)
        assertTrue(google.cancelCount >= 1)
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
    }

    @Test
    fun expireSessionDoesNotClearGoogleCredentialState() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        val google = FakeGoogleAuthProvider()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
        )

        controller.expireSession()

        assertEquals(0, google.clearCount)
        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
    }

    @Test
    fun beginGoogleSignInIgnoredWhileSignedIn() {
        val auth = testAuth()
        auth.signIn(AuthLogin("tok", testUser()))
        var challengeCalled = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, _ -> challengeCalled = true },
        )
        controller.restoreSession()

        controller.beginGoogleSignIn()

        assertFalse(challengeCalled)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
    }

    @Test
    fun googleSignInCancelledLeavesSignedOutWithoutError() {
        val google = FakeGoogleAuthProvider().apply { autoIdToken = null }
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", testUser(), "ref-g")))
            },
        )

        controller.beginGoogleSignIn()
        assertEquals(AuthLifecycle.SigningIn, controller.lifecycle)
        google.pendingIdToken!!(Result.failure(GoogleSignInCancelled()))

        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertEquals(GoogleSignInStep.Idle, controller.googleSignInStep)
        assertNull(controller.lastAuthError)
        assertFalse(controller.isSignedIn)
    }

    @Test
    fun googleSignInPopupBlockedIsRetryable() {
        val google = FakeGoogleAuthProvider().apply { autoIdToken = null }
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = FakeAccountView(),
            profileRequester = { _, _ -> },
            googleAuth = google,
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
        )

        controller.beginGoogleSignIn()
        google.pendingIdToken!!(Result.failure(GoogleSignInException.fromGisCode("popup_blocked")))

        assertEquals(AuthLifecycle.SignedOut, controller.lifecycle)
        assertTrue(controller.lastAuthError!!.retryable)
        assertTrue(controller.lastAuthError!!.message.contains("pop-up"))
    }

    @Test
    fun googleFirstLoginShowsAliasPromptAndDoesNotStartPresence() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
        )

        controller.beginGoogleSignIn()

        assertTrue(controller.isSignedIn)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        assertTrue(controller.awaitingFirstLoginAlias)
        assertEquals("user-k7m4p9q2", view.firstLoginShownName)
        assertTrue(view.isFirstLoginAliasVisible)
        assertFalse(presence.signedIn)
        assertFalse(view.presenceShown)
    }

    @Test
    fun googleUserWithCustomizedAliasSkipsPrompt() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(
                    Result.success(
                        AuthLogin(
                            "tok-g",
                            googleUser(aliasCustomizedAt = "2026-09-10T12:00:00Z"),
                            "ref-g",
                        ),
                    ),
                )
            },
        )

        controller.beginGoogleSignIn()

        assertFalse(controller.awaitingFirstLoginAlias)
        assertNull(view.firstLoginShownName)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
    }

    @Test
    fun confirmFirstLoginAliasEntersLobbyWithoutPatchingAndStoresDismissal() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        var patchCalled = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(DISMISSAL_SLOT),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            aliasUpdateRequester = { _, _, done ->
                patchCalled = true
                done(Result.success(googleUser()))
            },
        )

        controller.beginGoogleSignIn()
        assertTrue(controller.awaitingFirstLoginAlias)
        controller.confirmFirstLoginAlias()

        assertFalse(patchCalled)
        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(view.firstLoginHidden)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
        assertNull(controller.user?.aliasCustomizedAt)
        assertTrue(googleUser().id in firstLoginPromptsSlot(DISMISSAL_SLOT).requireValue())
        assertFalse(controller.awaitingStartingLevel)
        assertFalse(view.isStartingLevelVisible)
    }

    @Test
    fun googleFirstLoginThenStartingLevelAfterAliasConfirm() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(startingLevelSetAt = null), "ref-g")))
            },
        )

        controller.beginGoogleSignIn()
        assertTrue(controller.awaitingFirstLoginAlias)
        assertFalse(controller.awaitingStartingLevel)
        assertFalse(presence.signedIn)

        controller.confirmFirstLoginAlias()

        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(controller.awaitingStartingLevel)
        assertTrue(view.isStartingLevelVisible)
        assertFalse(presence.signedIn)
        assertFalse(view.presenceShown)
    }

    @Test
    fun googleUserWithAliasSetStillNeedsStartingLevel() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(
                    Result.success(
                        AuthLogin(
                            "tok-g",
                            googleUser(
                                aliasCustomizedAt = "2026-09-10T12:00:00Z",
                                startingLevelSetAt = null,
                            ),
                            "ref-g",
                        ),
                    ),
                )
            },
        )

        controller.beginGoogleSignIn()

        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(controller.awaitingStartingLevel)
        assertTrue(view.isStartingLevelVisible)
        assertFalse(presence.signedIn)
        assertFalse(view.presenceShown)
    }

    @Test
    fun cancelFirstLoginAliasConfirmsRandomAliasAndEntersLobby() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        var patchCalled = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(DISMISSAL_SLOT),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            aliasUpdateRequester = { _, _, done ->
                patchCalled = true
                done(Result.success(googleUser()))
            },
        )

        controller.beginGoogleSignIn()
        assertTrue(controller.awaitingFirstLoginAlias)
        // User clicks "Cancel" (keep the random name) or presses Escape
        controller.cancelFirstLoginAlias()

        assertFalse(patchCalled)
        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(view.firstLoginHidden)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
        assertNull(controller.user?.aliasCustomizedAt)
        assertTrue(googleUser().id in firstLoginPromptsSlot(DISMISSAL_SLOT).requireValue())
    }

    @Test
    fun submitFirstLoginAliasWithUnchangedNameConfirmsRandomAliasAndEntersLobby() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        var patchCalled = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(DISMISSAL_SLOT),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            aliasUpdateRequester = { _, _, done ->
                patchCalled = true
                done(Result.success(googleUser()))
            },
        )

        controller.beginGoogleSignIn()
        assertTrue(controller.awaitingFirstLoginAlias)
        view.firstLoginAliasText = googleUser().displayName
        // User clicks "Save" without changing the generated name
        controller.submitFirstLoginAlias()

        assertFalse(patchCalled)
        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(view.firstLoginHidden)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
        assertNull(controller.user?.aliasCustomizedAt)
        assertTrue(googleUser().id in firstLoginPromptsSlot(DISMISSAL_SLOT).requireValue())
    }

    @Test
    fun restoreSessionGooglePromptDismissedSkipsPrompt() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "restored_tok", user = googleUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        firstLoginPromptsSlot(DISMISSAL_SLOT).set(setOf(googleUser().id))
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(DISMISSAL_SLOT),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.restoreSession()

        assertFalse(controller.awaitingFirstLoginAlias)
        assertNull(view.firstLoginShownName)
        assertTrue(presence.signedIn)
        // No prompt and no screen move: the boot's floor is what stays up.
        assertFalse(view.presenceShown)
    }

    @Test
    fun submitFirstLoginAliasPatchesCustomName() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        var patchedName: String? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            aliasUpdateRequester = { _, name, done ->
                patchedName = name
                done(
                    Result.success(
                        googleUser(
                            displayName = name,
                            aliasCustomizedAt = "2026-09-10T12:00:01Z",
                        ),
                    ),
                )
            },
        )

        controller.beginGoogleSignIn()
        view.firstLoginAliasText = "Alice M."
        controller.submitFirstLoginAlias()

        assertEquals("Alice M.", patchedName)
        assertEquals("Alice M.", controller.user?.displayName)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
    }

    @Test
    fun submitFirstLoginAliasSurfacesServerErrorAndStaysOnPrompt() {
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            aliasUpdateRequester = { _, _, done ->
                done(Result.failure(AuthException("That name is reserved")))
            },
        )

        controller.beginGoogleSignIn()
        view.firstLoginAliasText = "admin"
        controller.submitFirstLoginAlias()

        assertEquals("That name is reserved", view.firstLoginError)
        assertTrue(controller.awaitingFirstLoginAlias)
        assertFalse(presence.signedIn)
        assertFalse(view.presenceShown)
    }

    @Test
    fun restoreSessionGoogleUncustomizedShowsPrompt() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "restored_tok", user = googleUser()))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.restoreSession()

        assertTrue(controller.awaitingFirstLoginAlias)
        assertEquals("user-k7m4p9q2", view.firstLoginShownName)
        assertFalse(presence.signedIn)
        assertEquals(AuthLifecycle.SignedIn, controller.lifecycle)
        // The alias card owns input above the menu, so the menu must be closed.
        assertTrue(view.menuHidden)
    }

    @Test
    fun logOutHidesFirstLoginPrompt() {
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.beginGoogleSignIn()
        assertTrue(view.isFirstLoginAliasVisible)
        controller.logOut()

        assertFalse(controller.awaitingFirstLoginAlias)
        assertTrue(view.firstLoginHidden)
        assertFalse(controller.isSignedIn)
    }

    @Test
    fun beginAliasEditOpensEditorWhenSignedIn() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.beginAliasEdit()

        assertTrue(view.isAliasEditVisible)
    }

    @Test
    fun submitAliasEditPatchesAndCloses() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        var patched: String? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            aliasUpdateRequester = { token, name, done ->
                assertEquals("tok", token)
                patched = name
                done(
                    Result.success(
                        testUser().copy(displayName = name, aliasCustomizedAt = "2026-09-10T12:00:01Z"),
                    ),
                )
            },
        )

        controller.beginAliasEdit()
        view.aliasEditText = "Alice M."
        controller.submitAliasEdit()

        assertEquals("Alice M.", patched)
        assertEquals("Alice M.", controller.user?.displayName)
        assertFalse(view.isAliasEditVisible)
        assertTrue(controller.isSignedIn)
    }

    @Test
    fun submitUnchangedAliasClosesWithoutRequest() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        var called = false
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            aliasUpdateRequester = { _, _, _ -> called = true },
        )

        controller.beginAliasEdit()
        view.aliasEditText = "Alice"
        controller.submitAliasEdit()

        assertFalse(called)
        assertFalse(view.isAliasEditVisible)
    }

    @Test
    fun submitEmptyAliasShowsRequiredError() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.beginAliasEdit()
        view.aliasEditText = "   "
        controller.submitAliasEdit()

        assertEquals(L10n.get("alias.error_required"), view.aliasErrorSet)
        assertTrue(view.isAliasEditVisible)
    }

    @Test
    fun submitAliasEditSurfacesServerValidationError() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            aliasUpdateRequester = { _, _, done ->
                done(Result.failure(AuthException("That name is reserved")))
            },
        )

        controller.beginAliasEdit()
        view.aliasEditText = "admin"
        controller.submitAliasEdit()

        assertEquals("That name is reserved", view.aliasErrorSet)
        assertTrue(view.isAliasEditVisible)
        assertTrue(controller.isSignedIn)
    }

    @Test
    fun submitAliasEditSurfacesRateLimitError() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            aliasUpdateRequester = { _, _, done ->
                done(
                    Result.failure(
                        AuthException(
                            "Please wait before changing your name again",
                            rateLimited = true,
                        ),
                    ),
                )
            },
        )

        controller.beginAliasEdit()
        view.aliasEditText = "Alicia"
        controller.submitAliasEdit()

        assertEquals("Please wait before changing your name again", view.aliasErrorSet)
        assertTrue(view.isAliasEditVisible)
    }

    @Test
    fun cancelAliasEditClosesField() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.beginAliasEdit()
        controller.cancelAliasEdit()

        assertFalse(view.isAliasEditVisible)
    }

    @Test
    fun logOutCancelsAliasEdit() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser()))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.beginAliasEdit()
        controller.logOut()

        assertFalse(view.isAliasEditVisible)
        assertFalse(controller.isSignedIn)
    }

    @Test
    fun beginAliasEditIgnoredDuringFirstLoginPrompt() {
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = testAuth(),
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            googleAuth = FakeGoogleAuthProvider(),
            googleChallengeRequester = { _, _, done ->
                done(Result.success(GoogleChallenge("n", 600)))
            },
            googleExchangeRequester = { _, _, done ->
                done(Result.success(AuthLogin("tok-g", googleUser(), "ref-g")))
            },
        )

        controller.beginGoogleSignIn()
        controller.beginAliasEdit()

        assertFalse(view.isAliasEditVisible)
        assertTrue(controller.awaitingFirstLoginAlias)
    }

    @Test
    fun setStartingLevelSuccessUpdatesEloAndEntersLobby() {
        val auth = testAuth()
        val userWithoutLevel = testUser(startingLevelSetAt = null)
        auth.signIn(AuthLogin(token = "tok", user = userWithoutLevel))
        val presence = createPresenceClient()
        val view = FakeAccountView()
        var requestedLevel: String? = null
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = presence,
            view = view,
            profileRequester = { _, _ -> },
            startingLevelRequester = { _, level, done ->
                requestedLevel = level
                val updated = userWithoutLevel.copy(
                    startingLevel = level,
                    startingLevelSetAt = "2026-09-11T12:00:00Z",
                    eloRating = 800.0,
                )
                done(Result.success(updated))
            },
        )

        controller.showStartingLevelPrompt()
        assertTrue(controller.awaitingStartingLevel)
        assertTrue(view.isStartingLevelVisible)

        controller.setStartingLevel("novice")

        assertEquals("novice", requestedLevel)
        assertFalse(controller.awaitingStartingLevel)
        assertFalse(view.isStartingLevelVisible)
        assertEquals(800.0, controller.eloRating)
        assertTrue(presence.signedIn)
        assertTrue(view.presenceShown)
    }

    @Test
    fun setStartingLevelFailureSetsErrorOnOverlay() {
        val auth = testAuth()
        val userWithoutLevel = testUser(startingLevelSetAt = null)
        auth.signIn(AuthLogin(token = "tok", user = userWithoutLevel))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            startingLevelRequester = { _, _, done ->
                done(Result.failure(RuntimeException("Network error")))
            },
        )

        controller.showStartingLevelPrompt()
        controller.setStartingLevel("strong")

        assertTrue(controller.awaitingStartingLevel)
        assertTrue(view.isStartingLevelVisible)
        assertFalse(view.isStartingLevelBusy)
        assertEquals("Network error", view.startingLevelErrorSet)
    }

    @Test
    fun showStartingLevelPromptIgnoredWhenAlreadySet() {
        val auth = testAuth()
        auth.signIn(AuthLogin(token = "tok", user = testUser(startingLevelSetAt = "2026-09-11T12:00:00Z")))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
        )

        controller.showStartingLevelPrompt()

        assertFalse(controller.awaitingStartingLevel)
        assertFalse(view.isStartingLevelVisible)
    }

    @Test
    fun logOutHidesStartingLevelOverlayAndClearsAwaiting() {
        val auth = testAuth()
        val userWithoutLevel = testUser(startingLevelSetAt = null)
        auth.signIn(AuthLogin(token = "tok", user = userWithoutLevel))
        val view = FakeAccountView()
        val controller = AccountController(
            firstLoginPrompts = firstLoginPromptsSlot(),
            authSession = auth,
            presenceClient = createPresenceClient(),
            view = view,
            profileRequester = { _, _ -> },
            logoutRequester = { _, done -> done(Result.success(Unit)) },
        )

        controller.showStartingLevelPrompt()
        assertTrue(view.isStartingLevelVisible)

        controller.logOut()

        assertFalse(view.isStartingLevelVisible)
        assertFalse(controller.awaitingStartingLevel)
        assertFalse(controller.isSignedIn)
    }
}


/** In-memory `auth.session` slot for one test. */
private fun authSlot(): StateSlot<AuthPersistedSession> =
    StateSlot(DefaultStateStore(InMemoryStateBackend()), AuthPersistedSessionKey)

/** In-memory `notification.prefs` slot for one test. */
private fun notificationPrefsSlot(): StateSlot<NotificationPrefs> =
    StateSlot(DefaultStateStore(InMemoryStateBackend()), NotificationPrefsKey)

/** In-memory `online.find` slot for one test. */
private fun findSlot(): StateSlot<FindGamePreferences> =
    StateSlot(DefaultStateStore(InMemoryStateBackend()), FindGamePreferencesKey)

/** A session over [authSlot]: nothing persists beyond the test. */
private fun testAuth(): AuthSession = AuthSession(authSlot())

/** One wire attempt row, for the log-out clear test. */
private fun attemptRow(publicId: String = "7K3H-M9PQ"): ChallengeEntry = ChallengeEntry(
    publicId = publicId,
    rounds = "one",
    allowChicken = false,
    createdAt = "2026-09-20T10:00:00Z",
    status = ChallengeStatus.Finished,
    totalScore = 100,
    startedAt = "2026-09-20T10:00:00Z",
    finishedAt = "2026-09-20T10:05:00Z",
    players = 2,
    ratingDelta = 4.0,
    scores = emptyList(),
)

/** The slot name a first-login dismissal test reads back. */
private const val DISMISSAL_SLOT = "dismissal"

private const val DEFAULT_SLOT = "default"

/**
 * In-memory `auth.firstLogin` slots by name.
 *
 * Most tests construct an `AccountController` without caring about first-login
 * state; the few that assert the dismissal read back the same named slot they
 * handed in.
 */
private val firstLoginSlots = mutableMapOf<String, StateSlot<Set<String>>>()

private fun firstLoginPromptsSlot(name: String = DEFAULT_SLOT): StateSlot<Set<String>> =
    firstLoginSlots.getOrPut(name) {
        StateSlot(DefaultStateStore(InMemoryStateBackend()), DismissedFirstLoginPromptsKey)
    }
