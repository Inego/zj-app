package inego.zungjunggame.runtime

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.features.board.BoardChrome
import inego.zungjunggame.features.board.BoardPane
import inego.zungjunggame.features.board.HintButtonRenderInput
import inego.zungjunggame.features.challenges.ShadowRankedAttemptsOverlay
import inego.zungjunggame.features.challenges.ShadowRankedAttemptsRenderInput
import inego.zungjunggame.features.challenges.ShadowRankedHandsOverlay
import inego.zungjunggame.features.challenges.ShadowRankedHandsRenderInput
import inego.zungjunggame.features.history.MatchHistoryRenderInput
import inego.zungjunggame.features.leaderboard.LeaderboardOverlay
import inego.zungjunggame.features.leaderboard.LeaderboardRenderInput
import inego.zungjunggame.features.menu.GameMenuRenderInput
import inego.zungjunggame.features.notices.NoticesOverlay
import inego.zungjunggame.features.notices.NoticesRenderInput
import inego.zungjunggame.features.online.hub.OnlineHubOverlay
import inego.zungjunggame.features.online.hub.OnlineHubView
import inego.zungjunggame.features.online.hub.ShadowRankedView
import inego.zungjunggame.features.ratinghistory.RatingHistoryOverlay
import inego.zungjunggame.features.ratinghistory.defaultRatingHistory
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.features.away.AwayOverlay
import inego.zungjunggame.features.confirm.ConfirmOverlay
import inego.zungjunggame.features.auth.AccountOverlay
import inego.zungjunggame.features.auth.AliasCardOverlay
import inego.zungjunggame.features.menu.GameMenuOverlay
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.features.settings.LanguagePickerOverlay
import inego.zungjunggame.features.history.MatchHistoryOverlay
import inego.zungjunggame.features.local.NewGameSetupOverlay
import inego.zungjunggame.features.online.OnlineConnectOverlay
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.features.reference.ReferenceBrowserOverlay
import inego.zungjunggame.features.score.ScoreBreakdownOverlay
import inego.zungjunggame.features.score.ScoreTableOverlay
import inego.zungjunggame.features.settings.SettingsNotifications
import inego.zungjunggame.features.settings.SettingsOverlay
import inego.zungjunggame.features.auth.StartingLevelOverlay
import inego.zungjunggame.features.tutorial.TutorialCoachOverlay
import inego.zungjunggame.features.tutorial.TutorialQuizOverlay
import inego.zungjunggame.features.tutorial.TutorialShell
import inego.zungjunggame.render.ui.UiChip
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot

/**
 * Host-owned instances and provider seams the feature components need.
 *
 * Every entry is a provider so assembly and headless tests construct no
 * GL-backed object until a component actually consumes it; the defaults mirror
 * the components' own defaults. Supply real instances from the composition root.
 */
class FeatureDeps(
    val table: () -> PlayTableView = { PlayTableView() },
    val scoreTable: () -> SessionScoreTable = { SessionScoreTable() },
    val humanSeat: () -> Int = { 0 },
    val camera: () -> Camera = { OrthographicCamera() },
    val boardChrome: () -> BoardChrome = { BoardChrome { _, _, _, _ -> } },

    val gameMenuOverlay: () -> GameMenuOverlay = { GameMenuOverlay() },
    /** Per-entry card input: the route says which of the card's three homes is asking. */
    val gameMenuRenderInput: (Route) -> GameMenuRenderInput = { GameMenuRenderInput() },

    val confirmOverlay: () -> ConfirmOverlay = { ConfirmOverlay() },

    val languagePickerOverlay: () -> LanguagePickerOverlay = { LanguagePickerOverlay() },

    val settingsOverlay: () -> SettingsOverlay = { SettingsOverlay() },
    /**
     * The `ui.prefs` slot: the display model the Settings checkbox and the hand-end
     * card's parked place live in, read by their components and written by the
     * prefs effect. The default is an in-memory slot so a build that never wires a
     * store still has working cards; production passes the real key.
     */
    val uiPreferences: StateSlot<UiPreferences> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey),
    /**
     * The account's notification settings the Settings card's notifications section
     * draws. Empty while signed out, so the section is hidden.
     */
    val settingsNotifications: () -> SettingsNotifications = { SettingsNotifications() },
    /**
     * The route-keyed store of each route's player-set state. The default is an
     * in-memory store, so a headless assembly still wires working scroll
     * surfaces; production passes the one the runtime snapshots.
     */
    val routeUiState: RouteUiState = RouteUiState(),

    val newGameSetupOverlay: () -> NewGameSetupOverlay = { NewGameSetupOverlay() },
    val newGameConfig: () -> GameConfig = { GameConfig.DEFAULT },

    val scoreTableOverlay: () -> ScoreTableOverlay = { ScoreTableOverlay() },

    val matchHistoryOverlay: () -> MatchHistoryOverlay = { MatchHistoryOverlay() },
    val matchHistoryRenderInput: () -> MatchHistoryRenderInput = { MatchHistoryRenderInput() },

    val leaderboardOverlay: () -> LeaderboardOverlay = { LeaderboardOverlay() },
    val leaderboardRenderInput: () -> LeaderboardRenderInput = { LeaderboardRenderInput() },

    /** The rating-history chart's shell and its plot. */
    val ratingHistoryOverlay: () -> RatingHistoryOverlay = { RatingHistoryOverlay() },
    /**
     * The rating-history chart's tile owner: the byte-bounded retained coverage
     * plus the request scheduler E3 drives from the frame. The default is one owner
     * for an assembly that wires nothing, so a headless build still has a working
     * chart; production passes the instance the app scope owns.
     */
    val ratingHistory: () -> RatingHistoryClient = defaultRatingHistory(),

    val shadowRankedAttemptsOverlay: () -> ShadowRankedAttemptsOverlay = { ShadowRankedAttemptsOverlay() },
    val shadowRankedAttemptsRenderInput: () -> ShadowRankedAttemptsRenderInput = {
        ShadowRankedAttemptsRenderInput()
    },

    val shadowRankedHandsOverlay: () -> ShadowRankedHandsOverlay = { ShadowRankedHandsOverlay() },
    val shadowRankedHandsRenderInput: () -> ShadowRankedHandsRenderInput = {
        ShadowRankedHandsRenderInput()
    },

    val noticesOverlay: () -> NoticesOverlay = { NoticesOverlay() },
    val noticesRenderInput: () -> NoticesRenderInput = { NoticesRenderInput() },
    val noticePopupOverlay: () -> NoticesOverlay = { NoticesOverlay(compact = true) },
    val noticePopupRenderInput: () -> NoticesRenderInput = { NoticesRenderInput() },

    val awayOverlay: () -> AwayOverlay = { AwayOverlay() },

    val hintButtonOverlay: () -> HintButtonOverlay = { HintButtonOverlay(UiChip()) },
    val hintButtonRenderInput: () -> HintButtonRenderInput = { HintButtonRenderInput() },

    val scoreBreakdownOverlay: () -> ScoreBreakdownOverlay = { ScoreBreakdownOverlay() },

    /** The unified Online hub's shell and its host-built Live / Shadow views. */
    val onlineHubOverlay: () -> OnlineHubOverlay = { OnlineHubOverlay() },
    val onlineHubView: () -> OnlineHubView = { OnlineHubView() },
    val shadowRankedView: () -> ShadowRankedView = { ShadowRankedView() },

    val onlineConnectOverlay: () -> OnlineConnectOverlay = { OnlineConnectOverlay() },
    val onlineConnectStatus: () -> OnlineConnectOverlay.Status = {
        OnlineConnectOverlay.Status.Connecting
    },
    val onlineConnectLatencyMs: () -> Int? = { null },
    val onlineConnectSnapshot: () -> PresenceSnapshot? = { null },

    val aliasCardOverlay: () -> AliasCardOverlay = { AliasCardOverlay() },

    val accountOverlay: () -> AccountOverlay = { AccountOverlay() },
    /** The signed-in label the Account panel shows; empty while signed out. */
    val accountDisplayName: () -> String = { "" },

    val startingLevelOverlay: () -> StartingLevelOverlay = { StartingLevelOverlay() },

    val referenceBrowserOverlay: () -> ReferenceBrowserOverlay = { ReferenceBrowserOverlay() },
    val referenceCanGoBack: () -> Boolean = { false },

    val tutorialCoachOverlay: () -> TutorialCoachOverlay = { TutorialCoachOverlay() },
    /**
     * The pane a tutorial board lesson leaves the live table, derived from the
     * coach's campaign-wide copy band for the frame's own content space. Lazy,
     * like every other widget here: a headless assembly never builds it.
     */
    val tutorialBoardPane: BoardPane = BoardPane { width, height ->
        tutorialCoachOverlay().boardAreaFor(width, height)
    },
    val tutorialQuizOverlay: () -> TutorialQuizOverlay = { TutorialQuizOverlay() },
    val tutorialShell: () -> TutorialShell = { TutorialShell() },
)
