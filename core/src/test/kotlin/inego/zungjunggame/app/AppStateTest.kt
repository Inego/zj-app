package inego.zungjunggame.app

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.persist.*
import inego.zungjunggame.state.*
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialProgressKey
import inego.zungjunggame.ui.stack.*
import kotlin.random.Random
import kotlin.test.*

/**
 * The cold start's single read: [AppStateSource.read] composes every artifact the
 * boot decides from, each from its own key, and falls back to the key's default
 * where nothing is stored.
 */
class AppStateTest {

    private val prefs = InMemoryStateBackend()
    private val files = InMemoryStateBackend()

    private fun source(
        prefs: StateBackend = this.prefs,
        files: StateBackend = this.files,
    ): AppStateSource {
        val store: StateStore = DefaultStateStore(prefs)
        val fileStore: StateStore = DefaultStateStore(files)
        return AppStateSource(
            stack = StateSlot(store, StackStateKey),
            session = StateSlot(fileStore, SessionSnapshotKey),
            tutorialSession = StateSlot(fileStore, TutorialSessionSnapshotKey),
            account = StateSlot(store, AuthPersistedSessionKey),
            onlineTableId = StateSlot(store, OnlineTableIdKey),
            game = StateSlot(store, GameConfigKey),
            ui = StateSlot(store, UiPreferencesKey),
            locale = StateSlot(store, AppLocaleKey),
            find = StateSlot(store, FindGamePreferencesKey),
            tutorialProgress = StateSlot(store, TutorialProgressKey),
            firstLoginPrompts = StateSlot(store, DismissedFirstLoginPromptsKey),
            remembered = StateSlot(store, RememberedRouteUiKey),
            notificationPrefs = StateSlot(store, NotificationPrefsKey),
        )
    }

    @Test
    fun anEmptyStoreReadsTheDeclaredDefaults() {
        val boot = source().read()

        assertEquals(StackState.EMPTY, boot.stack)
        assertNull(boot.session, "no free-play save means no slice")
        assertNull(boot.tutorialSession, "no mid-lesson save means no slice")
        assertNull(boot.account, "signed out means no account slice")
        assertEquals(GameConfig.DEFAULT, boot.game)
        assertEquals(UiPreferences.DEFAULT, boot.ui)
        assertEquals(AppLocale.DEFAULT, boot.locale)
    }

    @Test
    fun oneReadComposesEverySliceFromItsOwnKey() {
        val stack = StackState(
            listOf(
                RouteEntryState(
                    "layer:local_board",
                    RouteKind.Layer,
                    "local_board",
                    policy = LayerPolicy(Presentation.Opaque, persists = true),
                ),
            ),
        )
        val session = SessionSnapshot(
            scoreRows = emptyList(),
            game = GameState.generateRandom(Random(5), eastSeat = 1),
            nextEastSeat = 2,
            currentHandRecorded = false,
            dealSeed = 5L,
        )
        val lesson = tutorialSnapshot()
        val account = AuthPersistedSession(
            refreshToken = "ref-1",
            user = AuthUser(id = "u1", displayName = "Alice"),
        )
        val game = GameConfig(rounds = 8, allowChickenHands = true)

        val first = source()
        first.stack.set(stack)
        first.session.set(session)
        first.tutorialSession.set(lesson)
        first.account.set(account)
        first.game.set(game)
        first.ui.set(UiPreferences(showTileLabels = true))
        first.locale.set(AppLocale.Russian)

        // A restart: every key is re-read through one source.
        val boot = source().read()

        assertEquals(stack, boot.stack)
        assertEquals(session.dealSeed, assertNotNull(boot.session).dealSeed)
        assertEquals(lesson.lessonId, assertNotNull(boot.tutorialSession).lessonId)
        assertEquals("ref-1", assertNotNull(boot.account).refreshToken)
        assertEquals(game, boot.game)
        assertTrue(boot.ui.showTileLabels)
        assertEquals(AppLocale.Russian, boot.locale)
    }

    @Test
    fun theBootReadsEachOfItsKeysOnceAndNothingElse() {
        val backend = CountingBackend(InMemoryStateBackend())
        source(prefs = backend).read()

        // Every artifact the boot decides from, read exactly once: one pass.
        assertEquals(BOOT_KEYS, backend.reads.toSet())
        assertEquals(backend.reads.size, backend.reads.toSet().size, "no key is read twice")

        // Keys whose owner reads them lazily on first use are not part of it.
        assertFalse(FindGamePreferencesKey.fullName in backend.reads)
        assertFalse(TutorialProgressKey.fullName in backend.reads)
        assertFalse(DismissedFirstLoginPromptsKey.fullName in backend.reads)
        assertFalse(OnlineTableIdKey.fullName in backend.reads)
    }

    private fun tutorialSnapshot(): TutorialSessionSnapshot = TutorialSessionSnapshot(
        lessonId = "lesson-1",
        stepIndex = 2,
        status = TutorialDirector.Status.Running,
        awaitingCoach = false,
        awaitingQuiz = false,
        quizFeedbackFail = false,
        lastCoachKey = null,
        lastFocus = null,
        lastDiagram = TutorialDiagram.None,
        failureMessage = null,
        pendingLinkPageId = null,
        game = GameState.generateRandom(Random(6), eastSeat = 0),
    )

    /** Counts backend reads so the boot's read set is inspectable. */
    private class CountingBackend(
        private val delegate: InMemoryStateBackend,
    ) : StateBackend {
        val reads = mutableListOf<String>()

        override fun isAvailable(): Boolean = delegate.isAvailable()
        override fun get(name: String): ByteArray? {
            reads += name
            return delegate.get(name)
        }
        override fun put(name: String, value: ByteArray) = delegate.put(name, value)
        override fun remove(name: String) = delegate.remove(name)
        override fun names(): Set<String> = delegate.names()
        override fun flush() = delegate.flush()
    }

    private companion object {
        /** The keys [AppStateSource.read] composes (the file-backed pair is read too). */
        val BOOT_KEYS = setOf(
            StackStateKey.fullName,
            AuthPersistedSessionKey.fullName,
            GameConfigKey.fullName,
            UiPreferencesKey.fullName,
            AppLocaleKey.fullName,
        )
    }
}
