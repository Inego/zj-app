package inego.zungjunggame.input

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey

/** Unified action vocabulary emitted by input and consumed by feature handlers. */
sealed interface Intent {
    // Navigation / stack
    data object Back : Intent
    data class PushRoute(val route: Route) : Intent
    data class PopRoute(val key: RouteKey) : Intent

    /**
     * Leave the current context for the root main menu, discarding it: the game
     * menu's **Exit to main menu** row. A pop to the floor, never a screen move.
     */
    data object ExitToMainMenu : Intent

    // Navigation / chrome
    data object ShowMenu : Intent
    data object HideMenu : Intent

    /** Quit the app (menu Exit row); the runtime persists before exiting. */
    data object ExitApp : Intent

    // Board / session
    data class Discard(val tileType: Int, val visualId: Int = -1, val isDrawn: Boolean = false) : Intent
    data object DiscardDrawnTile : Intent
    data class ApplyChoice(val choice: Choice) : Intent
    data object Hint : Intent
    data object AiAssist : Intent
    data object NextHand : Intent
    data object NewGame : Intent
    data object PlayAgain : Intent

    /** Enter local free play from the root menu ("Play against bots"). */
    data object PlayAgainstBots : Intent

    /** Open the tutorial shell from the root menu. */
    data object OpenTutorial : Intent

    /** The player parked the hand-end card at ([x], [y]) from its centered rest pose. */
    data class ParkScoreBreakdown(val x: Float, val y: Float) : Intent

    /** The player parked the board's HINT button at ([x], [y]) from its rest pose. */
    data class ParkHintButton(val x: Float, val y: Float) : Intent

    /** The player parked the board's claim / action row at ([x], [y]) from its rest pose. */
    data class ParkClaimPanel(val x: Float, val y: Float) : Intent

    /**
     * The player pressed the hand-end card's fold control. The remembered fold is
     * display state (`ui.prefs`), so the card asks for the flip instead of doing it:
     * one writer, and the card renders what it is handed.
     */
    data object ToggleScoreBreakdownFold : Intent

    /**
     * The player left the hand-end card's scrollable body at [offset] pixels down
     * from the top. The card's route is announced, so its body scroll cannot ride
     * the stack snapshot; it is display state like the fold and the park, written
     * to `ui.prefs` by the prefs effect.
     */
    data class SetScoreBreakdownScroll(val offset: Float) : Intent

    // Online / account
    data object StartSearch : Intent
    data object CancelSearch : Intent
    data object LogIn : Intent

    /** Open the special-key field for the Google Play review account. */
    data object LogInSpecialKey : Intent

    data object LogOut : Intent
    data class SetMinOtherHumans(val value: Int) : Intent
    data object CycleRounds : Intent
    data object CycleChicken : Intent
    data object ViewMatchHistory : Intent

    /** Open the hub's Leaderboard card; the effect fetches the first page. */
    data object OpenLeaderboard : Intent

    /**
     * Fetch the window that starts at [offset] of the pinned leaderboard reading,
     * as the viewport reaches it. The effect stays on the reading's generation.
     */
    data class LoadLeaderboardWindow(val offset: Int) : Intent

    /**
     * Open the rating-history chart for [target]: the viewer's own history from
     * the hub or the root menu's account drawer, or a listed player's from a
     * tapped Leaderboard row. The target is the route's own state, so the effect
     * writes it before the push and the screen (and a restore of it) reads one
     * value.
     */
    data class OpenRatingHistory(val target: RatingHistoryTarget) : Intent

    /** Open the notices inbox (the hub entry). */
    data object ViewNotices : Intent
    data object ToggleWaiting : Intent

    /** Rejoin the reserved or running online table from the hub. */
    data object RejoinTable : Intent

    

    // Online hub (the unified Live / Shadow Ranked screen)
    /** Open the Online hub; [tab] overrides the remembered tab. */
    data class OpenOnline(val tab: OnlineTab? = null) : Intent

    /** The player chose a hub tab; stored as the hub route's own state. */
    data class SelectOnlineTab(val tab: OnlineTab) : Intent

    /** Leave the Online hub for the root menu. */
    data object LeaveOnlineHub : Intent

    /** Start a Shadow Ranked attempt from the hub's remembered draft. */
    data object StartShadowRanked : Intent

    /** Cancel an in-flight Shadow Ranked start. */
    data object CancelShadowRankedStart : Intent

    /** Retry a failed Shadow Ranked start with the remembered draft. */
    data object RetryShadowRankedStart : Intent

    /** Set the draft's rounds (1 or 2); stores it and refreshes the pool count. */
    data class SetShadowRankedRounds(val rounds: Int) : Intent

    /** Flip the draft's chicken toggle; stores it and refreshes the pool count. */
    data object ToggleShadowRankedChicken : Intent

    /** Flip Shadow Ranked's fast-bots toggle; stored on the hub without refreshing the pool. */
    data object ToggleShadowRankedFastBots : Intent

    /** Reload the signed-in account's Shadow Ranked attempts. */
    data object RefreshShadowRankedAttempts : Intent

    /** Open the signed-in account's Shadow Ranked attempts panel. */
    data object ViewShadowRankedAttempts : Intent

    /** Fetch the next page of Shadow Ranked attempts. */
    data object LoadMoreShadowRankedAttempts : Intent

    /** Close the Shadow Ranked attempts panel. */
    data object ShadowRankedAttemptsDismiss : Intent

    /** Open the hand tables of a finished challenge from an attempts row. */
    data class OpenShadowRankedHands(val publicId: String) : Intent

    /** Close the hand tables. */
    data object ShadowRankedHandsDismiss : Intent

    /** Move the Who selection by [delta] (`-1` left, `+1` right); wraps. */
    data class ShadowRankedHandsStep(val delta: Int) : Intent

    /** Open or close the Who dropdown. */
    data object ToggleShadowRankedHandsDropdown : Intent

    /** Choose the Who row at [index] from the open dropdown. */
    data class SelectShadowRankedHandsAttempt(val index: Int) : Intent

    // Online table connection card
    data object OnlineConnectCancel : Intent
    data object OnlineConnectRetry : Intent

    /**
     * Leave the online table and land on the hub — the online home. Raised by the
     * connect card and by the hand-end card's **Return home** chip.
     */
    data object OnlineReturnHome : Intent

    // Auth modals (first-login alias, starting level)
    data object FirstLoginSubmit : Intent
    data object FirstLoginBack : Intent

    /** Open the Account panel (name + Change name + Delete account). */
    data object OpenAccount : Intent

    /** Open the account display-name editor (the Account panel's Change name). */
    data object EditDisplayName : Intent

    /** Save the open display-name editor. */
    data object SaveDisplayName : Intent

    /** Close the display-name editor without saving. */
    data object CancelDisplayName : Intent

    /** Submit the open special-key field. */
    data object SpecialKeySubmit : Intent

    /** Close the special-key field without signing in. */
    data object SpecialKeyCancel : Intent

    data class StartingLevelPick(val level: String) : Intent

    /** Clears away state after an away-during-online-match overlay. */
    data object AwayResume : Intent

    /** Appends the next page to the paginated match-history list. */
    data object LoadMoreMatchHistory : Intent

    // Chrome / preferences
    data object ToggleFullscreen : Intent
    data object ToggleTileLabels : Intent

    /** Flip the sound-effect master switch; written to the display preferences. */
    data object ToggleSounds : Intent

    /** Set the sound-effect level (`0..UiPreferences.MAX_SOUND_VOLUME`). */
    data class SetSoundVolume(val value: Int) : Intent

    /** Flip the Score Breakdown email opt-in; written to the account's notification settings. */
    data object ToggleEmailOptIn : Intent

    /** Set the hours between result digests (one of `NotificationPrefsScale.HOURS`). */
    data class SetDigestIntervalHours(val value: Int) : Intent

    data class PickLocale(val tag: String) : Intent

    /**
     * The player set the state of the route [routeKey] names (a body scroll, a
     * fold, a draft). The effect writes [value] into the route UI store; the
     * stack snapshot projects it, so a restored route reads it back through its
     * codec. [remember], from the route's spec, also keeps it past the entry's
     * removal in the remembered store.
     */
    data class WriteRouteUi(
        val routeKey: String,
        val value: RouteUiValue,
        val remember: Boolean = false,
    ) : Intent

    // Reference
    data class OpenReference(val pageId: String? = null) : Intent
    data object ReferenceBack : Intent

    /** Switch the reference browser to a top-level section (section link). */
    data class OpenReferenceSection(val sectionId: String) : Intent

    /** Open an external URL linked from reference body text. */
    data class OpenReferenceExternal(val url: String) : Intent

    // Confirm dialog
    data object ConfirmYes : Intent
    data object ConfirmNo : Intent

    // Route-declared back dismissals
    data object DismissReference : Intent
    data object DismissScoreTable : Intent
    data object DismissLanguagePicker : Intent
    data object DismissSettings : Intent
    /** Close the Account panel, revealing the menu under it. */
    data object DismissAccount : Intent
    data object CancelSetup : Intent
    data object MatchHistoryDismiss : Intent
    data object LeaderboardDismiss : Intent
    /** Close the rating-history chart, revealing the hub or leaderboard under it. */
    data object RatingHistoryDismiss : Intent
    data object NoticesDismiss : Intent
    data object NoticePopupDismiss : Intent

    // Tutorial
    data object TutorialPrimary : Intent
    data object TutorialBack : Intent
    data class TutorialSelect(val lessonId: String) : Intent
    data class TutorialQuizSubmit(val answer: QuizAnswer) : Intent
    data object TutorialQuizNext : Intent
    data object TutorialQuizTryAgain : Intent
    data class TutorialQuizStateChanged(val attempt: QuizInteractionState) : Intent
    data class TutorialOpenHref(val href: String) : Intent

    /**
     * The coach slide's exit finished. The host recorded the leave destination
     * before the exit began, so this only reports the animation is done and the
     * host can land it.
     */
    data object TutorialCoachLeaveComplete : Intent

    /** The wall view committed a new deal during presentation; the host parks it. */
    data class TutorialWallDealCommitted(val deal: WallBuildDeal) : Intent

    /** The wall view published handoff poses; the host parks them for the table. */
    data class TutorialWallHandoffCommitted(val handoff: WallBuildHandoffSnapshot) : Intent
}
