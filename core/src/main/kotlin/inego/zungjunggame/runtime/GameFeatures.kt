package inego.zungjunggame.runtime

import inego.zungjunggame.features.auth.AccountFeature
import inego.zungjunggame.features.auth.AliasCardFeature
import inego.zungjunggame.features.auth.StartingLevelFeature
import inego.zungjunggame.features.away.AwayFeature
import inego.zungjunggame.features.board.BoardFeature
import inego.zungjunggame.features.board.HintButtonFeature
import inego.zungjunggame.features.challenges.ShadowRankedAttemptsFeature
import inego.zungjunggame.features.challenges.ShadowRankedHandsFeature
import inego.zungjunggame.features.confirm.ConfirmFeature
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.features.history.MatchHistoryFeature
import inego.zungjunggame.features.leaderboard.LeaderboardFeature
import inego.zungjunggame.features.local.NewGameSetupFeature
import inego.zungjunggame.features.menu.GameMenuFeature
import inego.zungjunggame.features.notices.NoticesFeature
import inego.zungjunggame.features.online.OnlineConnectFeature
import inego.zungjunggame.features.online.hub.OnlineHubFeature
import inego.zungjunggame.features.ratinghistory.RatingHistoryFeature
import inego.zungjunggame.features.reference.ReferenceFeature
import inego.zungjunggame.features.score.ScoreBreakdownFeature
import inego.zungjunggame.features.score.ScoreTableFeature
import inego.zungjunggame.features.settings.LanguagePickerFeature
import inego.zungjunggame.features.settings.SettingsFeature
import inego.zungjunggame.features.tutorial.TutorialCoachFeature
import inego.zungjunggame.features.tutorial.TutorialQuizFeature
import inego.zungjunggame.features.tutorial.TutorialShellFeature
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.StackState

/**
 * The single assembly point for every feature route.
 *
 * [install] wires each feature's `register(...)` from one [FeatureDeps] and
 * fails fast when [RouteIds.all] holds an id that no feature registered.
 */
object GameFeatures {
    /** Every route [install] wires, in registration order. */
    val FEATURE_ROUTES: List<Route> = listOf(
        Route.Screen(RouteIds.ROOT_MENU),
        Route.Layer(RouteIds.LOCAL_MENU),
        Route.Layer(RouteIds.LOCAL_BOARD),
        // The confirm's identity includes the question it asks, so its instance here
        // names one: `modal:confirm` is what is registered, and every instance of it
        // must carry a question (see [ConfirmPrompt]).
        ConfirmPrompt.AbandonHand.route(),
        Route.Layer(RouteIds.ONLINE_TABLE),
        Route.Overlay(RouteIds.TUTORIAL_LESSON),
        Route.Modal(RouteIds.GAME_MENU),
        Route.Modal(RouteIds.LANGUAGE_PICKER),
        Route.Modal(RouteIds.SETTINGS),
        Route.Modal(RouteIds.ACCOUNT),
        Route.Modal(RouteIds.NEW_GAME_SETUP),
        Route.Modal(RouteIds.SCORE_TABLE),
        Route.Modal(RouteIds.MATCH_HISTORY),
        Route.Modal(RouteIds.LEADERBOARD),
        Route.Modal(RouteIds.RATING_HISTORY),
        Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS),
        Route.Modal(RouteIds.SHADOW_RANKED_HANDS),
        Route.Modal(RouteIds.NOTICES),
        Route.Modal(RouteIds.NOTICE_POPUP),
        Route.Overlay(RouteIds.AWAY),
        Route.Overlay(RouteIds.HINT_BUTTON),
        Route.Modal(RouteIds.FIRST_LOGIN_ALIAS),
        Route.Modal(RouteIds.CHANGE_DISPLAY_NAME),
        Route.Modal(RouteIds.SPECIAL_KEY),
        Route.Modal(RouteIds.STARTING_LEVEL),
        Route.Layer(RouteIds.ONLINE_HUB),
        Route.Modal(RouteIds.ONLINE_CONNECT),
        Route.Reference(StackState.EMPTY),
        Route.Overlay(RouteIds.SCORE_BREAKDOWN),
        Route.Overlay(RouteIds.TUTORIAL_COACH),
        Route.Overlay(RouteIds.TUTORIAL_QUIZ),
        Route.Layer(RouteIds.TUTORIAL_SHELL),
    )

    /** The [RouteIds] catalog id a route instance corresponds to. */
    fun catalogIdOf(route: Route): String = RouteIds.catalogIdOf(route)

    /** Registers every feature route; throws if a catalog id has no feature. */
    fun install(registry: FeatureRegistry, deps: FeatureDeps) {
        ConfirmFeature.register(registry, deps.confirmOverlay)
        BoardFeature.register(
            registry,
            deps.table,
            deps.uiPreferences,
            deps.boardChrome,
            deps.tutorialBoardPane,
        )
        GameMenuFeature.register(
            registry,
            deps.gameMenuOverlay,
            deps.gameMenuRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        LanguagePickerFeature.register(
            registry,
            deps.languagePickerOverlay,
            deps.camera,
            deps.routeUiState,
        )
        SettingsFeature.register(
            registry,
            deps.settingsOverlay,
            deps.uiPreferences,
            deps.settingsNotifications,
            deps.camera,
            deps.routeUiState,
        )
        NewGameSetupFeature.register(
            registry,
            deps.newGameSetupOverlay,
            deps.newGameConfig,
            deps.camera,
            deps.routeUiState,
        )
        ScoreTableFeature.register(
            registry,
            deps.scoreTable,
            deps.humanSeat,
            deps.scoreTableOverlay,
            deps.camera,
            deps.routeUiState,
        )
        MatchHistoryFeature.register(
            registry,
            deps.matchHistoryOverlay,
            deps.matchHistoryRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        LeaderboardFeature.register(
            registry,
            deps.leaderboardOverlay,
            deps.leaderboardRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        RatingHistoryFeature.register(
            registry,
            deps.ratingHistoryOverlay,
            deps.ratingHistory,
            deps.camera,
            deps.routeUiState,
        )
        NoticesFeature.register(
            registry,
            deps.noticesOverlay,
            deps.noticesRenderInput,
            deps.noticePopupOverlay,
            deps.noticePopupRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        ShadowRankedAttemptsFeature.register(
            registry,
            deps.shadowRankedAttemptsOverlay,
            deps.shadowRankedAttemptsRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        ShadowRankedHandsFeature.register(
            registry,
            deps.shadowRankedHandsOverlay,
            deps.shadowRankedHandsRenderInput,
            deps.camera,
            deps.routeUiState,
        )
        AwayFeature.register(registry, deps.awayOverlay)
        HintButtonFeature.register(registry, deps.hintButtonOverlay, deps.hintButtonRenderInput)
        AliasCardFeature.register(registry, deps.aliasCardOverlay)
        StartingLevelFeature.register(registry, deps.startingLevelOverlay)
        AccountFeature.register(registry, deps.accountOverlay, deps.accountDisplayName)
        OnlineHubFeature.register(
            registry,
            deps.onlineHubOverlay,
            deps.onlineHubView,
            deps.shadowRankedView,
            deps.camera,
            deps.routeUiState,
        )
        OnlineConnectFeature.register(
            registry,
            deps.onlineConnectOverlay,
            deps.onlineConnectStatus,
            deps.onlineConnectLatencyMs,
            deps.onlineConnectSnapshot,
        )
        ReferenceFeature.register(
            registry,
            deps.referenceBrowserOverlay,
            deps.camera,
            deps.routeUiState,
            deps.referenceCanGoBack,
        )
        ScoreBreakdownFeature.register(
            registry,
            deps.scoreBreakdownOverlay,
            deps.uiPreferences,
            deps.camera,
        )
        TutorialCoachFeature.register(
            registry,
            deps.tutorialCoachOverlay,
        )
        TutorialQuizFeature.register(registry, deps.tutorialQuizOverlay)
        TutorialShellFeature.register(
            registry,
            deps.tutorialShell,
            deps.camera,
            deps.routeUiState,
        )

        for (route in FEATURE_ROUTES) registry.specFor(route)
        requireCatalogCovered()
    }

    private fun requireCatalogCovered() {
        val covered = FEATURE_ROUTES.map(::catalogIdOf)
        val duplicates = covered.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Route id both registered and excluded: $duplicates" }
        val missing = RouteIds.all.filterNot { it in covered }
        require(missing.isEmpty()) {
            "RouteIds with no feature: $missing"
        }
    }
}
