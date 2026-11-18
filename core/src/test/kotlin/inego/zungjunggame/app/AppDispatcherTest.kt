package inego.zungjunggame.app

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.runtime.*
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.ui.*
import inego.zungjunggame.ui.stack.*
import kotlin.test.*

/** GL-free tests for the [Intent] → effect-port reduction. */
class AppDispatcherTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    /** A real [Stack] (bottom→top) over the given routes; the first must be a Screen. */
    private fun stackOf(vararg routes: Route): Stack = Stack(
        routes.map { route ->
            RouteInstance(route, StubComponent(ComponentId(route.key.value)), RouteSpecs.specFor(route))
        },
    )

    /** One recorder for every port; each method appends/counts. */
    private class RecordingPorts : NavigationEffects, ChromeEffects, PrefsEffects, SessionEffects,
        TutorialEffects, OnlineEffects, AccountEffects {

        val references = mutableListOf<String?>()
        val sections = mutableListOf<String>()
        val externals = mutableListOf<String>()
        var referenceBacks = 0
        var referenceDismisses = 0

        var menuShows = 0
        var menuHides = 0
        var fullscreens = 0
        var exits = 0

        var tileLabelToggles = 0
        var soundToggles = 0
        val soundVolumes = mutableListOf<Int>()
        val routeUiWrites = mutableListOf<Pair<String, RouteUiValue>>()
        val routeUiRemember = mutableListOf<Boolean>()
        val locales = mutableListOf<AppLocale>()
        var languageOpens = 0
        var languageDismisses = 0
        var settingsOpens = 0
        var settingsDismisses = 0

        /** Live kind the harness reports to the dispatcher (as the registry would). */
        var kind: SessionKind = SessionKind.Menu
        val discards = mutableListOf<Triple<Int, Int, Boolean>>()
        val choices = mutableListOf<Choice>()
        var hints = 0
        var assists = 0
        var nextHands = 0
        var newGames = 0
        var setupStarts = 0
        var setupCancels = 0
        var setupEnters = 0
        var scoreOpens = 0
        var scoreDismisses = 0
        val breakdownParks = mutableListOf<Pair<Float, Float>>()
        var breakdownFolds = 0
        val breakdownScrolls = mutableListOf<Float>()
        val hintParks = mutableListOf<Pair<Float, Float>>()
        val claimParks = mutableListOf<Pair<Float, Float>>()
        var continues = 0
        var entersFromRoot = 0
        var exitToMenus = 0
        val confirms = mutableListOf<ConfirmPrompt>()
        var confirmYes = 0
        var confirmNo = 0

        var tutorialPrimary = 0
        var tutorialBack = 0
        var selectResult = false
        val selectedLessons = mutableListOf<String>()
        var quizNexts = 0
        var quizTryAgains = 0
        val quizSubmitAnswers = mutableListOf<QuizAnswer>()
        val quizStateChanges = mutableListOf<QuizInteractionState>()
        val hrefs = mutableListOf<String>()
        var tutorialOpens = 0
        var slideLeaveCompletes = 0
        val committedWallDeals = mutableListOf<WallBuildDeal>()
        val committedWallHandoffs = mutableListOf<WallBuildHandoffSnapshot>()

        var primaries = 0
        var rejoins = 0
        var abandonToLobby = 0
        var playAgains = 0
        val minOtherHumans = mutableListOf<Int>()
        var cycleRoundCount = 0
        var cycleChickenCount = 0
        var toggleWaitingCount = 0

        val hubOpens = mutableListOf<OnlineTab?>()
        val hubTabs = mutableListOf<OnlineTab>()
        var hubLeaves = 0
        var shadowStarts = 0
        var shadowCancels = 0
        var shadowRetries = 0
        var shadowRefreshes = 0
        var viewAttemptsCount = 0
        var loadMoreAttemptsCount = 0
        var attemptsDismissCount = 0
        val openHandsPublicIds = mutableListOf<String>()
        var handsDismissCount = 0
        val handsSteps = mutableListOf<Int>()
        var handsDropdownToggles = 0
        val handsSelects = mutableListOf<Int>()
        var editDisplayNameCount = 0
        var saveDisplayNameCount = 0
        var cancelDisplayNameCount = 0
        var viewHistoryCount = 0
        var loadMoreCount = 0
        var historyDismissCount = 0
        var openLeaderboardCount = 0
        var leaderboardDismissCount = 0
        val leaderboardWindows = mutableListOf<Int>()
        var viewNoticesCount = 0
        var noticesDismissCount = 0
        var noticePopupDismissCount = 0
        var toggleEmailOptInCount = 0
        var lastDigestIntervalHours: Int? = null
        var connectCancelCount = 0
        var connectRetryCount = 0
        var returnHomeCount = 0
        var awayResumeCount = 0

        var logIns = 0
        var firstSubmits = 0
        var firstBacks = 0
        val startingLevels = mutableListOf<String>()

        override fun openReference(pageId: String?) { references += pageId }
        override fun openReferenceSection(sectionId: String) { sections += sectionId }
        override fun openReferenceExternal(url: String) { externals += url }
        override fun referenceHistoryBack() { referenceBacks++ }
        override fun dismissReference() { referenceDismisses++ }

        override fun showMenu() { menuShows++ }
        override fun hideMenu() { menuHides++ }
        override fun toggleFullscreen() { fullscreens++ }
        override fun exitApp() { exits++ }

        override fun toggleTileLabels() { tileLabelToggles++ }
        override fun toggleSounds() { soundToggles++ }
        override fun setSoundVolume(value: Int) { soundVolumes += value }
        override fun writeRouteUi(routeKey: String, value: RouteUiValue, remember: Boolean) {
            routeUiWrites += routeKey to value
            routeUiRemember += remember
        }
        override fun pickLocale(locale: AppLocale) { locales += locale }
        override fun openLanguagePicker() { languageOpens++ }
        override fun dismissLanguagePicker() { languageDismisses++ }
        override fun openSettings() { settingsOpens++ }
        override fun dismissSettings() { settingsDismisses++ }

        var discardDrawnTiles: Int = 0

        override fun discard(tileType: Int, visualId: Int, isDrawn: Boolean) {
            discards += Triple(tileType, visualId, isDrawn)
        }
        override fun discardDrawnTile() { discardDrawnTiles++ }
        override fun applyChoice(choice: Choice) { choices += choice }
        override fun hint() { hints++ }
        override fun aiAssist() { assists++ }
        override fun requestNextHand() { nextHands++ }
        override fun requestNewGame() { newGames++ }
        override fun startFromSetup() { setupStarts++ }
        override fun cancelSetup() { setupCancels++ }
        override fun enterSetup() { setupEnters++ }
        override fun openScoreTable() { scoreOpens++ }
        override fun dismissScoreTable() { scoreDismisses++ }
        override fun parkScoreBreakdown(x: Float, y: Float) { breakdownParks += x to y }
        override fun toggleScoreBreakdownFold() { breakdownFolds++ }
        override fun setScoreBreakdownScroll(offset: Float) { breakdownScrolls += offset }
        override fun parkHintButton(x: Float, y: Float) { hintParks += x to y }
        override fun parkClaimPanel(x: Float, y: Float) { claimParks += x to y }
        override fun continueParked() { continues++ }
        override fun enterFromRoot() { entersFromRoot++ }
        override fun exitToMainMenu() { exitToMenus++ }
        override fun confirm(prompt: ConfirmPrompt) {
            confirms += prompt
        }
        override fun confirmYes() { confirmYes++ }
        override fun confirmNo() { confirmNo++ }

        override fun applyPrimary() { tutorialPrimary++ }
        override fun applyBack() { tutorialBack++ }
        override fun selectLesson(lessonId: String): Boolean {
            selectedLessons += lessonId
            return selectResult
        }
        override fun quizSubmit(answer: QuizAnswer) { quizSubmitAnswers += answer }
        override fun quizNext() { quizNexts++ }
        override fun quizTryAgain() { quizTryAgains++ }
        override fun quizStateChanged(attempt: QuizInteractionState) {
            quizStateChanges += attempt
        }
        override fun openHref(href: String) { hrefs += href }
        override fun openFromRoot() { tutorialOpens++ }
        override fun slideLeaveComplete() { slideLeaveCompletes++ }
        override fun wallDealCommitted(deal: WallBuildDeal) { committedWallDeals += deal }
        override fun wallHandoffCommitted(handoff: WallBuildHandoffSnapshot) {
            committedWallHandoffs += handoff
        }

        override fun primary() { primaries++ }
        override fun rejoin() { rejoins++ }
        override fun abandonToLobby() { abandonToLobby++ }
        override fun playAgain() { playAgains++ }
        override fun setMinOtherHumans(value: Int) { minOtherHumans += value }
        override fun cycleRounds() { cycleRoundCount++ }
        override fun cycleChicken() { cycleChickenCount++ }
        override fun toggleWaiting() { toggleWaitingCount++ }

        override fun hubOpen(tab: OnlineTab?) { hubOpens += tab }
        override fun hubSelectTab(tab: OnlineTab) { hubTabs += tab }
        override fun hubLeave() { hubLeaves++ }
        override fun shadowRankedStart() { shadowStarts++ }
        override fun shadowRankedCancelStart() { shadowCancels++ }
        override fun shadowRankedRetryStart() { shadowRetries++ }
        // The draft edits are supplied for the interface the dispatcher maps;
        // nothing in this file asserts them.
        override fun setShadowRankedRounds(rounds: Int) {}
        override fun toggleShadowRankedChicken() {}
        override fun shadowRankedRefreshAttempts() { shadowRefreshes++ }
        override fun viewShadowRankedAttempts() { viewAttemptsCount++ }
        override fun loadMoreShadowRankedAttempts() { loadMoreAttemptsCount++ }
        override fun shadowRankedAttemptsDismiss() { attemptsDismissCount++ }
        override fun openShadowRankedHands(publicId: String) { openHandsPublicIds += publicId }
        override fun shadowRankedHandsDismiss() { handsDismissCount++ }
        override fun shadowRankedHandsStep(delta: Int) { handsSteps += delta }
        override fun toggleShadowRankedHandsDropdown() { handsDropdownToggles++ }
        override fun selectShadowRankedHandsAttempt(index: Int) { handsSelects += index }
        override fun viewMatchHistory() { viewHistoryCount++ }
        override fun loadMoreMatchHistory() { loadMoreCount++ }
        override fun matchHistoryDismiss() { historyDismissCount++ }
        override fun openLeaderboard() { openLeaderboardCount++ }
        override fun loadLeaderboardWindow(offset: Int) { leaderboardWindows += offset }
        override fun leaderboardDismiss() { leaderboardDismissCount++ }
        // The rating-history entry points, supplied for the interface the dispatcher
        // maps; nothing in this file asserts them.
        override fun openRatingHistory(target: RatingHistoryTarget) {}
        override fun ratingHistoryDismiss() {}
        override fun viewNotices() { viewNoticesCount++ }
        override fun noticesDismiss() { noticesDismissCount++ }
        override fun dismissNoticePopup() { noticePopupDismissCount++ }
        override fun toggleEmailOptIn() { toggleEmailOptInCount++ }
        override fun setDigestIntervalHours(value: Int) { lastDigestIntervalHours = value }
        override fun connectCancel() { connectCancelCount++ }
        override fun connectRetry() { connectRetryCount++ }
        override fun returnHome() { returnHomeCount++ }
        override fun awayResume() { awayResumeCount++ }

        override fun logIn() { logIns++ }
        override fun logInSpecialKey() {}
        override fun specialKeySubmit() {}
        override fun specialKeyCancel() {}
        override fun firstLoginSubmit() { firstSubmits++ }
        override fun firstLoginBack() { firstBacks++ }
        override fun editDisplayName() { editDisplayNameCount++ }
        override fun saveDisplayName() { saveDisplayNameCount++ }
        override fun cancelDisplayName() { cancelDisplayNameCount++ }
        override fun startingLevelPick(level: String) { startingLevels += level }
        override fun openAccount() {}
        override fun dismissAccount() {}
    }

    private inner class Harness {
        val ports = RecordingPorts()
        val logs = mutableListOf<String>()

        /** Live bottom→top order; tests replace it to change what back sees. */
        var stack: Stack = stackOf(Route.Screen(RouteIds.LOCAL_BOARD))

        /** Per-route back intent; absent (null) means the route opts out. */
        var backIntents: Map<RouteKey, Intent?> = mapOf(
            Route.Screen(RouteIds.LOCAL_BOARD).key to Intent.ShowMenu,
        )

        val dispatcher = AppDispatcher(
            navigation = ports,
            chrome = ports,
            prefs = ports,
            session = ports,
            tutorial = ports,
            online = ports,
            account = ports,
            sessionKind = { ports.kind },
            stack = { stack },
            backOf = { route -> backIntents[route.key] },
            log = { logs += it },
        )

        fun apply(intent: Intent): Boolean = dispatcher.handle(intent)
    }

    @Test
    fun playSurfaceIntentsReduceToSessionEffects() {
        val h = Harness()
        val choice = Choice.pass()

        h.apply(Intent.Discard(tileType = 7, visualId = 3, isDrawn = true))
        h.apply(Intent.DiscardDrawnTile)
        h.apply(Intent.ApplyChoice(choice))
        h.apply(Intent.Hint)
        h.apply(Intent.AiAssist)
        h.apply(Intent.NextHand)
        h.apply(Intent.PlayAgain)

        assertEquals(listOf(Triple(7, 3, true)), h.ports.discards.toList())
        assertEquals(1, h.ports.discardDrawnTiles)
        assertEquals(listOf(choice), h.ports.choices.toList())
        assertEquals(1, h.ports.hints)
        assertEquals(1, h.ports.assists)
        assertEquals(1, h.ports.nextHands)
        assertEquals(1, h.ports.playAgains)
    }

    @Test
    fun newGameStartsSetupOnlyWhileSetupIsOpen() {
        val h = Harness()
        h.apply(Intent.NewGame)
        assertEquals(1, h.ports.newGames)

        h.stack = stackOf(
            Route.Screen(RouteIds.LOCAL_BOARD),
            Route.Modal(RouteIds.NEW_GAME_SETUP),
        )
        h.apply(Intent.NewGame)
        assertEquals(1, h.ports.setupStarts)
    }

    @Test
    fun newGameOnlineAbandonsToLobby() {
        val h = Harness()
        h.ports.kind = SessionKind.Online
        h.apply(Intent.NewGame)
        assertEquals(1, h.ports.abandonToLobby)
        assertEquals(0, h.ports.newGames)
    }

    @Test
    fun localAndUtilityActionsMap() {
        val h = Harness()
        h.apply(Intent.ParkScoreBreakdown(-120f, 40f))
        h.apply(Intent.ToggleScoreBreakdownFold)
        h.apply(Intent.SetScoreBreakdownScroll(55f))
        h.apply(Intent.ParkHintButton(-30f, 12f))
        h.apply(Intent.ParkClaimPanel(5f, -4f))
        h.apply(Intent.ToggleFullscreen)
        h.apply(Intent.ToggleTileLabels)
        h.apply(Intent.ToggleSounds)
        h.apply(Intent.SetSoundVolume(6))
        h.apply(Intent.ToggleEmailOptIn)
        h.apply(Intent.SetDigestIntervalHours(6))

        assertEquals(listOf(-120f to 40f), h.ports.breakdownParks.toList())
        assertEquals(1, h.ports.breakdownFolds)
        assertEquals(listOf(55f), h.ports.breakdownScrolls.toList())
        assertEquals(listOf(-30f to 12f), h.ports.hintParks.toList())
        assertEquals(listOf(5f to -4f), h.ports.claimParks.toList())
        assertEquals(1, h.ports.fullscreens)
        assertEquals(1, h.ports.tileLabelToggles)
        assertEquals(1, h.ports.soundToggles)
        assertEquals(listOf(6), h.ports.soundVolumes.toList())
        assertEquals(1, h.ports.toggleEmailOptInCount)
        assertEquals(6, h.ports.lastDigestIntervalHours)
    }

    @Test
    fun routeStateWriteReachesThePrefsEffect() {
        val h = Harness()
        val value = RouteUiValue("scroll", "v1:42.0")

        h.apply(Intent.WriteRouteUi("modal:score_table", value))

        assertEquals(listOf("modal:score_table" to value), h.ports.routeUiWrites.toList())
        assertEquals(listOf(false), h.ports.routeUiRemember.toList())
    }

    @Test
    fun routeStateWriteCarriesTheRememberFlag() {
        val h = Harness()

        h.apply(Intent.WriteRouteUi("modal:duplicate_setup", RouteUiValue("setupCard", "v1:1:true:0.0"), remember = true))

        assertEquals(listOf(true), h.ports.routeUiRemember.toList())
    }

    @Test
    fun loginAndLogoutReachAccountInEveryKind() {
        for (kind in SessionKind.entries) {
            val h = Harness()
            h.ports.kind = kind
            h.apply(Intent.LogIn)
            h.apply(Intent.LogOut)
            assertEquals(1, h.ports.logIns, kind.name)
            assertEquals(
                listOf(ConfirmPrompt.LogOut),
                h.ports.confirms.toList(),
                kind.name,
            )
        }
    }

    @Test
    fun pushRoutesMapToOpenEffects() {
        val h = Harness()
        h.apply(Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE)))
        h.apply(Intent.PushRoute(Route.Modal(RouteIds.GAME_MENU)))
        h.apply(Intent.PushRoute(Route.Modal(RouteIds.LANGUAGE_PICKER)))
        h.apply(Intent.PushRoute(Route.Modal(RouteIds.SETTINGS)))
        h.apply(Intent.PushRoute(Route.Modal(RouteIds.NEW_GAME_SETUP)))

        assertEquals(1, h.ports.scoreOpens)
        assertEquals(1, h.ports.menuShows)
        assertEquals(1, h.ports.languageOpens)
        assertEquals(1, h.ports.settingsOpens)
        assertEquals(1, h.ports.setupEnters)
    }

    @Test
    fun confirmPushCarriesItsQuestion() {
        val h = Harness()
        h.apply(Intent.PushRoute(ConfirmPrompt.Forfeit.route()))
        assertEquals(
            listOf(ConfirmPrompt.Forfeit),
            h.ports.confirms.toList(),
        )
    }

    /**
     * The reported defect: a menu row that raised a confirm left the game-menu
     * card standing under it, so once the question was dismissed the leftover card
     * ate the next back and the menu took two presses to reopen.
     *
     * Drives the production raise helper ([replaceMenuCardWith]) and the production
     * back mapping ([FeatureRegistry.back]) over a real [Router] built from the
     * production [RouteSpecs]; the effect port only records what back resolves to.
     * Swept over every question, because the rule belongs to the raise, not to one
     * prompt.
     */
    @Test
    fun aConfirmRaisedOverTheGameMenuReplacesItAndBackReopensTheMenuInOneStep() {
        val h = Harness()
        val registry = FeatureRegistry().also { GameFeatures.install(it, featureDeps()) }
        // Production back for every route the scenario can land on, the leftover
        // game-menu card included: if it survives the raise, back reaches its
        // HideMenu instead of the board's ShowMenu.
        h.backIntents = mapOf(
            Route.Layer(RouteIds.LOCAL_BOARD).key to registry.back(Route.Layer(RouteIds.LOCAL_BOARD)),
            Route.Modal(RouteIds.GAME_MENU).key to registry.back(Route.Modal(RouteIds.GAME_MENU)),
            Route.Modal(RouteIds.CONFIRM).key to registry.back(Route.Modal(RouteIds.CONFIRM)),
        )

        for (prompt in ConfirmPrompt.entries) {
            val router = Router(
                Route.Screen(RouteIds.ROOT_MENU),
                { FakeComponent(ComponentId(it.key.value)) },
                { registry.specFor(it) },
            )
            val routes = RouteController(router)
            routes.pushAboveFloor(Route.Layer(RouteIds.LOCAL_MENU))
            routes.push(Route.Layer(RouteIds.LOCAL_BOARD))
            routes.push(Route.Modal(RouteIds.GAME_MENU))

            routes.replaceMenuCardWith(prompt.route())

            assertFalse(
                router.stack.contains(Route.Modal(RouteIds.GAME_MENU).key),
                "$prompt: the menu card must not stand under the confirm",
            )
            assertTrue(router.stack.contains(Route.Modal(RouteIds.CONFIRM).key), "$prompt")

            // Dismiss without answering: the running game is what is left, and one
            // back must raise the menu rather than be eaten by a leftover card.
            routes.removeModal(RouteIds.CONFIRM)
            h.stack = router.stack
            h.ports.menuShows = 0
            h.ports.menuHides = 0
            h.apply(Intent.Back)

            assertEquals(1, h.ports.menuShows, "$prompt: back must reopen the menu")
            assertEquals(0, h.ports.menuHides, "$prompt: no leftover card to hide")
        }
    }

    /** GL-free deps: `install` never runs a provider, so error stamps stay loud. */
    private fun featureDeps(): FeatureDeps = FeatureDeps(
        table = { error("no GL in tests: the table provider must stay lazy") },
        hintButtonOverlay = { error("no GL in tests: the hint overlay provider must stay lazy") },
    )

    @Test
    fun onlineContextRoutesReachTheOnlineEffect() {
        val h = Harness()

        // Leaving a table is the online effect's own landing, not a screen
        // replacement chosen by session kind: the hand-end Return home chip and the
        // connect card's Return home are the same intent.
        h.apply(Intent.OnlineReturnHome)
        assertEquals(1, h.ports.returnHomeCount)

        // The root menu is the floor: leaving for it is the session effect's
        // landing — pop back to the root — not a navigation the dispatcher picks.
        h.apply(Intent.ExitToMainMenu)
        assertEquals(1, h.ports.exitToMenus)
    }

    @Test
    fun aMailDeepLinkOpensTheHubShadowRankedTab() {
        val h = Harness()

        // The play page's `#my_challenges` fragment is the one deep link; it now
        // names the hub's Shadow Ranked tab, where the attempts list lives.
        val intent = deepLinkStartupIntent(DeepLink.targetOf("#my_challenges"))
        assertTrue(h.apply(intent!!), "the deep-link intent must be handled")

        assertEquals(listOf(OnlineTab.ShadowRanked), h.ports.hubOpens.toList())
        assertNull(deepLinkStartupIntent(DeepLink.targetOf("#other")), "unknown fragments open nothing")
    }

    @Test
    fun onlineHubIntentsReachTheOnlineEffect() {
        val h = Harness()

        h.apply(Intent.OpenOnline(OnlineTab.ShadowRanked))
        h.apply(Intent.OpenOnline(null))
        h.apply(Intent.SelectOnlineTab(OnlineTab.Live))
        h.apply(Intent.LeaveOnlineHub)
        h.apply(Intent.StartShadowRanked)
        h.apply(Intent.CancelShadowRankedStart)
        h.apply(Intent.RetryShadowRankedStart)
        h.apply(Intent.RefreshShadowRankedAttempts)
        h.apply(Intent.ViewShadowRankedAttempts)
        h.apply(Intent.LoadMoreShadowRankedAttempts)
        h.apply(Intent.ShadowRankedAttemptsDismiss)

        assertEquals(listOf(OnlineTab.ShadowRanked, null), h.ports.hubOpens.toList())
        assertEquals(listOf(OnlineTab.Live), h.ports.hubTabs.toList())
        assertEquals(1, h.ports.hubLeaves)
        assertEquals(1, h.ports.shadowStarts)
        assertEquals(1, h.ports.shadowCancels)
        assertEquals(1, h.ports.shadowRetries)
        assertEquals(1, h.ports.shadowRefreshes)
        assertEquals(1, h.ports.viewAttemptsCount)
        assertEquals(1, h.ports.loadMoreAttemptsCount)
        assertEquals(1, h.ports.attemptsDismissCount)
    }

    @Test
    fun playAgainstBotsAndTutorialEnterFromRoot() {
        val h = Harness()

        h.apply(Intent.PlayAgainstBots)
        assertEquals(1, h.ports.entersFromRoot)

        h.apply(Intent.OpenTutorial)
        assertEquals(1, h.ports.tutorialOpens)
    }

    @Test
    fun backAppliesTopmostDeclaredIntent() {
        val h = Harness()
        h.backIntents = mapOf(
            Route.Screen(RouteIds.LOCAL_BOARD).key to Intent.ShowMenu,
            Route.Modal(RouteIds.GAME_MENU).key to Intent.HideMenu,
            Route.Modal(RouteIds.SCORE_TABLE).key to Intent.DismissScoreTable,
        )
        h.stack = stackOf(
            Route.Screen(RouteIds.LOCAL_BOARD),
            Route.Modal(RouteIds.GAME_MENU),
            Route.Modal(RouteIds.SCORE_TABLE),
        )

        h.apply(Intent.Back)

        assertEquals(0, h.ports.menuShows)
        assertEquals(0, h.ports.menuHides)
        assertEquals(1, h.ports.scoreDismisses)
    }

    @Test
    fun backSkipsRoutesThatDeclareNoBack() {
        val h = Harness()
        h.backIntents = mapOf(Route.Screen(RouteIds.TUTORIAL_LESSON).key to Intent.ShowMenu)
        h.stack = stackOf(
            Route.Screen(RouteIds.TUTORIAL_LESSON),
            Route.Overlay(RouteIds.TUTORIAL_QUIZ),
            Route.Overlay(RouteIds.TUTORIAL_COACH),
        )

        h.apply(Intent.Back)

        assertEquals(1, h.ports.menuShows)
    }

    @Test
    fun backIsNoOpWhenNoRouteDeclaresBack() {
        val h = Harness()
        h.backIntents = emptyMap()
        h.stack = stackOf(Route.Screen(RouteIds.ROOT_MENU))

        assertFalse(h.apply(Intent.Back))
        assertEquals(0, h.ports.menuShows)
        assertEquals(0, h.ports.menuHides)
    }

    @Test
    fun referenceIntentsRouteThroughPorts() {
        val h = Harness()
        h.apply(Intent.OpenReference("2.1"))
        h.apply(Intent.OpenReference(null))
        h.apply(Intent.OpenReferenceSection("rules"))
        h.apply(Intent.OpenReferenceExternal("https://example.com"))
        h.apply(Intent.ReferenceBack)

        assertEquals(listOf("2.1", null), h.ports.references.toList())
        assertEquals(listOf("rules"), h.ports.sections.toList())
        assertEquals(listOf("https://example.com"), h.ports.externals.toList())
        assertEquals(1, h.ports.referenceBacks)
    }

    @Test
    fun tutorialSelectRequestsRestartConfirmOnlyOnSignal() {
        val h = Harness()
        h.ports.selectResult = false
        h.apply(Intent.TutorialSelect("lesson"))
        assertEquals(listOf("lesson"), h.ports.selectedLessons.toList())
        assertTrue(h.ports.confirms.isEmpty())

        h.ports.selectResult = true
        h.apply(Intent.TutorialSelect("lesson"))
        assertEquals(
            listOf(ConfirmPrompt.RestartLesson),
            h.ports.confirms.toList(),
        )
    }

    @Test
    fun everyFeatureIntentIsHandled() {
        val h = Harness()
        val intents = listOf(
            Intent.Back,
            Intent.ShowMenu,
            Intent.HideMenu,
            Intent.DismissReference,
            Intent.DismissScoreTable,
            Intent.DismissLanguagePicker,
            Intent.DismissSettings,
            Intent.CancelSetup,
            Intent.MatchHistoryDismiss,
            Intent.PushRoute(Route.Modal(RouteIds.GAME_MENU)),
            Intent.PopRoute(RouteKey("modal:${RouteIds.CONFIRM}")),
            Intent.ExitToMainMenu,
            Intent.Discard(1),
            Intent.DiscardDrawnTile,
            Intent.ApplyChoice(Choice.pass()),
            Intent.Hint,
            Intent.AiAssist,
            Intent.NextHand,
            Intent.NewGame,
            Intent.PlayAgain,
            Intent.ParkScoreBreakdown(0f, 0f),
            Intent.ToggleScoreBreakdownFold,
            Intent.SetScoreBreakdownScroll(0f),
            Intent.WriteRouteUi("modal:score_table", RouteUiValue("scroll", "v1:0.0")),
            Intent.ParkHintButton(0f, 0f),
            Intent.ParkClaimPanel(0f, 0f),
            Intent.StartSearch,
            Intent.CancelSearch,
            Intent.LogIn,
            Intent.LogOut,
            Intent.SetMinOtherHumans(2),
            Intent.CycleRounds,
            Intent.CycleChicken,
            Intent.ViewMatchHistory,
            Intent.OpenLeaderboard,
            Intent.LeaderboardDismiss,
            Intent.LoadLeaderboardWindow(50),
            Intent.ToggleWaiting,
            Intent.RejoinTable,
            Intent.OpenOnline(OnlineTab.Live),
            Intent.SelectOnlineTab(OnlineTab.ShadowRanked),
            Intent.LeaveOnlineHub,
            Intent.StartShadowRanked,
            Intent.CancelShadowRankedStart,
            Intent.RetryShadowRankedStart,
            Intent.RefreshShadowRankedAttempts,
            Intent.ViewShadowRankedAttempts,
            Intent.ShadowRankedAttemptsDismiss,
            Intent.OpenShadowRankedHands("7K3H-M9PQ"),
            Intent.ShadowRankedHandsDismiss,
            Intent.ShadowRankedHandsStep(1),
            Intent.ToggleShadowRankedHandsDropdown,
            Intent.SelectShadowRankedHandsAttempt(0),
            Intent.EditDisplayName,
            Intent.SaveDisplayName,
            Intent.CancelDisplayName,
            Intent.OnlineConnectCancel,
            Intent.OnlineConnectRetry,
            Intent.OnlineReturnHome,
            Intent.FirstLoginSubmit,
            Intent.FirstLoginBack,
            Intent.StartingLevelPick("novice"),
            Intent.AwayResume,
            Intent.LoadMoreMatchHistory,
            Intent.ToggleFullscreen,
            Intent.ExitApp,
            Intent.ToggleTileLabels,
            Intent.ToggleSounds,
            Intent.SetSoundVolume(4),
            Intent.ToggleEmailOptIn,
            Intent.SetDigestIntervalHours(6),
            Intent.PickLocale(AppLocale.English.tag),
            Intent.OpenReference(),
            Intent.ReferenceBack,
            Intent.OpenReferenceSection("rules"),
            Intent.OpenReferenceExternal("https://example.com"),
            Intent.ConfirmYes,
            Intent.ConfirmNo,
            Intent.TutorialPrimary,
            Intent.TutorialBack,
            Intent.TutorialSelect("lesson"),
            Intent.TutorialQuizSubmit(QuizAnswer()),
            Intent.TutorialQuizNext,
            Intent.TutorialQuizTryAgain,
            Intent.TutorialQuizStateChanged(QuizInteractionState.Fresh),
            Intent.TutorialOpenHref("ref:x"),
        )
        for (intent in intents) {
            assertTrue(h.apply(intent), "not handled: $intent")
        }
    }

    @Test
    fun unknownPushRouteIsLoggedAndIgnored() {
        val h = Harness()
        assertFalse(h.apply(Intent.PushRoute(Route.Screen("nope"))))
        assertTrue(h.logs.isNotEmpty())
    }

    @Test
    fun exitAppQuitsTheRuntime() {
        val h = Harness()
        assertTrue(h.apply(Intent.ExitApp))
        assertEquals(1, h.ports.exits)
    }

    @Test
    fun pickLocaleResolvesThePublishedTag() {
        val h = Harness()
        h.apply(Intent.PickLocale(AppLocale.Russian.tag))
        assertEquals(listOf(AppLocale.Russian), h.ports.locales)
    }

    @Test
    fun unknownLocaleIsLoggedAndIgnored() {
        val h = Harness()
        h.apply(Intent.PickLocale("nope"))
        assertTrue(h.ports.locales.isEmpty())
        assertTrue(h.logs.isNotEmpty())
    }
}
