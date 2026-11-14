package inego.zungjunggame.app

import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.IntentSink
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.ui.stack.Stack

/**
 * Reduces the [Intent] vocabulary into app effect ports.
 *
 * Every variant is handled by calling the owning port ([NavigationEffects],
 * [SessionEffects], [TutorialEffects], [OnlineEffects], [AccountEffects],
 * [PrefsEffects], [ChromeEffects]); the dispatcher itself
 * only owns translation, route→effect mapping, and the context-sensitive
 * [Intent.Back] / [Intent.NewGame] cases. An unmapped route is logged and
 * swallowed so input can never wedge the frame loop.
 *
 * GL-free; the production wiring supplies the `app/` effect classes, tests
 * supply fakes.
 */
class AppDispatcher(
    private val navigation: NavigationEffects,
    private val chrome: ChromeEffects,
    private val prefs: PrefsEffects,
    private val session: SessionEffects,
    private val tutorial: TutorialEffects,
    private val online: OnlineEffects,
    private val account: AccountEffects,
    /**
     * The live [SessionKind], derived from the stack floor by the session
     * registry. A function, not a value: the dispatcher is long-lived and the
     * kind changes as the floor does.
     */
    private val sessionKind: () -> SessionKind,
    private val stack: () -> Stack,
    private val backOf: (Route) -> Intent?,
    private val log: (String) -> Unit = {},
) : IntentSink {

    override fun emit(intent: Intent) {
        handle(intent)
    }

    /** Applies [intent]; returns false only when it had no mapped effect. */
    fun handle(intent: Intent): Boolean {
        when (intent) {
            Intent.Back -> return back()
            is Intent.PushRoute -> return push(intent.route)
            is Intent.PopRoute -> return back()
            Intent.ExitToMainMenu -> session.exitToMainMenu()

            Intent.ShowMenu -> chrome.showMenu()
            Intent.HideMenu -> chrome.hideMenu()
            Intent.ExitApp -> chrome.exitApp()
            Intent.DismissReference -> navigation.dismissReference()
            Intent.DismissScoreTable -> session.dismissScoreTable()
            Intent.DismissLanguagePicker -> prefs.dismissLanguagePicker()
            Intent.DismissSettings -> prefs.dismissSettings()
            Intent.DismissAccount -> account.dismissAccount()
            Intent.CancelSetup -> session.cancelSetup()
            Intent.MatchHistoryDismiss -> online.matchHistoryDismiss()
            Intent.LeaderboardDismiss -> online.leaderboardDismiss()
            Intent.NoticesDismiss -> online.noticesDismiss()
            Intent.NoticePopupDismiss -> online.dismissNoticePopup()

            is Intent.Discard -> session.discard(intent.tileType, intent.visualId, intent.isDrawn)
            Intent.DiscardDrawnTile -> session.discardDrawnTile()
            is Intent.ApplyChoice -> session.applyChoice(intent.choice)
            Intent.Hint -> session.hint()
            Intent.AiAssist -> session.aiAssist()
            Intent.NextHand -> session.requestNextHand()
            Intent.NewGame -> when {
                isSetupOpen() -> session.startFromSetup()
                sessionKind() == SessionKind.Online -> online.abandonToLobby()
                else -> session.requestNewGame()
            }
            Intent.PlayAgain -> online.playAgain()
            Intent.PlayAgainstBots -> session.enterFromRoot()
            Intent.OpenTutorial -> tutorial.openFromRoot()

            Intent.StartSearch, Intent.CancelSearch -> online.primary()
            // Rejoin is its own effect: the hub's rejoin band is offered from the
            // persisted table id, which a seat-gated primary cannot resolve.
            Intent.RejoinTable -> online.rejoin()
            Intent.LogIn -> account.logIn()
            Intent.LogInSpecialKey -> account.logInSpecialKey()
            Intent.LogOut -> session.confirm(ConfirmPrompt.LogOut)
            is Intent.SetMinOtherHumans -> online.setMinOtherHumans(intent.value)
            Intent.CycleRounds -> online.cycleRounds()
            Intent.CycleChicken -> online.cycleChicken()
            Intent.ViewMatchHistory -> online.viewMatchHistory()
            Intent.OpenLeaderboard -> online.openLeaderboard()
            is Intent.LoadLeaderboardWindow -> online.loadLeaderboardWindow(intent.offset)
            is Intent.OpenRatingHistory -> online.openRatingHistory(intent.target)
            Intent.RatingHistoryDismiss -> online.ratingHistoryDismiss()
            Intent.ViewNotices -> online.viewNotices()
            Intent.ToggleWaiting -> online.toggleWaiting()


            is Intent.OpenOnline -> online.hubOpen(intent.tab)
            is Intent.SelectOnlineTab -> online.hubSelectTab(intent.tab)
            Intent.LeaveOnlineHub -> online.hubLeave()
            Intent.StartShadowRanked -> online.shadowRankedStart()
            Intent.CancelShadowRankedStart -> online.shadowRankedCancelStart()
            Intent.RetryShadowRankedStart -> online.shadowRankedRetryStart()
            Intent.RefreshShadowRankedAttempts -> online.shadowRankedRefreshAttempts()
            Intent.ViewShadowRankedAttempts -> online.viewShadowRankedAttempts()
            Intent.LoadMoreShadowRankedAttempts -> online.loadMoreShadowRankedAttempts()
            Intent.ShadowRankedAttemptsDismiss -> online.shadowRankedAttemptsDismiss()
            is Intent.OpenShadowRankedHands -> online.openShadowRankedHands(intent.publicId)
            Intent.ShadowRankedHandsDismiss -> online.shadowRankedHandsDismiss()
            is Intent.ShadowRankedHandsStep -> online.shadowRankedHandsStep(intent.delta)
            Intent.ToggleShadowRankedHandsDropdown -> online.toggleShadowRankedHandsDropdown()
            is Intent.SelectShadowRankedHandsAttempt -> online.selectShadowRankedHandsAttempt(intent.index)

            Intent.OnlineConnectCancel -> online.connectCancel()
            Intent.OnlineConnectRetry -> online.connectRetry()
            Intent.OnlineReturnHome -> online.returnHome()

            Intent.FirstLoginSubmit -> account.firstLoginSubmit()
            Intent.FirstLoginBack -> account.firstLoginBack()
            Intent.OpenAccount -> account.openAccount()
            Intent.EditDisplayName -> account.editDisplayName()
            Intent.SaveDisplayName -> account.saveDisplayName()
            Intent.CancelDisplayName -> account.cancelDisplayName()
            Intent.SpecialKeySubmit -> account.specialKeySubmit()
            Intent.SpecialKeyCancel -> account.specialKeyCancel()
            is Intent.StartingLevelPick -> account.startingLevelPick(intent.level)

            Intent.AwayResume -> online.awayResume()
            Intent.LoadMoreMatchHistory -> online.loadMoreMatchHistory()

            Intent.ToggleFullscreen -> chrome.toggleFullscreen()
            Intent.ToggleTileLabels -> prefs.toggleTileLabels()
            Intent.ToggleSounds -> prefs.toggleSounds()
            is Intent.SetSoundVolume -> prefs.setSoundVolume(intent.value)
            Intent.ToggleEmailOptIn -> online.toggleEmailOptIn()
            is Intent.SetDigestIntervalHours -> online.setDigestIntervalHours(intent.value)
            is Intent.ParkScoreBreakdown -> prefs.parkScoreBreakdown(intent.x, intent.y)
            Intent.ToggleScoreBreakdownFold -> prefs.toggleScoreBreakdownFold()
            is Intent.SetScoreBreakdownScroll -> prefs.setScoreBreakdownScroll(intent.offset)
            is Intent.WriteRouteUi -> prefs.writeRouteUi(intent.routeKey, intent.value, intent.remember)
            is Intent.ParkHintButton -> prefs.parkHintButton(intent.x, intent.y)
            is Intent.ParkClaimPanel -> prefs.parkClaimPanel(intent.x, intent.y)
            is Intent.PickLocale -> pickLocale(intent.tag)

            is Intent.OpenReference -> navigation.openReference(intent.pageId)
            Intent.ReferenceBack -> navigation.referenceHistoryBack()
            is Intent.OpenReferenceSection -> navigation.openReferenceSection(intent.sectionId)
            is Intent.OpenReferenceExternal -> navigation.openReferenceExternal(intent.url)

            Intent.ConfirmYes -> session.confirmYes()
            Intent.ConfirmNo -> session.confirmNo()

            Intent.TutorialPrimary -> tutorial.applyPrimary()
            Intent.TutorialBack -> tutorial.applyBack()
            is Intent.TutorialSelect ->
                if (tutorial.selectLesson(intent.lessonId)) {
                    session.confirm(ConfirmPrompt.RestartLesson)
                }
            is Intent.TutorialQuizSubmit -> tutorial.quizSubmit(intent.answer)
            Intent.TutorialQuizNext -> tutorial.quizNext()
            Intent.TutorialQuizTryAgain -> tutorial.quizTryAgain()
            is Intent.TutorialQuizStateChanged -> tutorial.quizStateChanged(intent.attempt)
            is Intent.TutorialOpenHref -> tutorial.openHref(intent.href)
            Intent.TutorialCoachLeaveComplete -> tutorial.slideLeaveComplete()
            is Intent.TutorialWallDealCommitted -> tutorial.wallDealCommitted(intent.deal)
            is Intent.TutorialWallHandoffCommitted -> tutorial.wallHandoffCommitted(intent.handoff)
        }
        return true
    }

    /**
     * Applies the back intent declared by the topmost route that has one,
     * skipping layers that opt out; false when no route declares back.
     */
    fun back(): Boolean {
        for (entry in stack().entries.asReversed()) {
            val intent = backOf(entry.route) ?: continue
            return handle(intent)
        }
        return false
    }

    /** True while the new-game setup modal is anywhere on the stack. */
    private fun isSetupOpen(): Boolean =
        stack().entries.any { routeId(it.route) == RouteIds.NEW_GAME_SETUP }

    private fun push(route: Route): Boolean {
        when (val id = routeId(route)) {
            RouteIds.CONFIRM -> {
                // The route names the question; a confirm that names none is not a card.
                val prompt = ConfirmPrompt.of(route)
                if (prompt == null) {
                    log("AppDispatcher: confirm route carries no question")
                    return false
                }
                session.confirm(prompt)
            }
            RouteIds.SCORE_TABLE -> session.openScoreTable()
            RouteIds.GAME_MENU -> chrome.showMenu()
            RouteIds.LANGUAGE_PICKER -> prefs.openLanguagePicker()
            RouteIds.SETTINGS -> prefs.openSettings()
            RouteIds.NEW_GAME_SETUP -> session.enterSetup()
            RouteIds.LOCAL_BOARD -> session.continueParked()
            else -> {
                log("AppDispatcher: unhandled push route '$id'")
                return false
            }
        }
        return true
    }

    private fun pickLocale(tag: String) {
        // The picker publishes the stable BCP 47 [AppLocale.tag], not the enum
        // constant name, so resolve by tag (e.g. "en-US" / "ru").
        val locale = AppLocale.entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
        if (locale == null) {
            log("AppDispatcher: unknown locale '$tag'")
            return
        }
        prefs.pickLocale(locale)
    }

    private fun routeId(route: Route): String = RouteIds.catalogIdOf(route)
}
