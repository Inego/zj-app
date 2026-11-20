package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import inego.zungjunggame.AppVersion
import inego.zungjunggame.BootDiagnostics
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engines.DefaultEngine
import inego.zungjunggame.features.board.BoardChrome
import inego.zungjunggame.features.board.BoardRenderInput
import inego.zungjunggame.features.board.FreePlayPresenter
import inego.zungjunggame.features.board.FreePlayRenderFacts
import inego.zungjunggame.features.board.OnlineRenderFacts
import inego.zungjunggame.features.board.TableChrome
import inego.zungjunggame.features.board.boardTableInputs
import inego.zungjunggame.features.history.MatchHistoryRenderInput
import inego.zungjunggame.features.menu.GameMenuRenderInput
import inego.zungjunggame.features.menu.MenuSignIn
import inego.zungjunggame.features.notices.NoticesOverlay
import inego.zungjunggame.features.notices.NoticesRenderInput
import inego.zungjunggame.features.online.hub.DEFAULT_ONLINE_HUB_STATE
import inego.zungjunggame.features.online.hub.OnlineHubCodec
import inego.zungjunggame.features.online.hub.OnlineHubOverlay
import inego.zungjunggame.features.online.hub.OnlineHubView
import inego.zungjunggame.features.online.hub.ShadowRankedView
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.input.InputNormalizer
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.IntentSink
import inego.zungjunggame.platform.OverlayTextPrompt
import inego.zungjunggame.platform.PresenceScheduler
import inego.zungjunggame.net.ApiConfig
import inego.zungjunggame.net.AuthApi
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.LeaderboardClient
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.net.ShadowRankedAttemptsClient
import inego.zungjunggame.net.ShadowRankedHandsClient
import inego.zungjunggame.net.ShadowRankedPoolClient
import inego.zungjunggame.net.ShadowRankedStartClient
import inego.zungjunggame.online.AccountController
import inego.zungjunggame.online.AuthLifecycle
import inego.zungjunggame.online.OnlineAwayPort
import inego.zungjunggame.online.OnlineCoordinator
import inego.zungjunggame.online.OnlineTablePort
import inego.zungjunggame.online.ShadowRankedPoolTracker
import inego.zungjunggame.online.ShadowRankedStarter
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.persist.AppLocaleKey
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.persist.DismissedFirstLoginPromptsKey
import inego.zungjunggame.persist.FindGamePreferencesKey
import inego.zungjunggame.persist.GameConfigKey
import inego.zungjunggame.persist.NotificationPrefsKey
import inego.zungjunggame.persist.OnlineTableIdKey
import inego.zungjunggame.persist.ReferenceNavCodec
import inego.zungjunggame.persist.RememberedRouteUiKey
import inego.zungjunggame.persist.SessionSnapshotKey
import inego.zungjunggame.persist.TutorialSessionSnapshotKey
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.features.away.AwayOverlay
import inego.zungjunggame.features.confirm.ConfirmOverlay
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.features.auth.AccountOverlay
import inego.zungjunggame.features.auth.AliasCardOverlay
import inego.zungjunggame.features.auth.AliasPrompt
import inego.zungjunggame.features.auth.NativeAliasPrompt
import inego.zungjunggame.features.auth.StackAliasPrompt
import inego.zungjunggame.features.menu.GameMenuOverlay
import inego.zungjunggame.features.settings.LanguagePickerOverlay
import inego.zungjunggame.features.menu.MenuContext
import inego.zungjunggame.render.gameMenuFlags
import inego.zungjunggame.render.assets.ManagedGpuResources
import inego.zungjunggame.render.assets.registerWhitePixelRecovery
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.render.ui.UiChip
import inego.zungjunggame.features.challenges.ShadowRankedAttemptsOverlay
import inego.zungjunggame.features.challenges.ShadowRankedAttemptsRenderInput
import inego.zungjunggame.features.challenges.ShadowRankedHandsOverlay
import inego.zungjunggame.features.challenges.ShadowRankedHandsRenderInput
import inego.zungjunggame.features.history.MatchHistoryOverlay
import inego.zungjunggame.features.leaderboard.LeaderboardOverlay
import inego.zungjunggame.features.leaderboard.LeaderboardRenderInput
import inego.zungjunggame.features.local.NewGameSetupOverlay
import inego.zungjunggame.features.local.SetupCardCodec
import inego.zungjunggame.features.online.OnlineConnectOverlay
import inego.zungjunggame.features.online.OnlineSettlementPresenter
import inego.zungjunggame.features.ratinghistory.RatingHistoryOverlay
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.render.table.TableApply
import inego.zungjunggame.render.table.TableApplyKind
import inego.zungjunggame.render.table.TableRenderInputs
import inego.zungjunggame.features.reference.ReferenceBrowserOverlay
import inego.zungjunggame.features.score.ScoreBreakdownOverlay
import inego.zungjunggame.features.score.ScoreTableOverlay
import inego.zungjunggame.features.settings.SettingsNotifications
import inego.zungjunggame.features.settings.SettingsOverlay
import inego.zungjunggame.features.auth.StartingLevelOverlay
import inego.zungjunggame.features.tutorial.TutorialCoachOverlay
import inego.zungjunggame.features.tutorial.TutorialQuizOverlay
import inego.zungjunggame.features.tutorial.TutorialShell
import inego.zungjunggame.features.tutorial.TutorialSnapshot
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.runtime.AppModel
import inego.zungjunggame.runtime.ComponentHost
import inego.zungjunggame.runtime.ComponentRegistry
import inego.zungjunggame.runtime.FeatureDeps
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.GameFeatures
import inego.zungjunggame.runtime.GdxUiEnvironment
import inego.zungjunggame.runtime.RegistryComponentFactory
import inego.zungjunggame.runtime.RegistrySpecProvider
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.BotTiming
import inego.zungjunggame.session.board.ChoiceGate
import inego.zungjunggame.session.board.LocalEventEmitter
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.table.DiscardOrigin
import inego.zungjunggame.session.board.EngineNonHumanPicker
import inego.zungjunggame.session.hosts.FreePlayBreakdownPort
import inego.zungjunggame.session.hosts.FreePlayHost
import inego.zungjunggame.session.hosts.FreePlayMenuPort
import inego.zungjunggame.session.hosts.FreePlayScoreTablePort
import inego.zungjunggame.session.hosts.FreePlaySetupPort
import inego.zungjunggame.session.hosts.TableHandApply
import inego.zungjunggame.session.hosts.LocalPlayHost
import inego.zungjunggame.session.board.NonHumanTurnStepper
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.session.online.PresentationSequencer
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.FileStateBackend
import inego.zungjunggame.state.PlatformSecretBox
import inego.zungjunggame.state.PlatformStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.state.StateStore
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialHost
import inego.zungjunggame.tutorial.TutorialPlayHost
import inego.zungjunggame.tutorial.TutorialProgress
import inego.zungjunggame.tutorial.TutorialProgressKey
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.Scope
import inego.zungjunggame.ui.TextFieldHost
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState
import inego.zungjunggame.ui.stack.StackStateKey
import inego.zungjunggame.ui.stack.toRoute
import inego.zungjunggame.ui.stack.idOrNull

/**
 * Composition root for the runtime: builds the runtime graph, then layers the
 * router / registry / dispatcher / raw-input pieces on top and returns an
 * [AppRuntime].
 *
 * The router stack is the sole screen authority; the per-domain effects under
 * `app/` apply destinations and overlays onto it. The host constructor lambdas
 * call the effect that owns their concern (the session effect for confirm and
 * hint-choice, the navigation effect for the tutorial's reference deep link, the
 * prefs effect for settings), and the tutorial host's reference seam opens the
 * browser through it.
 * This class also supplies the features' render-input providers from the live
 * session so their wrapped overlays paint with real values.
 */
class AppGraph(
    private val batch: SpriteBatch,
    private val camera: OrthographicCamera,
    private val viewport: ScreenViewport,
    private val onToggleFullscreen: () -> Unit,
    private val onExit: () -> Unit,
    private val onExitRequest: () -> Unit,
    /** The app lifetime: every screen and process-global resource is built through it. */
    private val scope: Scope,
) {
    // The composition root owns every screen instance; a launcher owns the GL shell
    // (batch/camera/viewport) and nothing else. These are built with the graph — on
    // the bootstrap frame, with the felt frame already on screen — so no launcher
    // names a screen type.
    private val confirm = scope.own("confirm") { ConfirmOverlay() }
    private val onlineConnect = scope.own("onlineConnect") { OnlineConnectOverlay() }
    // One shared card for all three menu routes: each GameMenuComponent owns its
    // entry's player-set state in the route UI store and hands it to this widget
    // every frame.
    private val gameMenu = scope.own("gameMenu") { GameMenuOverlay() }
    private val languagePicker = scope.own("languagePicker") { LanguagePickerOverlay() }
    private val settingsOverlay = scope.own("settings") { SettingsOverlay() }
    private val accountOverlay = scope.own("account") { AccountOverlay() }
    // One shared card for both alias routes; only one is ever open, so its draft
    // and busy state cannot be fought over (see AliasCardFeature).
    private val aliasCardOverlay = scope.own("aliasCard") { AliasCardOverlay() }
    private val startingLevelOverlay = scope.own("startingLevel") { StartingLevelOverlay() }
    private val onlineHub = scope.own("onlineHub") { OnlineHubOverlay() }
    private val matchHistory = scope.own("matchHistory") { MatchHistoryOverlay() }
    private val leaderboard = scope.own("leaderboard") { LeaderboardOverlay() }
    private val ratingHistory = scope.own("ratingHistory") { RatingHistoryOverlay() }
    private val shadowRankedAttemptsOverlay = scope.own("shadowRankedAttempts") { ShadowRankedAttemptsOverlay() }
    private val shadowRankedHandsOverlay = scope.own("shadowRankedHands") { ShadowRankedHandsOverlay() }
    private val notices = scope.own("notices") { NoticesOverlay() }
    private val noticePopup = scope.own("noticePopup") { NoticesOverlay(compact = true) }
    private val referenceBrowser = scope.own("referenceBrowser") { ReferenceBrowserOverlay() }
    private val awayOverlay = scope.own("away") { AwayOverlay() }
    private val tutorialShell = scope.own("tutorialShell") { TutorialShell() }
    private val tutorialQuiz = scope.own("tutorialQuiz") { TutorialQuizOverlay() }
    private val tutorialCoach = scope.own("tutorialCoach") { TutorialCoachOverlay() }
    /**
     * The single HINT button every entry shares. Its [UiChip] is the resource, so
     * the scope owns the chip and this overlay is the view over it; lazy because the
     * chip needs the live GL context that exists by the first layout.
     */
    private val hintButtonOverlay: HintButtonOverlay by lazy {
        HintButtonOverlay(scope.own("hintButtonChip") { UiChip() })
    }

    fun create(): AppRuntime {
        BootDiagnostics.mark("bootstrapApp:begin")
        val tBoot = System.nanoTime()
        // This batch is the one object whose GL state nothing else
        // owns, and it is what fails to draw after a context loss. Register its
        // recovery here, at the composition root, because the batch is built by
        // `Main` and reaches everything else as a constructor parameter.
        ManagedGpuResources.register("SpriteBatch") {
            ManagedGpuResources.rebuildBatchShader(batch)
        }
        // Mesh invalidation is app-wide, not per-owner: libGDX keeps its own registry
        // keyed by `Gdx.app`, so one step covers every `Mesh` in the process.
        ManagedGpuResources.register("meshes") {
            ManagedGpuResources.invalidateManagedMeshes()
        }
        // The shared 1×1 white pixel is the app's only unmanaged texture that every
        // panel draws through, so it is registered last, next to its two siblings.
        registerWhitePixelRecovery()
        ApiConfig.loadFromLocalFile()
        BootDiagnostics.mark("bootstrapApp:api", ApiConfig.baseUrl)
        // A web launch may arrive with a deep link (a mail link's `#my_challenges`).
        // Read it once and turn it into the single startup intent the runtime fires
        // when the online session can honor it.
        val startupIntent = deepLinkStartupIntent(DeepLink.consume())
        BootDiagnostics.mark("bootstrapApp:deepLink", startupIntent?.toString() ?: "none")
        // One versioned store for every persisted artifact, built before anything
        // reads state: the cold start is a single replay of these keys. The
        // preferences store carries the platform secret box, so a key marked
        // secret (the auth session) is sealed rather than written in the clear.
        //
        // The preferences container ([PreferencesStateBackend.STATE_NAME]) and the
        // file-state directory ([FileStateBackend.STATE_DIR]) must not share a
        // name: on desktop the first is a file at the same depth as the second,
        // and one path cannot be both.
        val secretBox = PlatformSecretBox.create()
        // The browser installs a live per-key backend here; every other platform
        // uses the libGDX preferences container (see [PlatformStateBackend]).
        val stateBackend = PlatformStateBackend.create()
        val stateFilePath = FileStateBackend(FileStateBackend.STATE_DIR)
        val stateStore: StateStore = DefaultStateStore(stateBackend, secretBox = secretBox)
        val stateFiles: StateStore = DefaultStateStore(stateFilePath)
        val state = AppStateSource(
            stack = StateSlot(stateStore, StackStateKey),
            session = StateSlot(stateFiles, SessionSnapshotKey),
            tutorialSession = StateSlot(stateFiles, TutorialSessionSnapshotKey),
            account = StateSlot(stateStore, AuthPersistedSessionKey),
            onlineTableId = StateSlot(stateStore, OnlineTableIdKey),
            game = StateSlot(stateStore, GameConfigKey),
            ui = StateSlot(stateStore, UiPreferencesKey),
            locale = StateSlot(stateStore, AppLocaleKey),
            find = StateSlot(stateStore, FindGamePreferencesKey),
            tutorialProgress = StateSlot(stateStore, TutorialProgressKey),
            firstLoginPrompts = StateSlot(stateStore, DismissedFirstLoginPromptsKey),
            remembered = StateSlot(stateStore, RememberedRouteUiKey),
            notificationPrefs = StateSlot(stateStore, NotificationPrefsKey),
        )
        // Nothing is flushed here: the runtime flushes on pause/exit/dispose.
        // The cold start's one read of persisted state; the replay that consumes
        // it is at the end of this method, after the graph it restores into.
        val boot = state.read()

        val inputProfile = InputProfile.detect()
        BootDiagnostics.mark("bootstrapApp:inputProfile", inputProfile.toString())
        LocaleController.bootstrap(localeSlot = state.locale, locale = boot.locale)
        BootDiagnostics.mark("bootstrapApp:locale", LocaleController.current.tag)

        TileLabels.enabled = boot.ui.showTileLabels
        // The one audio resource: it owns the Sound handles and gates on `ui.prefs`.
        val soundPlayer = scope.own("sound") { AppSoundPlayer(state.ui) }
        BootDiagnostics.mark("bootstrapApp:PlayTableView:begin")
        val table = scope.own("table") { PlayTableView() }
        BootDiagnostics.mark("bootstrapApp:PlayTableView:done")
        val engine = DefaultEngine()
        // Render overlay ownership now lives here (composition root) rather than
        // inside FreePlayHost; the host drives them through session ports.
        val scoreOverlay = scope.own("scoreTable") { ScoreTableOverlay() }
        val scoreBreakdownOverlay = scope.own("scoreBreakdown") { ScoreBreakdownOverlay() }
        val newGameSetup = scope.own("newGameSetup") { NewGameSetupOverlay() }

        // The router is built near the end (the feature registry comes first), so
        // the screen authority is late-bound like the effects below it.
        lateinit var transitions: ScreenTransitions
        // Assigned with the other effects, once the router exists; the host
        // constructor lambdas above resolve them at call time.
        lateinit var sessionEffects: AppSessionEffects
        lateinit var navigationEffects: AppNavigationEffects
        lateinit var prefsEffects: AppPrefsEffects
        lateinit var chromeEffects: AppChromeEffects
        lateinit var freePlayHost: FreePlayHost
        lateinit var freePlayPresenter: FreePlayPresenter
        lateinit var localPlayHost: LocalPlayHost
        lateinit var tutorialHost: TutorialHost
        lateinit var tutorialPlayHost: TutorialPlayHost
        lateinit var onlineCoordinator: OnlineCoordinator
        lateinit var accountController: AccountController
        // Declared before the online table port so its hub tab seam can resolve the
        // effect at call time; assigned with the other effects once the hub's
        // collaborators exist.
        lateinit var onlineEffects: AppOnlineEffects

        // The Router is created once the feature registry is populated; the
        // registry/floor writers below resolve it through this holder.
        val routerHolder = arrayOfNulls<Router>(1)
        val dispatcherHolder = arrayOfNulls<IntentSink>(1)
        val aliasPrompt: AliasPrompt = if (inputProfile.usesKeyboard) {
            StackAliasPrompt(
                router = { routerHolder[0] },
                overlay = aliasCardOverlay,
            )
        } else {
            NativeAliasPrompt(
                prompt = OverlayTextPrompt.create(),
                emit = { dispatcherHolder[0]?.emit(it) },
            )
        }

        // The input layer is built here, before anything can log through it. The
        // auth session reports its own load through [input], and while this was a
        // `lateinit` assigned near the end of the method that read threw the moment
        // the store held a session. Nothing in it is late-bound: the profile is
        // detected above, and the text-focus probe walks the stack at call time.
        val input = PlatformInput(
            profile = inputProfile,
            textFocus = {
                routerHolder[0]?.stack?.entries?.asReversed()
                    ?.firstNotNullOfOrNull { it.component as? TextFieldHost }
                    ?.takeIf { it.textFieldFocused }
            },
        )
        val rawInput = GdxRawInput(camera)
        input.install(
            onTouchCancelled = { rawInput.markTouchCancelled() },
            onScroll = { amountX, amountY -> rawInput.recordScroll(amountX, amountY) },
        )

        // Lazy providers: hosts/hand are lateinit and assigned below; the
        // registry reads them live, so no lifecycle notification is needed.
        val sessionRegistry = SessionRegistry(
            stack = { routerHolder[0]?.stack ?: error("Router is not ready") },
            localHostSource = { localPlayHost },
            tutorialHostSource = { tutorialPlayHost.takeIf { it.isActive } }, // S3: TutorialPlayHost
            onlineHostSource = { onlineCoordinator.onlineHost },
            handSource = { tutorialHost.runner ?: freePlayHost.session },
            onlineTableIdSource = { onlineCoordinator.onlineHost?.tableId },
        )

        // The online base is the ONLINE_HUB context layer. [onlineEffects] is built
        // after the account controller, so present/dismiss are late-bound here and
        // wired once it exists.
        var presentOnline: () -> Unit = {}
        var dismissOnline: () -> Unit = {}

        // The player-set state of each route on the stack, in memory; the stack
        // snapshot is its persistence form. A route whose spec remembers its state
        // also writes through to `ui.remembered`, so its value survives the entry.
        // Built before the ports so a port that answers with a route's state reads
        // the store, and handed to the router below so an entry's state leaves with
        // the entry.
        val routeUiState = RouteUiState(RememberedRouteUiStore(state.remembered))

        // Stack-owned menu/confirm surfaces: route pushes drive the shared
        // overlays, and the paint range owns what is up. The router itself is
        // built below, so these resolve it lazily by reference.
        val menuRoute = Route.Modal(RouteIds.GAME_MENU)
        val menuPort = object : FreePlayMenuPort {
            override fun hide() {
                routerHolder[0]?.remove(menuRoute.key)
            }
        }

        // S14: the local setup / score-table / hand-end breakdown are stack-owned.
        // These ports push/pop the routes; the paint range owns the overlays. The
        // router is built below, so it is resolved lazily.
        val setupRoute = Route.Modal(RouteIds.NEW_GAME_SETUP)
        val setupPort = object : FreePlaySetupPort {
            // REPLACE: the setup card takes the game-menu card's place, so the
            // active-game confirm path and the finished-game direct path agree.
            override fun show() = transitions.openNewGameSetup()

            // Taking the draft and closing the card are one step: the route's state
            // dies with the route, so a read after the removal would see nothing.
            override fun consumeDraft(): GameConfig? {
                val stored = routeUiState.consume(setupRoute.key, SetupCardCodec)
                routerHolder[0]?.remove(setupRoute.key)
                return stored?.draft
            }
        }
        val scoreTableRoute = Route.Modal(RouteIds.SCORE_TABLE)
        val scoreTablePort = object : FreePlayScoreTablePort {
            // REPLACE for free play and online alike: the ledger is chrome over
            // the board, never a card under the menu.
            override fun show() = transitions.openScoreTable()

            override fun hide() {
                routerHolder[0]?.remove(scoreTableRoute.key)
            }
        }
        val breakdownRoute = Route.Overlay(RouteIds.SCORE_BREAKDOWN)
        val breakdownPort = object : FreePlayBreakdownPort {
            override fun show() {
                val router = routerHolder[0] ?: return
                if (router.isOpen(breakdownRoute.key)) return
                // A hand-end ledger is content: inside a lesson it belongs in the
                // shell's content group, below whatever card stands over the lesson
                // (`Router.pushContent`); free play has no provider, so it appends.
                router.pushContent(breakdownRoute)
            }

            override fun hide() {
                routerHolder[0]?.removeExact(breakdownRoute.key)
            }
        }

        // S18/N6: the tutorial shell context and its lesson / quiz / coach content
        // layers are stack-owned. The host announces show/hide; these push/remove
        // the routes and the paint range owns the overlays. The router is built
        // below, so it is resolved lazily.
        val tutorialRoutes = tutorialRoutesPort { routerHolder[0] }

        // S17: the away-during-match overlay is stack-owned. The online
        // coordinator announces show/hide; this pushes/removes the AWAY route and
        // the paint range owns the overlay.
        val awayRoute = Route.Overlay(RouteIds.AWAY)
        val onlineAway = object : OnlineAwayPort {
            override fun show() {
                val router = routerHolder[0] ?: return
                if (!router.isOpen(awayRoute.key)) router.push(awayRoute)
            }

            override fun hide() {
                routerHolder[0]?.remove(awayRoute.key)
            }
        }

        // Local play's presentation backend: it narrates each apply as the transitions the
        // server would have emitted, and feeds them to the same sequencer online play
        // uses, so one pipeline decides what both modes sound like. The seat and hand
        // are read live — they are the session's, and the board outlives any one host.
        val localEmitter = LocalEventEmitter(
            sequencer = PresentationSequencer(),
            mySeat = { sessionRegistry.playHost.humanSeat },
            handNumber = { freePlayHost.session.currentHandNumber() },
        )
        val board = BoardController(gate = ChoiceGate.Identity, emitter = localEmitter)

        val nonHumanStepper = NonHumanTurnStepper(
            isHumanInteractiveTurn = { state ->
                TableViewState.fromGameState(
                    state,
                    sessionRegistry.playHost.humanSeat,
                    tutorialHost.runner?.revealedSeats ?: emptySet(),
                ).isHumanInteractiveTurn
            },
            isAnimating = { table.isAnimating() },
            picker = EngineNonHumanPicker(board, engine) { sessionRegistry.liveHand.choiceRng },
            timing = BotTiming(),
            isHumanSeat = { it == sessionRegistry.playHost.humanSeat },
        )

        freePlayHost = scope.own("freePlayHost") {
            FreePlayHost(
                sessionSlot = state.session,
                gameConfigSlot = state.game,
                initialSetupDraft = boot.game,
                board = board,
                engine = engine,
                nonHumanStepper = nonHumanStepper,
                scoreOverlay = scoreTablePort,
                breakdown = breakdownPort,
                newGameSetup = setupPort,
                liveChoiceRng = { sessionRegistry.liveHand.choiceRng },
                sessionKind = { sessionRegistry.kind },
                requestConfirmNextHand = {
                    sessionEffects.confirm(ConfirmPrompt.AbandonHand)
                },
                requestConfirmNewGame = {
                    sessionEffects.confirm(ConfirmPrompt.NewGame)
                },
                clearTutorialForFreePlayStart = { tutorialHost.clearForFreePlayStart() },
                leaveOnlineForFreePlayStart = { onlineCoordinator.leaveOnlineForLocalPlay() },
                allowTutorialAiStep = {
                    tutorialHost.runner?.status == TutorialDirector.Status.Running
                },
                // Resolved at call time; the presenter is assigned just below.
                stopHint = { freePlayPresenter.stopMcHintSearch() },
                // Lazy: the provider is only invoked from GameSession.persist(),
                // never during construction.
                persistAllowed = { sessionRegistry.kind == SessionKind.FreePlay },
            )
        }

        freePlayPresenter = FreePlayPresenter(
            session = freePlayHost.session,
            table = table,
            breakdown = breakdownPort,
            board = board,
            engine = engine,
            sessionKind = { sessionRegistry.kind },
            playHintChoice = { choice -> sessionEffects.commitChoice(choice) },
        )

        val onlineSettlementPresenter = OnlineSettlementPresenter(breakdownPort)
        val auth = AuthSession(state.account)
        auth.lifecycleLog = { msg -> input.logLifecycle("Auth $msg") }
        // The credential is the one slice the floor probe needs (`online.table`
        // only restores for a signed-in session), so it is adopted here, before
        // the replay at the end; its surfaces still come last.
        auth.load(boot.account)
        // S19: the three online host callbacks are one port. The coordinator owns
        // whether a table is live; the app answers with the floor, the board, and
        // the chrome. Nothing here sets a SessionKind — it is derived from the
        // floor by the registry.
        // The table's stack landing and its origin tab; the port below adds the
        // board-side effects of a table change. The hub tab write resolves the online
        // effect at call time, so this can be built before the effect exists.
        val onlineTableSurfaces = OnlineTableSurfaces(
            transitions = { transitions },
            selectHubTab = { tab -> onlineEffects.hubSelectTab(tab) },
        )
        // Entries of the online table, oldest first. The board apply itself is
        // resolved on the frame that can read the attach's answer; see
        // [onlineTableApply].
        var onlineTableApplySeq = 0L
        val onlineTablePort = object : OnlineTablePort {
            override fun tableJoined(origin: OnlineTab) {
                onlineTableSurfaces.tableJoined(origin)
                onlineTableApplySeq++
                freePlayPresenter.stopMcHintSearch()
            }

            override fun tableExited() {
                // The table's layer goes with the table: a coordinator-initiated exit
                // (a remotely closed or aborted table, a server shutdown) has no caller
                // to land anywhere, so the layer pops itself and reveals the hub it
                // was entered from, on that table's origin tab. An explicit exit
                // lands on the hub right after, and finds it already up.
                onlineTableSurfaces.tableExited()
                freePlayPresenter.stopMcHintSearch()
                accountController.refreshAccountSurfaces()
            }
        }

        // The rating chart's tiles. Scope-owned like the other caches because it
        // holds async callbacks: a scope close drops a response still in flight and
        // releases every retained candle. Its token is resolved at send time — a
        // public player chart sends none — and the account lifecycle's unauthorized
        // handler is wired once the controller exists, below.
        val ratingHistoryClient = scope.own("ratingHistoryClient") {
            RatingHistoryClient(token = { accountController.token })
        }

        onlineCoordinator = scope.own("onlineCoordinator") {
            OnlineCoordinator(
                authSession = auth,
                presenceClient = PresenceClient(findPreferences = state.find),
                onlineTableId = state.onlineTableId,
                notificationPrefs = NotificationPrefsClient(state.notificationPrefs),
                onNoticesChanged = { onlineEffects.onNoticesUpdated() },
                away = onlineAway,
                sessionKind = { sessionRegistry.kind },
                tablePort = onlineTablePort,
                onRestoreLocalHost = { localPlayHost },
                onStartingLevelPromptRequested = {
                    accountController.showStartingLevelPrompt()
                },
                onSettledMatch = { _ ->
                    // Presence re-announces the latest settlement on every
                    // heartbeat and after a cold start, and its rating is the
                    // account only as of that match: a Shadow Ranked comparison
                    // (or a settlement on another device) may have moved it
                    // since. The account is the only writer of its own rating,
                    // so the event asks it to be re-read rather than applied.
                    accountController.refreshAccount()
                },
            )
        }
        // Shadow Ranked's attempts cache. Scope-owned because it holds an async
        // callback: a scope close drops a response still in flight. The account
        // lifecycle owns its reload and clear wiring, so it is handed in through
        // the constructor rather than hooked from here.
        val shadowRankedAttempts =
            scope.own("shadowRankedAttempts") { ShadowRankedAttemptsClient() }
        // The Shadow Ranked tab's pool reading. Scope-owned like the attempts list
        // because it holds an async callback; sign-out and account change clear it,
        // and nothing about it is persisted (a restored count would be a stale
        // fact). The client reports a rejected session to the account lifecycle's
        // one owner.
        val shadowRankedPoolClient = ShadowRankedPoolClient(
            onUnauthorized = { used -> accountController.onUnauthorized(used) },
        )
        val shadowRankedPool = scope.own("shadowRankedPool") {
            ShadowRankedPoolTracker(shadowRankedPoolClient)
        }
        // The hand tables of one challenge. Scope-owned like the attempts list
        // because it holds an async callback; the account lifecycle clears and
        // reloads it through the constructor.
        val shadowRankedHands =
            scope.own("shadowRankedHands") { ShadowRankedHandsClient() }
        // The hub's leaderboard reading. Scope-owned like the other list caches
        // because it holds an async callback; a scope close drops a response still
        // in flight. The account lifecycle's unauthorized handler is wired once the
        // controller exists, below.
        val leaderboardClient = scope.own("leaderboardClient") { LeaderboardClient() }
        // Shadow Ranked's start lifecycle. Built before the account controller so it
        // can be handed in there for the log-out reset; its callbacks resolve
        // [accountController] and [onlineEffects] at call time, so neither has to
        // exist yet. Scope-owned like the attempts cache because it holds async
        // callbacks: a scope close drops a response still in flight. The start
        // client reports a rejected session to the account lifecycle's one owner.
        val shadowRankedStartClient = ShadowRankedStartClient(
            onUnauthorized = { used -> accountController.onUnauthorized(used) },
        )
        val shadowRankedStarter = scope.own("shadowRankedStarter") {
            ShadowRankedStarter(
                token = { accountController.token },
                client = shadowRankedStartClient,
                onStarted = { start -> onlineEffects.onShadowRankedStarted(start) },
            )
        }
        accountController = AccountController(
            authSession = auth,
            presenceClient = onlineCoordinator.presenceClient,
            firstLoginPrompts = state.firstLoginPrompts,
            view = RouteAccountView(
                router = { routerHolder[0] },
                aliasPrompt = aliasPrompt,
                startingLevelOverlay = startingLevelOverlay,
                gameMenu = menuPort,
                presentOnline = { presentOnline() },
                dismissOnline = { dismissOnline() },
                // A session context owns the display; the account may not cover it.
                // Before the router exists there is no stack and no session.
                entrySurfaceFree = {
                    routerHolder[0] == null || !sessionRegistry.sessionOwnsDisplay
                },
            ),
            profileRequester = { token, done -> AuthApi.me(token, done) },
            matchHistory = onlineCoordinator.matchHistory,
            shadowRankedAttempts = shadowRankedAttempts,
            shadowRankedHands = shadowRankedHands,
            shadowRankedStarter = shadowRankedStarter,
            noticeList = onlineCoordinator.noticeList,
            notificationPrefs = onlineCoordinator.notificationPrefs,
            logLifecycle = { msg -> input.logLifecycle(msg) },
            onLogOut = {
                // A new account must not inherit the previous one's last-read hub
                // tab; the remembered Shadow Ranked draft is not account state.
                onlineEffects.resetHubTabForAccountChange()
                onlineCoordinator.leaveOnlineTable()
            },
            // The stack is replayed before the session is back, so a card restored
            // over a cold start first loads with no token. Only the effects layer can
            // see which surfaces are up, so the account layer announces the token.
            onSessionEstablished = { onlineEffects.sessionEstablished() },
            // Distinct from log-out: an expiry must not forfeit a live match (the host's
            // reconnect and the connect card own that), but it does mean a restored table
            // that never attached has no session left to adopt it.
            onSessionDropped = { onlineCoordinator.abandonRestoredTable() },
        )
        // A leaderboard request with a present but invalid token is the same 401 the
        // other authenticated reads report, so it runs the one refresh path.
        leaderboardClient.onUnauthorized = { used -> accountController.onUnauthorized(used) }
        ratingHistoryClient.onUnauthorized = { used -> accountController.onUnauthorized(used) }
        localPlayHost = LocalPlayHost(
            liveHand = freePlayHost.session,
            board = board,
            onNextHand = { freePlayHost.requestNextHand(sessionRegistry.liveHand) },
            currentHandNumberProvider = { freePlayHost.session.currentHandNumber() },
            maxHandsProvider = { freePlayHost.session.gameConfig.maxHands },
            allowChickenHandsProvider = { freePlayHost.session.gameConfig.allowChickenHands },
        )
        tutorialHost = TutorialHost(
            board = board,
            nonHumanStepper = nonHumanStepper,
            freePlayPicker = { freePlayHost.engineNonHumanPicker() },
            gameMenu = menuPort,
            sessionKind = { sessionRegistry.kind },
            stopMcHintSearch = { freePlayPresenter.stopMcHintSearch() },
            dismissConfirm = { sessionEffects.dismissConfirm() },
            logLifecycle = { message -> input.logLifecycle(message) },
            openReference = { target -> navigationEffects.openReference(target) },
            openSettings = { prefsEffects.openSettings() },
            routes = tutorialRoutes,
            progress = TutorialProgress(state.tutorialProgress),
            sessionSlot = state.tutorialSession,
        )
        tutorialPlayHost = TutorialPlayHost(
            runnerSource = { tutorialHost.runner },
            revealedSeats = { tutorialHost.runner?.revealedSeats ?: emptySet() },
            onChoice = { choice -> tutorialHost.tryHumanChoice(choice) },
            onDiscard = { tileType -> tutorialHost.tryHumanDiscard(tileType) },
            onLeave = { tutorialHost.exit() },
        )

        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)

        // ---- additions ----------------------------------------------------------
        val featureRegistry = FeatureRegistry()

        fun homeNoticeText(): String? {
            val notice = onlineCoordinator.homeNotice ?: return null
            return when (notice.kind) {
                OnlineCoordinator.HomeNoticeKind.TableAborted -> {
                    val detail = notice.detail
                    if (detail.isNullOrBlank()) {
                        L10n.get("online.live.notice_aborted")
                    } else {
                        L10n.format("online.live.notice_aborted_reason", detail)
                    }
                }
                OnlineCoordinator.HomeNoticeKind.GameUnavailable ->
                    L10n.get("online.live.notice_unavailable")
                OnlineCoordinator.HomeNoticeKind.SessionExpired ->
                    L10n.get("online.live.notice_session")
                OnlineCoordinator.HomeNoticeKind.MatchmakingCooldown -> {
                    val seconds = notice.detail?.takeIf { it.isNotBlank() } ?: "0"
                    L10n.format("online.live.notice_cooldown", seconds)
                }
                OnlineCoordinator.HomeNoticeKind.ServerShutdown -> {
                    val detail = notice.detail
                    if (detail.isNullOrBlank()) {
                        L10n.get("online.live.notice_shutdown")
                    } else {
                        L10n.format("online.live.notice_shutdown_reason", detail)
                    }
                }
            }
        }

        fun boardRenderInput(): BoardRenderInput = BoardRenderInput(
            showTurnStatus = tutorialHost.showTableTurnStatus(),
            decisionSlackMs = sessionRegistry.playHost.decisionSlackMs,
            decisionBaseMs = sessionRegistry.playHost.decisionBaseMs,
            statusFeedback = onlineCoordinator.onlineHost?.lastRejectedReason,
        )

        fun freePlayTableApply(): TableApply {
            val id = freePlayHost.tableHandApplyId
            val kind = when (freePlayHost.tableHandApply) {
                TableHandApply.None -> return TableApply.None
                TableHandApply.Reset -> TableApplyKind.Reset
                TableHandApply.Restored -> TableApplyKind.Restored
            }
            if (id == 0L) return TableApply.None
            return TableApply(id = "fp:$id", kind = kind)
        }

        /**
         * The board apply for the online table the player is entering, resolved
         * the moment the connection's `TableJoined` says which moment it is.
         * [inego.zungjunggame.session.online.OnlinePlayHost.runningAtAttach] false
         * means this attach is one of the players whose arrival starts the match:
         * the first snapshot is then the opening deal, and the board deals it out.
         * True means the table was already running, so the first snapshot is its
         * position and the board rematerializes it — a rejoin shows melds, hands
         * and rivers as they are instead of replaying a deal the player never sat
         * through. Before that answer there is no table view to show, so the apply
         * is a no-op; the entry's own sequence number keeps the resolved apply
         * consume-once.
         */
        fun onlineTableApply(): TableApply {
            val seq = onlineTableApplySeq
            if (seq == 0L) return TableApply.None
            val kind = when (onlineCoordinator.onlineHost?.runningAtAttach) {
                null -> return TableApply.None
                false -> TableApplyKind.Reset
                true -> TableApplyKind.Restored
            }
            return TableApply(id = "on:$seq", kind = kind)
        }

        fun boardInputs(
            session: SessionSnapshot,
            origin: DiscardOrigin?,
            tutorial: TutorialSnapshot,
        ): TableRenderInputs {
            val freePlay = FreePlayRenderFacts(
                seatScores = freePlayHost.session.scores.runningTotals(),
            )
            val online = onlineCoordinator.onlineHost?.let {
                OnlineRenderFacts(
                    seatScores = it.scores.runningTotals(),
                    connected = it.connectionStatus == ConnectionStatus.Connected,
                )
            }
            val chrome = TableChrome(
                interact = tutorial.table.interact,
                highlightLiveWall = tutorial.table.highlightLiveWall,
                overlayLeaderActive = tutorial.table.overlayLeaderActive,
                revealedSeats = tutorial.table.revealedSeats,
                boardHighlight = tutorial.table.boardHighlight,
                apply = when (session.kind) {
                    SessionKind.Tutorial -> tutorial.table.apply
                    SessionKind.FreePlay -> freePlayTableApply()
                    SessionKind.Online -> onlineTableApply()
                    SessionKind.Menu -> TableApply.None
                },
            )
            return boardTableInputs(session, origin, freePlay, online, chrome)
        }

        val boardChrome = BoardChrome { canvas, c, snapshot, boardDrawn ->
            // A board only adorns itself: the felt and its version stamp are the
            // runtime's fundamental overlay, which a board supersedes.
            if (!boardDrawn) return@BoardChrome
            when (snapshot.kind) {
                // No board is registered for the menu context; nothing to adorn.
                SessionKind.Menu -> Unit

                SessionKind.FreePlay, SessionKind.Online -> {
                    val felt = table.feltChrome(c.worldWidth, c.worldHeight)
                    freePlayPresenter.renderHandStatusBadge(
                        batch = canvas.batch,
                        screenH = c.worldHeight,
                        rightBandInnerX = felt.rightBandInnerX,
                        sessionKind = snapshot.kind,
                        boardHosted = true,
                        labelScale = felt.labelScale,
                        playHost = sessionRegistry.playHost,
                        latencyMs = if (snapshot.kind == SessionKind.Online) {
                            onlineCoordinator.presenceClient.freshRttMs(TimeUtils.millis())
                        } else {
                            null
                        },
                    )
                }

                SessionKind.Tutorial -> Unit
            }
        }
        val gameMenuRenderInput = { route: Route ->
            val menuKind = sessionRegistry.kind
            val boardUp = menuKind == SessionKind.FreePlay && sessionRegistry.liveHand.active
            val onlineTableUp = menuKind == SessionKind.Online && onlineCoordinator.onlineHost != null
            val flags = gameMenuFlags(boardUp, onlineTableUp, sessionRegistry.playHost.matchPhase)
            GameMenuRenderInput(
                context = MenuContext.from(menuKind),
                showScoreTable = flags.showScoreTable,
                showForfeit = flags.showForfeit,
                showContinue = menuKind == SessionKind.FreePlay && freePlayHost.session.active,
                // The floor is the only sign-in entry: the root menu keeps the
                // badge, while every child surface and the chrome card over a
                // running context have no account surface at all.
                showIdentity = route.idOrNull == RouteIds.ROOT_MENU,
                signedInLabel = accountController.signedInLabel,
                eloRating = accountController.eloRating,
                // The badge reports the Google sign-in flow, because nothing else
                // can: in flight it must say so, a failure must be visible where
                // the attempt started, and a build without Google disables only
                // the chooser's Google row — the special key stays available.
                signIn = when {
                    accountController.lifecycle == AuthLifecycle.SigningIn -> MenuSignIn.Pending
                    !accountController.googleSignInAvailable -> MenuSignIn.Unavailable
                    accountController.lastAuthError != null -> MenuSignIn.Failed
                    else -> MenuSignIn.Ready
                },
                showExit = GameMenuOverlay.canExit(),
                showNotices = route.idOrNull == RouteIds.ROOT_MENU && accountController.signedInLabel != null,
                hasUnseenNotices = onlineCoordinator.noticeList.notices.any { !it.seen },
            )
        }
        val matchHistoryRenderInput = {
            MatchHistoryRenderInput(
                matches = onlineCoordinator.matchHistory.all,
                loading = onlineCoordinator.matchHistory.allLoading,
                error = onlineCoordinator.matchHistory.allError,
                hasMore = onlineCoordinator.matchHistory.hasMore,
                nowMs = TimeUtils.millis(),
            )
        }
        val leaderboardRenderInput = {
            LeaderboardRenderInput(
                reading = leaderboardClient.reading,
                loading = leaderboardClient.loading,
                error = leaderboardClient.error,
            )
        }
        val shadowRankedAttemptsRenderInput = {
            ShadowRankedAttemptsRenderInput(
                attempts = shadowRankedAttempts.entries,
                loading = shadowRankedAttempts.loading,
                loadingMore = shadowRankedAttempts.loadingMore,
                error = shadowRankedAttempts.error,
                loadMoreError = shadowRankedAttempts.loadMoreError,
                hasMore = shadowRankedAttempts.hasMore,
                nowMs = TimeUtils.millis(),
            )
        }
        val shadowRankedHandsRenderInput = {
            ShadowRankedHandsRenderInput(
                document = shadowRankedHands.document,
                selectedIndex = shadowRankedHands.selectedIndex,
                dropdownOpen = shadowRankedHands.dropdownOpen,
                loading = shadowRankedHands.loading,
                failed = shadowRankedHands.error != null,
                errorCode = shadowRankedHands.errorCode,
            )
        }
        val noticesRenderInput = {
            NoticesRenderInput(
                notices = onlineCoordinator.noticeList.notices,
                loading = onlineCoordinator.noticeList.loading,
                error = onlineCoordinator.noticeList.error,
                nowMs = TimeUtils.millis(),
            )
        }
        val noticePopupRenderInput = {
            NoticesRenderInput(
                notices = listOfNotNull(onlineCoordinator.noticeList.popupNotice),
                nowMs = TimeUtils.millis(),
            )
        }
        // The Live tab's projection is the presence snapshot plus the persisted
        // `online.table` reservation the body reads to offer Rejoin.
        val onlineHubView = {
            val nowMs = TimeUtils.millis()
            val snap = onlineCoordinator.presenceClient.snapshot
            val waiting = onlineCoordinator.presenceClient.waitingEnabled
            val seatedAt = snap?.self?.table
            val elapsedSec = onlineCoordinator.presenceClient.searchStartedAtMs?.let { start ->
                ((nowMs - start).coerceAtLeast(0L) / 1000L).toInt()
            }
            OnlineHubView(
                storedTableId = state.onlineTableId.requireValue().takeIf { it.isNotEmpty() },

                signedInLabel = accountController.signedInLabel,
                eloRating = accountController.eloRating,
                searching = waiting && onlineCoordinator.onlineHost == null && seatedAt == null,
                searchElapsedSec = elapsedSec,
                searchInterrupted = waiting && onlineCoordinator.presenceClient.isStale(nowMs),
                seatedAt = seatedAt,
                reserved = waiting && seatedAt != null,
                queue = snap?.self?.queue,
                minOtherHumans = onlineCoordinator.presenceClient.minOtherHumans,
                rounds = onlineCoordinator.presenceClient.queueRounds,
                chicken = onlineCoordinator.presenceClient.queueChicken,
                snapshot = snap,
                stale = onlineCoordinator.presenceClient.isStale(nowMs),
                latencyMs = onlineCoordinator.presenceClient.freshRttMs(nowMs),
                prefsFrozen = waiting || seatedAt != null,
                nowMs = nowMs,
                notice = homeNoticeText(),
            )
        }
        val shadowRankedView = {
            ShadowRankedView(
                signedIn = accountController.isSignedIn,
                start = shadowRankedStarter.snapshot(),
                pool = shadowRankedPool.counts(account = accountController.user?.id),
            )
        }

        GameFeatures.install(
            featureRegistry,
            FeatureDeps(
                table = { table },
                scoreTable = {
                    onlineCoordinator.onlineHost?.scores ?: freePlayHost.session.scores
                },
                humanSeat = { sessionRegistry.playHost.humanSeat },
                camera = { camera },
                boardChrome = { boardChrome },
                hintButtonOverlay = { hintButtonOverlay },
                gameMenuOverlay = { gameMenu },
                gameMenuRenderInput = gameMenuRenderInput,
                confirmOverlay = { confirm },
                languagePickerOverlay = { languagePicker },
                settingsOverlay = { settingsOverlay },
                uiPreferences = state.ui,
                settingsNotifications = {
                    val client = onlineCoordinator.notificationPrefs
                    SettingsNotifications(
                        visible = accountController.isSignedIn,
                        emailOptIn = client.prefs.emailOptIn,
                        digestIntervalHours = client.prefs.digestIntervalHours,
                    )
                },
                newGameSetupOverlay = { newGameSetup },
                newGameConfig = { freePlayHost.setupDraft },
                scoreTableOverlay = { scoreOverlay },
                matchHistoryOverlay = { matchHistory },
                matchHistoryRenderInput = matchHistoryRenderInput,
                leaderboardOverlay = { leaderboard },
                leaderboardRenderInput = leaderboardRenderInput,
                ratingHistoryOverlay = { ratingHistory },
                ratingHistory = { ratingHistoryClient },
                shadowRankedAttemptsOverlay = { shadowRankedAttemptsOverlay },
                shadowRankedAttemptsRenderInput = shadowRankedAttemptsRenderInput,
                shadowRankedHandsOverlay = { shadowRankedHandsOverlay },
                shadowRankedHandsRenderInput = shadowRankedHandsRenderInput,
                noticesOverlay = { notices },
                noticesRenderInput = noticesRenderInput,
                noticePopupOverlay = { noticePopup },
                noticePopupRenderInput = noticePopupRenderInput,
                awayOverlay = { awayOverlay },
                scoreBreakdownOverlay = { scoreBreakdownOverlay },
                onlineHubOverlay = { onlineHub },
                onlineHubView = onlineHubView,
                shadowRankedView = shadowRankedView,
                onlineConnectOverlay = { onlineConnect },
                onlineConnectStatus = {
                    // The online model, not the registry's local fallback host: with
                    // no online host attached the card is not yet adopted and must
                    // read as connecting, not as a rejoin the local host invented.
                    val host = onlineCoordinator.onlineHost
                    OnlineConnectOverlay.statusFor(
                        connectionStatus = host?.connectionStatus,
                        failure = host?.connectionFailure,
                    )
                },
                onlineConnectLatencyMs = {
                    onlineCoordinator.presenceClient.freshRttMs(TimeUtils.millis())
                },
                onlineConnectSnapshot = { onlineCoordinator.presenceClient.snapshot },
                aliasCardOverlay = { aliasCardOverlay },
                startingLevelOverlay = { startingLevelOverlay },
                accountOverlay = { accountOverlay },
                accountDisplayName = { accountController.signedInLabel.orEmpty() },
                referenceBrowserOverlay = { referenceBrowser },
                referenceCanGoBack = { navigationEffects.canGoBack() },
                tutorialCoachOverlay = { tutorialCoach },
                tutorialQuizOverlay = { tutorialQuiz },
                tutorialShell = { tutorialShell },
                routeUiState = routeUiState,
            ),
        )

        val router = Router(
            initial = Route.Screen(RouteIds.ROOT_MENU),
            components = RegistryComponentFactory(featureRegistry as ComponentRegistry),
            specs = RegistrySpecProvider(featureRegistry as ComponentRegistry),
            scope = scope,
            onEntryRemoved = { routeUiState.remove(it) },
        )
        routerHolder[0] = router
        // Cold-start replay, step 1: the saved stack over the default floor. The
        // stack is the sole authority for what is up, so an unusable snapshot
        // falls back to the default. [restoredStack] is what step 2 reads to
        // decide whether it may touch the stack at all.
        val incomingStack = aliasPrompt.restoredStack(boot.stack)
        val restoredStack = incomingStack.entries.isNotEmpty() && router.restore(incomingStack)
        if (!restoredStack && boot.stack.entries.isNotEmpty()) {
            // The snapshot names something this client no longer serves (an old
            // route, a corrupt entry). Start at the root and drop it, so the next
            // launch is clean instead of replaying a stack that can never build.
            input.logLifecycle("stack restore rejected; starting at the root and clearing it")
            state.stack.clear()
        }
        if (restoredStack) {
            // The incoming stack's state text is the store's content, resolved
            // through each route's declared codec. Presenters read lazily, so
            // seeding after the router rebuild is safe.
            for (entry in incomingStack.entries) {
                val text = entry.componentState ?: continue
                val route = entry.toRoute() ?: continue
                val codec = RouteSpecs.specFor(route).state ?: continue
                routeUiState.put(RouteKey(entry.routeKey), RouteUiValue(codec.type, text))
            }
        }
        // The cold start is a run of stack mutations, and a layer that goes missing
        // between them is invisible without this: name the stack at each step, with
        // the args that carry a layer's content and whether the entry persists.
        fun logStack(tag: String) {
            val entries = router.stack.entries.joinToString(", ") { entry ->
                val args = entry.route.args.entries.joinToString(",") { "${it.key}=${it.value}" }
                val name = entry.route.key.value + if (args.isEmpty()) "" else "[$args]"
                if (entry.spec.policy.persists) name else "$name (transient)"
            }
            input.logLifecycle("$tag [$entries]")
        }
        logStack("coldStart:restored")

        val routes = RouteController(router)
        transitions = ScreenTransitions(routes = routes)
        presentOnline = { onlineEffects.hubOpen(null) }
        dismissOnline = { transitions.dismissHub() }

        // Effects. One class per concern, each taking only what it needs, so the
        // composition root states the dependency per domain instead of handing
        // every domain the whole graph (the old AppEffectPorts). The forward
        // seams (`exitTutorial`, the two online-home callbacks) are assigned once
        // the runtime object they need exists.
        sessionEffects = AppSessionEffects(
            table = table,
            freePlayHost = freePlayHost,
            freePlayPresenter = freePlayPresenter,
            sessionRegistry = sessionRegistry,
            onlineCoordinator = onlineCoordinator,
            routes = routes,
            transitions = transitions,
            refreshAccount = { accountController.refreshAccount() },
            logout = {
                onlineCoordinator.leaveOnlineTable()
                accountController.logOut()
                transitions.dismissHub()
                sessionEffects.pushGameMenu()
            },
            restartTutorialLesson = { tutorialHost.restart() },
            openOnlineHub = { onlineEffects.hubOpen(null) },
        )
        navigationEffects = AppNavigationEffects(
            transitions = transitions,
            // A browser command resolves a place; the store is its one owner, so
            // the effect writes it here instead of leaving it on the shared widget.
            // The write mirrors into `ui.remembered` when the route remembers its
            // state, so a deep-linked page survives the entry closing, exactly as
            // the browser's own emitted writes do (see `RouteSpecs`).
            writeNavigation = { nav ->
                val reference = Route.Reference(StackState.EMPTY)
                routeUiState.put(
                    reference.key,
                    RouteUiValue(ReferenceNavCodec.type, ReferenceNavCodec.encode(nav)),
                    remember = RouteSpecs.specFor(reference).remembersState,
                )
            },
            readNavigation = {
                routeUiState.read(Route.Reference(StackState.EMPTY).key, ReferenceNavCodec)
            },
        )
        chromeEffects = AppChromeEffects(
            sessionRegistry = sessionRegistry,
            routes = routes,
            toggleFullscreen = onToggleFullscreen,
            exitApp = onExitRequest,
        )
        prefsEffects = AppPrefsEffects(
            preferences = state.ui,
            transitions = transitions,
            routeUi = routeUiState,
            soundPlayer = soundPlayer,
        )
        val tutorialEffects = AppTutorialEffects(
            tutorialHost = tutorialHost,
            sessionRegistry = sessionRegistry,
        )
        val accountEffects = AppAccountEffects(
            accountController = accountController,
            transitions = transitions,
        )
        onlineEffects = AppOnlineEffects(
            onlineCoordinator = onlineCoordinator,
            accountController = accountController,
            sessionRegistry = sessionRegistry,
            routes = routes,
            transitions = transitions,
            routeUi = routeUiState,
            shadowRankedStarter = shadowRankedStarter,
            shadowRankedPool = shadowRankedPool,
            shadowRankedAttempts = shadowRankedAttempts,
            shadowRankedHands = shadowRankedHands,
            leaderboardClient = leaderboardClient,
            ratingHistoryClient = ratingHistoryClient,
        )
        // A refresh that found a fresh notice lets the effect present it.
        sessionEffects.exitTutorial = { tutorialHost.exit() }

        // Cold-start replay, step 2: the context layers, topmost first. A context
        // whose session is gone is **popped**, which takes the chrome above it with
        // it and reveals its parent — a table with no seat lands on the hub, a
        // local board with no save on the local play menu, a shell with no lesson on
        // the main menu, never on nothing. Only the layers the stack names are
        // probed, so a local save is never consumed while restoring an online table
        // (and vice versa).
        val onlineTableKey = Route.Layer(RouteIds.ONLINE_TABLE).key
        // The online seat is the one context the boot cannot adopt, and it must not be
        // mistaken for one whose session is gone: the access token is memory-only, so at
        // this step the process has nothing to present to a table endpoint and cannot
        // ask. A restored credential keeps the layer — presence adopts the seat once the
        // refresh lands ([OnlineCoordinator.reconcilePresence] joins an `Online` stack,
        // and a snapshot with no seat pops it) — and only a stack with no credential at
        // all loses it here, because then no session is coming.
        if (router.stack.contains(onlineTableKey) && !onlineCoordinator.canRestoreOnlineTable()) {
            router.remove(onlineTableKey)
            input.logLifecycle("no restorable session; online table popped to the hub")
        }
        val localBoardKey = Route.Layer(RouteIds.LOCAL_BOARD).key
        if (router.stack.contains(localBoardKey) && !freePlayHost.tryRestore(boot.session)) {
            router.remove(localBoardKey)
            input.logLifecycle("local board save is gone; popped to the local play menu")
        }
        // The shell's session is the live lesson, and restoring it hands the host its
        // game and binds the lesson layer inside the shell. A shell whose lesson did
        // not come back goes, so the main menu is what is left rather than a shell
        // with nothing in it. Neither probe consumes the other's key: a lesson keeps
        // a parked local save on disk for the local menu's next entry, and a local
        // game keeps the mid-lesson save for Tutorial's.
        val tutorialShellKey = Route.Layer(RouteIds.TUTORIAL_SHELL).key
        if (router.stack.contains(tutorialShellKey) &&
            !tutorialHost.tryRestore(boot.tutorialSession)
        ) {
            router.remove(tutorialShellKey)
            input.logLifecycle("tutorial lesson is gone; popped to the main menu")
        }
        logStack("coldStart:probed")
        // Step 3: a restore that landed is left exactly as it came back — the stack is
        // the answer to "where was I". Every surface whose content comes from the
        // server and is not persisted loads from its own appearance rather than from
        // here: the seam observes each key appearing, so installing it after the
        // replay loads a restored surface and the observer loads a pushed one.
        // Loading on the tap alone is the defect this replaces — a restored card
        // would come up showing nothing until the player reopened it. The lesson
        // needs no equivalent: `tryRestore` above is what fills it.
        RouteSurfaceLoad(
            router = router,
            key = Route.Layer(RouteIds.ONLINE_HUB).key,
            enter = { onlineEffects.hubEnter() },
        )
        RouteSurfaceLoad(
            router = router,
            key = Route.Modal(RouteIds.SHADOW_RANKED_HANDS).key,
            enter = { onlineEffects.shadowRankedHandsEntered() },
        )
        RouteSurfaceLoad(
            router = router,
            key = Route.Modal(RouteIds.LEADERBOARD).key,
            enter = { onlineEffects.leaderboardEnter() },
        )
        RouteSurfaceLoad(
            router = router,
            key = Route.Modal(RouteIds.MATCH_HISTORY).key,
            enter = { onlineEffects.matchHistoryEnter() },
        )
        RouteSurfaceLoad(
            router = router,
            key = Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS).key,
            enter = { onlineEffects.shadowRankedAttemptsEnter() },
        )
        RouteSurfaceLoad(
            router = router,
            key = Route.Modal(RouteIds.RATING_HISTORY).key,
            enter = { onlineEffects.ratingHistoryEnter() },
        )
        val dispatcher = AppDispatcher(
            navigation = navigationEffects,
            chrome = chromeEffects,
            prefs = prefsEffects,
            session = sessionEffects,
            tutorial = tutorialEffects,
            online = onlineEffects,
            account = accountEffects,
            sessionKind = { sessionRegistry.kind },
            stack = { router.stack },
            backOf = { route -> featureRegistry.back(route) },
            log = { input.logLifecycle(it) },
        )
        dispatcherHolder[0] = dispatcher

        // Step 4: the account surfaces, last. Presence starts for the restored
        // credential, and a pending account-setup prompt may be raised, but the
        // account never moves the context the steps above decided (see
        // AccountController.restoreSession). After the dispatcher so a native
        // display-name dialog can emit.
        accountController.restoreSession()
        logStack("coldStart:ready")

        val presenceScheduler = PresenceScheduler.create()
        val componentHost = ComponentHost(
            router = router,
            registry = featureRegistry,
            ui = GdxUiEnvironment(densityScale = Fonts.designPxScale()),
            session = { sessionRegistry.snapshot() },
            intents = dispatcher,
            model = { snapshot, origin ->
                val tutorial = tutorialHost.snapshot()
                AppModel(
                    session = snapshot,
                    tutorial = tutorial,
                    tableInputs = boardInputs(snapshot, origin, tutorial),
                    boardRender = boardRenderInput(),
                    scoreBreakdown = when (snapshot.kind) {
                        SessionKind.Online -> snapshot.table?.let {
                            onlineSettlementPresenter.paintContent(it)
                        }
                        SessionKind.FreePlay, SessionKind.Tutorial ->
                            snapshot.hand?.let { freePlayPresenter.buildPresentation(it) }
                        SessionKind.Menu -> null
                    },
                )
            },
            log = { input.logLifecycle(it) },
            // The one-shot wake a settled surface schedules for its deadline. The
            // scheduler posts to the app thread (setTimeout on the web, a daemon
            // executor on desktop); requestRendering is then the present.
            requestFrameAfter = { delayMs ->
                presenceScheduler.scheduleAfter(delayMs) {
                    Gdx.graphics?.requestRendering()
                }
            },
        )
        val normalizer = InputNormalizer()

        val bootMs = (System.nanoTime() - tBoot) / 1_000_000L
        BootDiagnostics.mark(
            "bootstrapApp:done",
            "elapsedMs=$bootMs kind=${sessionRegistry.kind} " +
                "freePlay=${freePlayHost.session.active} tutorial=${tutorialHost.runner?.scenario?.id}",
        )
        input.logLifecycle(
            "create version=${AppVersion.LABEL} (${AppVersion.CODE}) profile=$inputProfile " +
                "locale=${LocaleController.current.tag} screen=${Gdx.graphics.width}x${Gdx.graphics.height} " +
                "kind=${sessionRegistry.kind} bootMs=$bootMs",
        )

        val runtime = AppRuntime(
            scope = scope,
            camera = camera,
            viewport = viewport,
            input = input,
            table = table,
            freePlayHost = freePlayHost,
            freePlayPresenter = freePlayPresenter,
            tutorialHost = tutorialHost,
            onlineCoordinator = onlineCoordinator,
            onlineSettlementPresenter = onlineSettlementPresenter,
            accountController = accountController,
            batch = batch,
            onExit = onExit,
            router = router,
            componentHost = componentHost,
            dispatcher = dispatcher,
            rawInput = rawInput,
            normalizer = normalizer,
            inputProfile = inputProfile,
            sessionRegistry = sessionRegistry,
            stateStore = stateStore,
            stack = state.stack,
            routeUiState = routeUiState,
            soundPlayer = soundPlayer,
            presenceScheduler = presenceScheduler,
            pollNotices = { onlineEffects.pollNotices() },
            startupIntent = startupIntent,
        )
        runtime.requestFontRebuild()
        // No flush here: [AppRuntime] flushes on pause/exit/dispose, which is the
        // runtime's one flush site. Boot-time writes are the imports' (each
        // flushed against its own source) and the restored session's, which the
        // backend holds until that edge.
        return runtime
    }

}

/**
 * The one startup intent a play-page deep link asks for, or null.
 *
 * The mail link's `#my_challenges` fragment now lands on the Online hub's Shadow
 * Ranked tab. The fragment is the launch input; this is
 * the composition root's one translation of it into an [Intent], so a desktop or
 * phone launch with no fragment is unchanged.
 */
internal fun deepLinkStartupIntent(target: DeepLink.Target?): Intent? = when (target) {
    DeepLink.Target.MY_CHALLENGES -> Intent.OpenOnline(OnlineTab.ShadowRanked)
    null -> null
}
