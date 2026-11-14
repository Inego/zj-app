package inego.zungjunggame.features.leaderboard

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.RouteScrollCodec
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.LEADERBOARD] modal with the runtime registry. */
object LeaderboardFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.LEADERBOARD)
        registry.register(
            routeKey = route.key,
            factory = { registered -> LeaderboardComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = LeaderboardActions,
        )
    }

    /** Registers the [RouteIds.LEADERBOARD] modal with host-owned overlay and page seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> LeaderboardOverlay,
        renderInput: () -> LeaderboardRenderInput = { LeaderboardRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.LEADERBOARD)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                LeaderboardComponent(
                    route = registered,
                    overlay = overlay(),
                    renderInput = renderInput,
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = LeaderboardActions,
        )
    }
}

/**
 * Maps the leaderboard card's close control to [Intent.Back] and back to
 * [Intent.LeaderboardDismiss]; a row tap opens that player's rating history with
 * the row's public handle. The Top and Me controls resolve a scroll offset in
 * the overlay, so they never reach this table; the window the viewport reaches
 * travels as [LOAD_WINDOW] and maps to [Intent.LoadLeaderboardWindow].
 */
object LeaderboardActions : RouteActions {
    const val DISMISS = "leaderboard.dismiss"
    const val LOAD_WINDOW = "leaderboard.loadWindow"

    /** A tap on a listed player's row: open the rating history behind it. */
    const val OPEN_RATING_HISTORY = "leaderboard.ratingHistory"

    /** The snapshot offset a [LOAD_WINDOW] action carries. */
    const val KEY_OFFSET = "offset"

    /** The public history handle an [OPEN_RATING_HISTORY] action carries. */
    const val KEY_HISTORY_ID = "historyId"

    /** The action asking for the window that starts at [offset] of the pinned reading. */
    fun loadWindow(offset: Int): ComponentAction =
        ComponentAction(LOAD_WINDOW, mapOf(KEY_OFFSET to offset))

    /** The action asking for the rating history addressed by [historyId]. */
    fun ratingHistory(historyId: String): ComponentAction =
        ComponentAction(OPEN_RATING_HISTORY, mapOf(KEY_HISTORY_ID to historyId))

    /** Maps the control a completed press on the card resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? = when {
        verdict.close -> ComponentAction(DISMISS)
        // The card's rows are the only other thing a release can select, and the
        // overlay only ever reports a row that a fetched window holds.
        verdict.selectedId != null -> ratingHistory(verdict.selectedId)
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.LeaderboardDismiss
        LOAD_WINDOW -> (action.payload[KEY_OFFSET] as? Int)?.let { Intent.LoadLeaderboardWindow(it) }
        OPEN_RATING_HISTORY -> (action.payload[KEY_HISTORY_ID] as? String)
            ?.let { Intent.OpenRatingHistory(RatingHistoryTarget.Player(it)) }

        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.LeaderboardDismiss
}
