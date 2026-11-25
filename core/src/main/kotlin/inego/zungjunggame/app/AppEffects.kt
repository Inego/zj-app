package inego.zungjunggame.app

import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * Effect ports the [AppDispatcher] reduces [inego.zungjunggame.input.Intent]s
 * into. Each port is a small surface over one concern so the dispatcher stays a
 * pure mapping and tests can fake every effect; the production implementations
 * are the `App*Effects` classes in `app/`, one per port.
 */

/** Reference browser open / deep-link / back / dismiss. */
interface NavigationEffects {
    fun openReference(pageId: String?)
    fun openReferenceSection(sectionId: String)
    fun openReferenceExternal(url: String)
    fun referenceHistoryBack()
    fun dismissReference()
}

/** Desktop chrome and the game-menu modal (with its free-play navigation). */
interface ChromeEffects {
    fun showMenu()
    fun hideMenu()
    fun toggleFullscreen()

    /** Quit the app; the runtime persists first. */
    fun exitApp()
}

/** Display/locale preferences and their pickers. */
interface PrefsEffects {
    fun toggleTileLabels()
    /** Flip the sound-effect master switch; written to the display preferences. */
    fun toggleSounds()
    /**
     * The player set the sound-effect level. Written to the display preferences;
     * the effect clamps to the slider's range, so the model cannot hold a value
     * the widget could not produce.
     */
    fun setSoundVolume(value: Int)
    /**
     * The player moved the hand-end card to ([x], [y]) from its rest pose. The
     * arrangement is theirs, so it is written to the display preferences.
     */
    fun parkScoreBreakdown(x: Float, y: Float)
    /**
     * The player folded (or unfolded) the hand-end card. Its fold is display state
     * like the parked place, so it is written to the display preferences rather than
     * left in the card, which forgets it on the next hand and on a restart.
     */
    fun toggleScoreBreakdownFold()
    /**
     * The player scrolled the hand-end card's body to [offset]. The card is an
     * announced layer whose route is not in the stack snapshot, so its body scroll
     * is remembered in the display preferences instead of the route's component
     * state.
     */
    fun setScoreBreakdownScroll(offset: Float)
    /**
     * The player set the state of the route [routeKey] names (a body scroll, a
     * fold, a draft). The value is written into the route UI store, which the
     * stack snapshot projects and a restored route reads back through its codec.
     */
    fun writeRouteUi(routeKey: String, value: RouteUiValue, remember: Boolean = false)
    /**
     * The player moved the board's HINT button to ([x], [y]) from its rest pose.
     * The board's movable chrome is the player's arrangement, written to the
     * display preferences so it outlives the table and the process.
     */
    fun parkHintButton(x: Float, y: Float)
    /** The player moved the board's claim / action row to ([x], [y]). */
    fun parkClaimPanel(x: Float, y: Float)
    fun pickLocale(locale: AppLocale)
    fun openLanguagePicker()
    fun dismissLanguagePicker()
    /** Show Settings above the current layer (menu card or tutorial shell). */
    fun openSettings()
    fun dismissSettings()
}

/** Board commits, the free-play session, score table, setup, and shared confirm. */
interface SessionEffects {
    fun discard(tileType: Int, visualId: Int, isDrawn: Boolean)
    fun discardDrawnTile()
    fun applyChoice(choice: Choice)
    fun hint()
    fun aiAssist()
    fun requestNextHand()
    fun requestNewGame()
    fun startFromSetup()
    fun cancelSetup()
    fun enterSetup()
    fun openScoreTable()
    fun dismissScoreTable()
    fun continueParked()
    fun enterFromRoot()
    fun exitToMainMenu()
    /** Raise the shared confirm card asking [prompt]; its route carries the question. */
    fun confirm(prompt: ConfirmPrompt)
    fun confirmYes()
    fun confirmNo()
}

/** Interactive tutorial host commands. */
interface TutorialEffects {
    fun applyPrimary()
    fun applyBack()
    /** @return true when the caller must confirm a restart of the current lesson. */
    fun selectLesson(lessonId: String): Boolean
    fun quizSubmit(answer: QuizAnswer)
    fun quizNext()
    fun quizTryAgain()
    fun quizStateChanged(attempt: QuizInteractionState)
    fun openHref(href: String)
    /** Root-menu Tutorial entry: open the TOC / restore the parked lesson. */
    fun openFromRoot()
    /** The coach slide's exit animation finished; land the recorded destination. */
    fun slideLeaveComplete()
    /** The wall view committed a deal; park it for the current lesson. */
    fun wallDealCommitted(deal: WallBuildDeal)
    /** The wall view published handoff poses; park them for the wall→table seed. */
    fun wallHandoffCommitted(handoff: WallBuildHandoffSnapshot)
}

/** Online coordinator, hub, match history, connection card, and presence. */
interface OnlineEffects {
    /** Online home primary button: sign in / cancel search / find game. */
    fun primary()

    /**
     * Rejoin the table the player is still seated at.
     *
     * Its own effect, not a branch of [primary]: the shell offers Rejoin from the
     * persisted `online.table` id as well as from presence, and an offline
     * player's presence has not answered — so a primary that gates on a seat would
     * read that as "not seated" and start a *new* search instead. Rejoin is
     * unambiguous, so it resolves itself.
     */
    fun rejoin()
    fun abandonToLobby()
    fun playAgain()
    fun setMinOtherHumans(value: Int)
    fun cycleRounds()
    fun cycleChicken()
    fun toggleWaiting()

    /** Open the unified Online hub; [tab] overrides the remembered tab. */
    fun hubOpen(tab: OnlineTab?)

    /** The player chose a hub tab; stored as the hub route's own state. */
    fun hubSelectTab(tab: OnlineTab)

    /** Leave the hub for the root menu; an in-progress Live search is cancelled. */
    fun hubLeave()

    /** Start a Shadow Ranked attempt from the hub's remembered draft. */
    fun shadowRankedStart()

    /** Cancel an in-flight Shadow Ranked start. */
    fun shadowRankedCancelStart()

    /** Retry a failed Shadow Ranked start with the remembered draft. */
    fun shadowRankedRetryStart()

    /** Set the draft's rounds (1 or 2), store it and refresh the pool count. */
    fun setShadowRankedRounds(rounds: Int)

    /** Flip the draft's chicken toggle, store it and refresh the pool count. */
    fun toggleShadowRankedChicken()

    /** Flip Shadow Ranked's fast-bots toggle; stored without refreshing the pool count. */
    fun toggleShadowRankedFastBots()

    /** Reload the signed-in account's Shadow Ranked attempts. */
    fun shadowRankedRefreshAttempts()

    /** Open the attempts panel; refreshes the list it shows. */
    fun viewShadowRankedAttempts()

    /** Fetch the next page of Shadow Ranked attempts. */
    fun loadMoreShadowRankedAttempts()

    /** Close the attempts panel, revealing the hub under it. */
    fun shadowRankedAttemptsDismiss()

    /** Open the hand tables of [publicId]'s challenge; a finished-row entry. */
    fun openShadowRankedHands(publicId: String)

    /** Close the hand tables, revealing whatever opened them. */
    fun shadowRankedHandsDismiss()

    /** Move the hand tables' Who selection by [delta]; wraps. */
    fun shadowRankedHandsStep(delta: Int)

    /** Open or close the hand tables' Who dropdown. */
    fun toggleShadowRankedHandsDropdown()

    /** Choose the hand tables' Who row at [index]. */
    fun selectShadowRankedHandsAttempt(index: Int)

    fun viewMatchHistory()
    fun loadMoreMatchHistory()
    fun matchHistoryDismiss()

    /** Open the hub's Leaderboard card; fetches the first page of a new reading. */
    fun openLeaderboard()

    /** Fetch one window of the pinned leaderboard reading as the viewport reaches it. */
    fun loadLeaderboardWindow(offset: Int)

    /** Close the leaderboard card, revealing the hub under it. */
    fun leaderboardDismiss()

    /**
     * Open the rating-history chart for [target] — the viewer's own from the hub,
     * a listed player's from a Leaderboard row — writing the target as the
     * route's own state before the push.
     */
    fun openRatingHistory(target: RatingHistoryTarget)

    /** Close the rating-history chart, revealing the surface under it. */
    fun ratingHistoryDismiss()
    /** Open the notices inbox; refreshes the list and marks it seen. */
    fun viewNotices()
    fun noticesDismiss()
    /** Dismiss the freshly arrived notice popup. */
    fun dismissNoticePopup()
    /** Flip the account's Score Breakdown email opt-in. */
    fun toggleEmailOptIn()
    /** Set the account's hours between result digests. */
    fun setDigestIntervalHours(value: Int)
    fun connectCancel()
    fun connectRetry()
    fun returnHome()
    fun awayResume()
}

/** Authentication / account lifecycle. */
interface AccountEffects {
    fun logIn()

    /** Open, submit, or dismiss the Google Play reviewer's special-key field. */
    fun logInSpecialKey()
    fun specialKeySubmit()
    fun specialKeyCancel()
    fun firstLoginSubmit()
    fun firstLoginBack()
    /** Open / save / dismiss the account display-name editor. */
    fun editDisplayName()
    fun saveDisplayName()
    fun cancelDisplayName()
    fun startingLevelPick(level: String)

    /** Show / dismiss the Account panel above the menu card. */
    fun openAccount()
    fun dismissAccount()
}


/**
 * Plays a semantic [SoundCue]. The production implementation owns the sample
 * handles and gates on `ui.prefs`; nothing in `ui/`, `session/` or `features/`
 * touches `Gdx.audio`.
 *
 * A cue's layers may be offset from each other, so a player that defers any of
 * them has to expose when it still owes one: the frame loop presents on demand
 * and will not wake again for a sound on its own.
 */
interface SoundPlayer {
    fun play(cue: SoundCue)

    /**
     * Start whatever layers of pending cues have come due. Called once per
     * presented frame, because dispatch rides the frame loop rather than a
     * timer thread.
     */
    fun tick()

    /** Whether [tick] still owes a sample. */
    fun hasScheduled(): Boolean
}
