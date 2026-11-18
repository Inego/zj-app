package inego.zungjunggame.app

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.features.online.hub.DEFAULT_ONLINE_HUB_STATE
import inego.zungjunggame.features.online.hub.OnlineHubCodec
import inego.zungjunggame.features.online.hub.OnlineHubState
import inego.zungjunggame.features.ratinghistory.RatingHistoryCodec
import inego.zungjunggame.features.ratinghistory.RatingHistoryState
import inego.zungjunggame.net.ChallengeStart
import inego.zungjunggame.net.LeaderboardClient
import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.net.ShadowRankedAttemptsClient
import inego.zungjunggame.net.ShadowRankedHandsClient
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.online.*
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.session.online.ConnectionFailure
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route

/**
 * Online coordinator, hub, match history, connection card, and presence
 * effects.
 *
 * Two things this effect deliberately does **not** do: it does not move the
 * floor to the online table (the coordinator's own enter/leave decides when a
 * table is live, and the stack follows through [enterOnlineTable]'s landing), and
 * it does not own the away overlay (the coordinator drives that through its own
 * port). It owns the *hub* landing: the surface's appearance loads its presence
 * surfaces through [hubEnter], so they cannot be forgotten by a new caller.
 */
internal class AppOnlineEffects(
    private val onlineCoordinator: OnlineCoordinator,
    private val accountController: AccountController,
    private val sessionRegistry: SessionRegistry,
    private val routes: RouteController,
    private val transitions: ScreenTransitions,
    /** The hub route's player-set tab, both scrolls, and Shadow Ranked draft. */
    private val routeUi: RouteUiState,
    private val shadowRankedStarter: ShadowRankedStarter,
    /** The Shadow Ranked tab's in-memory pool reading; refreshed, never persisted. */
    private val shadowRankedPool: ShadowRankedPoolTracker,
    private val shadowRankedAttempts: ShadowRankedAttemptsClient,
    private val shadowRankedHands: ShadowRankedHandsClient,
    /** The hub's leaderboard reading; opening takes a new one. */
    private val leaderboardClient: LeaderboardClient = LeaderboardClient(),
    /** The rating chart's tile owner; the chart's appearance takes a new reading. */
    private val ratingHistoryClient: RatingHistoryClient = RatingHistoryClient(),
) : OnlineEffects {

    /** The unified Online screen's route; its state is the route UI store's. */
    private val hubRoute: Route.Layer = Route.Layer(RouteIds.ONLINE_HUB)

    override fun primary() {
        if (!accountController.isSignedIn) return
        when {
            onlineCoordinator.presenceClient.waitingEnabled -> onlineCoordinator.cancelSearch()
            onlineCoordinator.isSeated() -> Unit
            onlineCoordinator.isServerShutdown() -> Unit
            else -> onlineCoordinator.startSearch()
        }
    }

    /**
     * The shell's Rejoin band. Unlike [primary] this does not ask presence whether
     * a seat exists: the band is offered from the persisted `online.table` id
     * precisely so an offline player gets a way back, and gating on a seat that has
     * not been reported would answer "not seated" and start a new search instead.
     */
    override fun rejoin() {
        if (!accountController.isSignedIn) return
        onlineCoordinator.rejoinActiveTable()
    }

    override fun abandonToLobby() {
        // Leaving the table reveals the hub on the tab the table was entered from.
        // The table's own exit port returns the hub to that tab, and that tab's
        // selection is what re-reads the pool.
        onlineCoordinator.leaveOnlineTable()
        routes.removeModal(RouteIds.GAME_MENU)
    }

    /**
     * A finished table's **Play again**, read as a rematch on the same tab.
     *
     * The table's origin names what a rematch is: a Live table looks for another
     * live game ([startSearch]), a Shadow Ranked table starts another attempt from
     * the remembered draft. The origin is read *before* the table leaves — the
     * route's args are its record, and [leaveOnlineTable] drops it.
     */
    override fun playAgain() {
        val origin = transitions.tableOrigin()
        onlineCoordinator.leaveOnlineTable()
        routes.removeModal(RouteIds.GAME_MENU)
        if (origin == OnlineTab.ShadowRanked) shadowRankedStart() else onlineCoordinator.startSearch()
    }

    override fun setMinOtherHumans(value: Int) {
        onlineCoordinator.setMinOtherHumans(value)
    }

    override fun cycleRounds() {
        onlineCoordinator.cycleRounds()
    }

    override fun cycleChicken() {
        onlineCoordinator.cycleChicken()
    }

    override fun toggleWaiting() {
        onlineCoordinator.toggleWaiting()
    }



    override fun hubOpen(tab: OnlineTab?) {
        // The hub is a signed-in surface: it shows presence, matchmaking, Shadow
        // Ranked attempts and the account's rating, none of which exist without an
        // account. A signed-out request is refused rather than opening a screen
        // whose only content would be "log in from the main menu" — which is the
        // menu the player is already on. The sign-in path lands here through
        // `presentOnline` once the session exists.
        if (!accountController.isSignedIn) return
        val remembered = routeUi.read(hubRoute.key, OnlineHubCodec) ?: DEFAULT_ONLINE_HUB_STATE
        val target = tab ?: remembered.tab
        if (target != remembered.tab) storeHub(remembered.copy(tab = target))
        // A fresh push loads through [hubEnter], once. An already-open hub is
        // revealed, not re-entered, and was read when its tab was selected.
        transitions.onlineHub()
    }

    override fun hubSelectTab(tab: OnlineTab) {
        // The fallback is the hub's default tab, never [tab] itself: nothing is
        // stored until a write happens, so defaulting to the requested tab would
        // make the first switch a no-op and leave the hub unreadable.
        val current = routeUi.read(hubRoute.key, OnlineHubCodec) ?: DEFAULT_ONLINE_HUB_STATE
        // Entering Shadow Ranked is a read trigger: the counts belong to the tab,
        // and a count from the first visit may be minutes old. A re-selection is
        // one too — a second tap on the active tab, or the table handing the hub
        // back through its own exit port — so only the write is skipped.
        if (current.tab == tab) {
            if (tab == OnlineTab.ShadowRanked) refreshShadowRankedPoolIfVisible()
            return
        }
        storeHub(current.copy(tab = tab))
        if (tab == OnlineTab.ShadowRanked) refreshShadowRankedPoolIfVisible()
    }

    /**
     * Log-out: the hub's last-read tab is account-scoped, so a new player must
     * not land on the previous one's. The tab is rewritten on Live; the
     * remembered Shadow Ranked draft is display state, not account state, and is
     * kept. The hub's route state belongs to this effect, so the reset lives here
     * rather than in [accountController], which cannot see `features/`.
     */
    fun resetHubTabForAccountChange() {
        shadowRankedPool.clear()
        val current = routeUi.read(hubRoute.key, OnlineHubCodec) ?: return
        if (current.tab == OnlineTab.Live) return
        storeHub(current.copy(tab = OnlineTab.Live))
    }

    /**
     * Leaving the whole hub screen ends an in-progress Live search.
     *
     * The hub is the only surface that shows the search, and there is no
     * root-menu indicator, so a search must not outlive it. Switching tabs is
     * *not* a leave: [hubSelectTab] only writes the route state, so the search
     * keeps running behind the Live tab's label indicator.
     */
    override fun hubLeave() {
        onlineCoordinator.cancelSearch()
        transitions.dismissHub()
    }

    /**
     * Load the hub's surfaces when the hub appears.
     *
     * The surface, not a tap, is the trigger: the composition root's
     * [RouteSurfaceLoad] observer calls this for a pushed or restored hub, so
     * the presence request, account surfaces and notice inbox cannot be
     * forgotten by whoever made the hub appear. It navigates nothing — the push
     * that made the hub appear already did.
     *
     * The attempts list is deliberately not here: the hub only links to its
     * card, so it loads when that card appears ([shadowRankedAttemptsEnter])
     * rather than on every visit to the hub. The pool line is here, because it is
     * part of the tab: when Shadow Ranked is the visible tab, entering the hub is
     * one of its read triggers.
     */
    fun hubEnter() {
        onlineCoordinator.presenceClient.requestNow()
        accountController.refreshAccountSurfaces()
        accountController.token?.let { onlineCoordinator.noticeList.refresh(it) }
        refreshShadowRankedPoolIfVisible()
    }

    override fun shadowRankedStart() {
        if (accountController.token == null) return
        // The draft is read, not consumed: the hub stays up while the start is
        // pending, and a successful start must not clear the remembered config.
        val draft = routeUi.read(hubRoute.key, OnlineHubCodec)?.draft
            ?: DEFAULT_SHADOW_RANKED_DRAFT
        shadowRankedStarter.start(draft)
    }

    override fun shadowRankedCancelStart() {
        shadowRankedStarter.cancel()
    }

    override fun shadowRankedRetryStart() {
        if (accountController.token == null) return
        shadowRankedStarter.retry()
    }

    /**
     * The draft's rounds. The write is the effect's because the draft is the pool
     * count's key: the stored value and the badge change together, and a response
     * for the previous draft cannot paint.
     */
    override fun setShadowRankedRounds(rounds: Int) {
        val current = hubState()
        val draft = current.draft ?: DEFAULT_SHADOW_RANKED_DRAFT
        if (draft.rounds == rounds) return
        storeDraft(current, draft.copy(rounds = rounds))
    }

    override fun toggleShadowRankedChicken() {
        val current = hubState()
        val draft = current.draft ?: DEFAULT_SHADOW_RANKED_DRAFT
        storeDraft(current, draft.copy(allowChickenHands = !draft.allowChickenHands))
    }

    /** Store [draft] on [current] and read the pool for it. */
    private fun storeDraft(current: OnlineHubState, draft: GameConfig) {
        val updated = current.copy(draft = draft)
        storeHub(updated)
        refreshShadowRankedPool(updated)
    }

    /**
     * The selected draft's pool line. The tab is the trigger, not a poll: this is
     * called on entering the tab, on every draft edit, and when a game hands the
     * hub back. With no account or token the tracker clears instead of sending.
     */
    private fun refreshShadowRankedPool(state: OnlineHubState = hubState()) {
        val draft = state.draft ?: DEFAULT_SHADOW_RANKED_DRAFT
        shadowRankedPool.load(
            account = accountController.user?.id,
            token = accountController.token,
            rounds = draft.rounds.toChallengeRounds(),
            allowChicken = draft.allowChickenHands,
        )
    }

    /**
     * Refresh only when the hub is actually on screen — the stack's paint range,
     * not merely present beneath a table — and Shadow Ranked is the visible tab.
     * A session rotation during a live game must not run a cosmetic read behind
     * the board.
     */
    private fun refreshShadowRankedPoolIfVisible() {
        if (routes.stack.paintRange.none { it.route.key == hubRoute.key }) return
        val state = hubState()
        if (state.tab != OnlineTab.ShadowRanked) return
        refreshShadowRankedPool(state)
    }

    private fun hubState(): OnlineHubState =
        routeUi.read(hubRoute.key, OnlineHubCodec) ?: DEFAULT_ONLINE_HUB_STATE

    override fun shadowRankedRefreshAttempts() {
        shadowRankedAttempts.load(accountController.token)
    }

    /**
     * The attempts card is content of the hub: its appearance is what loads the
     * signed-in account's attempts and raises the route above the hub, which
     * stays on the stack beneath it.
     */
    override fun viewShadowRankedAttempts() {
        // Navigates only. The card's content is the account's own attempts, which
        // live only in memory, so its appearance is what fetches them (see
        // [shadowRankedAttemptsEnter]) — a tap alone would leave a restored card
        // empty.
        transitions.onlineShadowRankedAttempts()
    }

    /**
     * The attempts card appeared (a push or a restored stack). The account's
     * attempts are not persisted, so the surface's own appearance is the trigger
     * that loads them and the card can never come up showing nothing.
     */
    fun shadowRankedAttemptsEnter() {
        val token = accountController.token ?: return
        shadowRankedAttempts.load(token)
    }

    override fun loadMoreShadowRankedAttempts() {
        val token = accountController.token ?: return
        shadowRankedAttempts.loadMore(token)
    }

    override fun shadowRankedAttemptsDismiss() {
        // The card sits above the hub, so popping it reveals the hub.
        routes.popModal(RouteIds.SHADOW_RANKED_ATTEMPTS)
    }

    /**
     * Open the hand tables of [publicId]'s challenge above whatever raised them
     * (the attempts card or the finish card).
     *
     * The route carries the public id; the modal is a single route, so a second
     * challenge either pushes a fresh instance or replaces the args of the one
     * already up. The document is fetched once per open; the surface-load observer
     * fires during the push and is the single fetch path, so this only fetches
     * when the route was already up with the same id (a refetch).
     */
    override fun openShadowRankedHands(publicId: String) {
        val token = accountController.token
        if (token == null) {
            shadowRankedHands.load(null, publicId)
            return
        }
        val route = Route.Modal(RouteIds.SHADOW_RANKED_HANDS, mapOf(PUBLIC_ID_ARG to publicId))
        val stacked = routes.stack.entries.firstOrNull { it.route.key == route.key }?.route
        when {
            stacked == null -> routes.pushIfAbsent(route)
            stacked.args[PUBLIC_ID_ARG] != publicId -> {
                routes.remove(route.key)
                routes.push(route)
            }
            // Same id: leave the route up; the document below may be refetched.
        }
        if (!shadowRankedHands.isActive(publicId)) {
            shadowRankedHands.load(token, publicId)
        }
    }

    /**
     * The hand-tables route appeared (a push or a restored stack). The stacked
     * route's public id is the document to load; a still-current document is left
     * alone so an open does not refetch it.
     */
    fun shadowRankedHandsEntered() {
        val publicId = stackedHandsPublicId() ?: return
        if (shadowRankedHands.isActive(publicId)) return
        shadowRankedHands.load(accountController.token, publicId)
    }

    override fun shadowRankedHandsDismiss() {
        // The card sits above whatever opened it, so popping it reveals that.
        routes.popModal(RouteIds.SHADOW_RANKED_HANDS)
    }

    override fun shadowRankedHandsStep(delta: Int) {
        shadowRankedHands.step(delta)
    }

    override fun toggleShadowRankedHandsDropdown() {
        shadowRankedHands.toggleDropdown()
    }

    override fun selectShadowRankedHandsAttempt(index: Int) {
        shadowRankedHands.select(index)
    }

    private fun stackedHandsPublicId(): String? {
        val key = Route.Modal(RouteIds.SHADOW_RANKED_HANDS).key
        return routes.stack.entries.firstOrNull { it.route.key == key }?.route?.args?.get(PUBLIC_ID_ARG)
    }

    /**
     * The starter reached `Started`: the hub's request is answered, so the table is
     * entered above the hub with the ticket the server minted. The hub is only
     * *covered* (the table is its child), not removed, so leaving the table reveals
     * it on the Shadow Ranked tab the start came from.
     */
    fun onShadowRankedStarted(start: ChallengeStart) {
        onlineCoordinator.enterOnlineTable(
            tableId = start.tableId,
            ticket = TableTicket(
                ticket = start.ticket,
                tableId = start.tableId,
                seat = start.seat,
            ),
            origin = OnlineTab.ShadowRanked,
        )
    }

    /** Store the hub's state through its codec, remembering it past the route. */
    private fun storeHub(state: OnlineHubState) {
        routeUi.put(
            hubRoute.key,
            RouteUiValue(OnlineHubCodec.type, OnlineHubCodec.encode(state)),
            remember = RouteSpecs.specFor(hubRoute).remembersState,
        )
    }

    override fun viewMatchHistory() {
        // Navigates only. The card's content is the account's own games, which live
        // only in memory, so its appearance is what fetches them (see
        // [matchHistoryEnter]) — a tap alone would leave a restored card empty.
        transitions.onlineMatchHistory()
    }

    /**
     * The match-history card appeared (a push or a restored stack). The account's
     * games are not persisted, so the surface's own appearance is the trigger that
     * loads them and the card can never come up showing nothing.
     */
    fun matchHistoryEnter() {
        val token = accountController.token ?: return
        onlineCoordinator.matchHistory.loadFirstPage(token)
    }

    override fun loadMoreMatchHistory() {
        val token = accountController.token ?: return
        onlineCoordinator.matchHistory.loadMore(token)
    }

    override fun matchHistoryDismiss() {
        // The card sits above the hub, so popping it reveals the hub.
        routes.popModal(RouteIds.MATCH_HISTORY)
    }

    /**
     * Open the hub's Leaderboard card above the hub.
     *
     * Navigates only: the card's reading is in memory, so its appearance is what
     * fetches it (see [leaderboardEnter]).
     */
    override fun openLeaderboard() {
        transitions.onlineLeaderboard()
    }

    /**
     * The leaderboard card appeared (a push or a restored stack).
     *
     * A reading is in memory only and a restored stack comes back with the card up
     * and no reading, so the surface's appearance is the trigger: the tap that opens
     * the card cannot be the only thing that fetches it. The card sits above the
     * hub, which stays underneath.
     *
     * The endpoint is public, so this loads whether or not the player has a session:
     * a signed-out token is null and the server answers with a null `self`, which is
     * exactly the state that hides the Me control.
     */
    fun leaderboardEnter() {
        leaderboardClient.loadFirstPage(accountController.token)
    }

    /**
     * A usable access token exists again, over surfaces that are already up.
     *
     * A cold start replays the stack before the session is back, so a restored
     * leaderboard card loads first with no token at all: its rows are public, but
     * the viewer's own row and the Me control need one. Only the surfaces that are
     * up are reloaded — this runs on every rotation, and a card nobody is looking
     * at must not refetch because its token changed.
     */
    fun sessionEstablished() {
        if (routes.isOpen(Route.Modal(RouteIds.LEADERBOARD).key)) {
            leaderboardEnter()
        }
        if (routes.isOpen(Route.Modal(RouteIds.MATCH_HISTORY).key)) {
            matchHistoryEnter()
        }
        if (routes.isOpen(Route.Modal(RouteIds.RATING_HISTORY).key)) {
            ratingHistoryEnter()
        }
        // A restored hub enters before the session is back, so its pool read was
        // skipped; a rotation also replaces the credential. Both land here and the
        // visible tab re-reads once. The answer on screen is kept: it is keyed by
        // the account and draft, not the token, and this read's generation drops a
        // late response fetched with the old one.
        refreshShadowRankedPoolIfVisible()
    }

    override fun loadLeaderboardWindow(offset: Int) {
        // Stay on the reading's pinned generation; the client ignores a window it
        // already holds or one that already failed.
        leaderboardClient.loadWindow(offset, accountController.token)
    }

    override fun leaderboardDismiss() {
        // The card sits above the hub, so popping it reveals the hub.
        routes.popModal(RouteIds.LEADERBOARD)
    }

    /**
     * Open the rating-history chart above whatever is up.
     *
     * The screen's target is its own route state, so it is written before the
     * push: the first frame already knows whose history it shows, and the stack
     * snapshot persists it with the route. A chart already up keeps its state —
     * reopening it must not throw away the window the player left.
     *
     * The remembered view belongs to its **target**, not to the route: the window
     * names absolute instants and the rung names the buckets they were drawn in.
     * Replaying either onto another player's graph would show that reading at a
     * position and density it never had. A close is not a reset only for the same
     * target; a different one is a new reading and opens on all time (a null
     * window and rung, so the component seeds the window from the publication's
     * own bounds and lets the ladder choose the density).
     */
    override fun openRatingHistory(target: RatingHistoryTarget) {
        val route = Route.Modal(RouteIds.RATING_HISTORY)
        if (routes.isOpen(route.key)) return
        val previous = routeUi.read(route.key, RatingHistoryCodec)
        val remembered = previous?.takeIf { it.target == target }
        val state = RatingHistoryState(
            target = target,
            window = remembered?.window,
            level = remembered?.level,
        )
        routeUi.put(
            route.key,
            RouteUiValue(RatingHistoryCodec.type, RatingHistoryCodec.encode(state)),
            remember = RouteSpecs.specFor(route).remembersState,
        )
        transitions.onlineRatingHistory()
    }

    override fun ratingHistoryDismiss() {
        // The chart sits above the hub or the leaderboard it was opened from, so
        // popping it reveals that surface exactly as it was.
        routes.popModal(RouteIds.RATING_HISTORY)
    }

    /**
     * The chart appeared — pushed over the hub or the leaderboard, or restored
     * with a cold-start stack.
     *
     * The surface, not the tap, is the trigger: the chart's target is its own route
     * state (so a restored chart knows whose history it shows) but its coverage is
     * this client's in-memory store, and a restored stack comes back with none. The
     * parameterless all-time answer is what names the server-picked rung and the
     * bounds the viewport opens on, so it is the one request made from here; the
     * viewport's own tiles are E3's requests through
     * [RatingHistoryClient.requestCoverage].
     *
     * A `Self` chart restored before the session is back loads with a null token and
     * reports the rejection; [sessionEstablished] reloads it under the rotated
     * credential, exactly as the leaderboard card's own row does. A public player
     * read needs no credential at all.
     */
    fun ratingHistoryEnter() {
        val key = Route.Modal(RouteIds.RATING_HISTORY).key
        val target = routeUi.read(key, RatingHistoryCodec)?.target ?: return
        ratingHistoryClient.open(target)
    }

    override fun viewNotices() {
        val token = accountController.token ?: return
        // Raise the inbox before the refresh lands, so the response's own
        // [onNoticesUpdated] sees the list on the stack and marks it rather than
        // popping a notice over it.
        transitions.onlineNotices()
        onlineCoordinator.noticeList.refresh(token)
        // Opening the inbox is seeing it: mark the rows this client already holds,
        // and the refresh callback marks any that arrive with the response.
        onlineCoordinator.noticeList.markAllSeen(token)
    }

    override fun noticesDismiss() {
        // The inbox sits above the hub, so popping it reveals the hub.
        routes.popModal(RouteIds.NOTICES)
    }

    override fun dismissNoticePopup() {
        routes.popModal(RouteIds.NOTICE_POPUP)
        onlineCoordinator.noticeList.acknowledgePopup()
        maybeShowNoticePopup()
    }

    override fun toggleEmailOptIn() {
        val token = accountController.token ?: return
        onlineCoordinator.notificationPrefs.toggleEmailOptIn(token)
    }

    override fun setDigestIntervalHours(value: Int) {
        val token = accountController.token ?: return
        onlineCoordinator.notificationPrefs.setDigestIntervalHours(token, value)
    }

    /**
     * Refresh the notice inbox. The runtime polls this; a fresh unseen notice is
     * presented by [onNoticesUpdated] once the response lands.
     */
    fun pollNotices() {
        val token = accountController.token ?: return
        onlineCoordinator.noticeList.refresh(token)
    }

    /**
     * A refresh replaced the inbox: while the player is not in a live session,
     * present the newest unseen notice as a popup. The notices list open
     * is itself seeing them, so it marks them instead.
     */
    fun onNoticesUpdated() {
        val token = accountController.token ?: return
        if (routes.stack.contains(Route.Modal(RouteIds.NOTICES).key)) {
            onlineCoordinator.noticeList.markAllSeen(token)
            return
        }
        maybeShowNoticePopup()
    }

    private fun maybeShowNoticePopup() {
        val popup = onlineCoordinator.noticeList.popupNotice ?: return
        // A live board, lesson, or table owns the display; the popup waits.
        if (sessionRegistry.sessionOwnsDisplay) return
        if (routes.stack.contains(Route.Modal(RouteIds.NOTICE_POPUP).key)) return
        val token = accountController.token ?: return
        // Showing the popup is seeing it; the server is told so a waiting mail drops.
        onlineCoordinator.noticeList.markSeen(token, listOf(popup.id))
        routes.pushModal(RouteIds.NOTICE_POPUP)
    }

    override fun connectCancel() {
        // Leaving the table reveals the hub on the tab it was entered from; the
        // table's exit port selects that tab, which re-reads the pool.
        onlineCoordinator.leaveOnlineTable()
        routes.removeModal(RouteIds.GAME_MENU)
    }

    override fun connectRetry() {
        onlineCoordinator.onlineHost?.retryConnection()
    }

    override fun returnHome() {
        val authExpired = onlineCoordinator.onlineHost?.connectionFailure ==
            ConnectionFailure.Authentication
        // Leaving the table reveals the hub on the tab the table was entered from;
        // there is no second landing. The table's exit port selects that tab, and
        // a Shadow Ranked selection re-reads the pool.
        onlineCoordinator.leaveOnlineTable()
        routes.removeModal(RouteIds.GAME_MENU)
        if (authExpired) {
            // A dead session has no hub to land on. Expiring it dismisses the hub
            // the leave just revealed, so the player lands on the main menu, where
            // Log in lives — the hub is a signed-in surface.
            accountController.expireSession()
        }
    }

    override fun awayResume() {
        onlineCoordinator.resumeFromAway()
    }

    private companion object {
        /** The `SHADOW_RANKED_HANDS` route arg naming the challenge whose tables to show. */
        const val PUBLIC_ID_ARG = "publicId"
    }
}
