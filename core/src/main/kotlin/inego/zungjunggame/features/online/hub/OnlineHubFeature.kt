package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.ONLINE_HUB] context with the runtime registry. */
object OnlineHubFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Layer(RouteIds.ONLINE_HUB)
        registry.register(
            routeKey = route.key,
            factory = { registered -> OnlineHubComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = OnlineHubActions,
        )
    }

    /**
     * Registers [RouteIds.ONLINE_HUB] with host-owned seams: the overlay, the two
     * frame views, the camera, and the route UI store.
     *
     * The parameterless overload keeps the catalog invariant (`RouteIds.all` must
     * be covered by `GameFeatures`); this overload is what `AppGraph` calls once
     * it builds the real widgets.
     */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> OnlineHubOverlay,
        view: () -> OnlineHubView = { OnlineHubView() },
        shadowView: () -> ShadowRankedView = { ShadowRankedView() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Layer(RouteIds.ONLINE_HUB)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                OnlineHubComponent(
                    route = registered,
                    overlay = overlay(),
                    view = view,
                    shadowView = shadowView,
                    camera = camera,
                    state = routeUi.port(registered.key, OnlineHubCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = OnlineHubActions,
        )
    }
}

/**
 * Maps the hub's published controls to [Intent]s.
 *
 * The tab strip selects a tab, the close button and back leave the hub, the live
 * body's controls reuse the live intents, and the Shadow Ranked controls drive
 * the start lifecycle and the draft. The draft edits are global commands rather
 * than route-state writes resolved here: the effect that stores the new draft
 * also refreshes the pool count keyed by it.
 */
object OnlineHubActions : RouteActions {
    // Shell: the tabs and the close button.
    const val SELECT_LIVE_TAB = "onlineHub.tab.live"
    const val SELECT_SHADOW_TAB = "onlineHub.tab.shadowRanked"

    /**
     * The hub-level **Leaderboard** link, in the header on both tabs. Published as
     * a shell hit region because the list belongs to neither tab.
     */
    const val OPEN_LEADERBOARD = "onlineHub.leaderboard"

    /**
     * The header's **rating, as the viewer's own history link**: the number is
     * printed in the ranked row while the viewer is rated, and tapping it opens
     * the viewer's own history. It is the same destination the hub used to name
     * with a separate link, now carried by the value itself.
     */
    const val OPEN_RATING_HISTORY = "onlineHub.ratingHistory"
    const val DISMISS = "onlineHub.dismiss"

    /**
     * The hub **card**, published behind every control: the hub claims the presses
     * that hit no control, so they are dropped instead of read as the scrim
     * ([outside]). Maps to no intent — only the scrim dismisses.
     */
    const val ABSORB = "onlineHub.absorb"

    /**
     * The shell's **Rejoin** band, above the tab strip. Hub-level, so it is
     * published as a shell hit region rather than resolved from a panel verdict:
     * a table belongs to neither tab, so neither body can own the way back to it.
     */
    const val REJOIN = "onlineHub.rejoin"

    // Live tab: the matchmaking primary button per its resolved state.
    const val PRIMARY_FIND_GAME = "onlineHub.primary.findGame"
    const val PRIMARY_CANCEL_SEARCH = "onlineHub.primary.cancelSearch"
    const val PRIMARY_REJOIN = "onlineHub.primary.rejoin"

    // Live tab: the body's other controls.

    const val SET_MIN_OTHER_HUMANS = "onlineHub.setMinOtherHumans"
    const val CYCLE_ROUNDS = "onlineHub.cycleRounds"
    const val CYCLE_CHICKEN = "onlineHub.cycleChicken"
    const val PAST_GAMES = "onlineHub.pastGames"

    // Shadow Ranked tab.
    const val START = "onlineHub.shadowRanked.start"
    const val CANCEL_START = "onlineHub.shadowRanked.cancelStart"
    const val RETRY = "onlineHub.shadowRanked.retry"
    const val VIEW_ATTEMPTS = "onlineHub.shadowRanked.viewAttempts"

    /**
     * The two draft edits. They are global commands because the draft is also the
     * pool's key: the effect writes the route state **and** refreshes the count,
     * so a badge can never outlive the draft it was read for.
     */
    const val SET_ROUNDS_ONE = "onlineHub.shadowRanked.roundsOne"
    const val SET_ROUNDS_TWO = "onlineHub.shadowRanked.roundsTwo"
    const val TOGGLE_CHICKEN = "onlineHub.shadowRanked.toggleChicken"
    const val TOGGLE_FAST_BOTS = "onlineHub.shadowRanked.toggleFastBots"

    /**
     * The description's info control. Stable reference page id (`structure.json`,
     * Rules section); the panel deep-links to it, never an English label.
     */
    const val SHADOW_RANKED_PAGE_ID = "shadow-ranked"
    const val VIEW_REFERENCE = "onlineHub.shadowRanked.viewReference"

    /**
     * The Live primary's action id for its resolved state; null when disabled.
     *
     * There is no rejoin case: a disabled primary covers both "a server is
     * draining" and "the player is seated", and in the seated case the shell's
     * own [REJOIN] band is the way back to the table.
     */
    fun primaryId(action: LiveTabPanel.PrimaryAction): String? = when (action) {
        LiveTabPanel.PrimaryAction.FindGame -> PRIMARY_FIND_GAME
        LiveTabPanel.PrimaryAction.CancelSearch -> PRIMARY_CANCEL_SEARCH
        LiveTabPanel.PrimaryAction.Disabled -> null
    }

    /**
     * The action a panel verdict resolves to. [primaryAction] is the live body's
     * resolved primary state; Shadow Ranked verdicts ignore it.
     */
    fun actionFor(
        verdict: HubVerdict,
        primaryAction: LiveTabPanel.PrimaryAction,
    ): ComponentAction? = when (verdict) {
        HubVerdict.Primary -> primaryId(primaryAction)?.let { ComponentAction(it) }
        is HubVerdict.SetMinOtherHumans ->
            ComponentAction(SET_MIN_OTHER_HUMANS, mapOf("value" to verdict.value))

        HubVerdict.CycleRounds -> ComponentAction(CYCLE_ROUNDS)
        HubVerdict.CycleChicken -> ComponentAction(CYCLE_CHICKEN)
        HubVerdict.PastGames -> ComponentAction(PAST_GAMES)
        HubVerdict.Start -> ComponentAction(START)
        HubVerdict.CancelStart -> ComponentAction(CANCEL_START)
        HubVerdict.Retry -> ComponentAction(RETRY)
        HubVerdict.ViewAttempts -> ComponentAction(VIEW_ATTEMPTS)
        HubVerdict.ShadowRankedReference -> ComponentAction(VIEW_REFERENCE)
        HubVerdict.SetRoundsOne -> ComponentAction(SET_ROUNDS_ONE)
        HubVerdict.SetRoundsTwo -> ComponentAction(SET_ROUNDS_TWO)
        HubVerdict.ToggleChicken -> ComponentAction(TOGGLE_CHICKEN)
        HubVerdict.ToggleFastBots -> ComponentAction(TOGGLE_FAST_BOTS)
        HubVerdict.None, is HubVerdict.BodyScroll -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        SELECT_LIVE_TAB -> Intent.SelectOnlineTab(OnlineTab.Live)
        SELECT_SHADOW_TAB -> Intent.SelectOnlineTab(OnlineTab.ShadowRanked)
        OPEN_LEADERBOARD -> Intent.OpenLeaderboard
        OPEN_RATING_HISTORY -> Intent.OpenRatingHistory(RatingHistoryTarget.Self)
        DISMISS -> Intent.LeaveOnlineHub
        REJOIN -> Intent.RejoinTable
        PRIMARY_FIND_GAME -> Intent.StartSearch
        PRIMARY_CANCEL_SEARCH -> Intent.CancelSearch
        PRIMARY_REJOIN -> Intent.RejoinTable
        // The card claiming a press is the point: resolved, then dropped.
        ABSORB -> null

        SET_MIN_OTHER_HUMANS -> Intent.SetMinOtherHumans(action.payload["value"] as? Int ?: 0)
        CYCLE_ROUNDS -> Intent.CycleRounds
        CYCLE_CHICKEN -> Intent.CycleChicken
        PAST_GAMES -> Intent.ViewMatchHistory
        START -> Intent.StartShadowRanked
        CANCEL_START -> Intent.CancelShadowRankedStart
        RETRY -> Intent.RetryShadowRankedStart
        SET_ROUNDS_ONE -> Intent.SetShadowRankedRounds(1)
        SET_ROUNDS_TWO -> Intent.SetShadowRankedRounds(2)
        TOGGLE_CHICKEN -> Intent.ToggleShadowRankedChicken
        TOGGLE_FAST_BOTS -> Intent.ToggleShadowRankedFastBots
        VIEW_ATTEMPTS -> Intent.ViewShadowRankedAttempts
        VIEW_REFERENCE -> Intent.OpenReference(SHADOW_RANKED_PAGE_ID)
        else -> null
    }

    override fun outside(): Intent = Intent.LeaveOnlineHub

    /**
     * Back closes the hub, like the close button. It must not return [Intent.Back]
     * itself: the dispatcher resolves `Intent.Back` through this table, so a
     * self-answer would recurse.
     */
    override fun back(route: Route): Intent = Intent.LeaveOnlineHub
}
