package inego.zungjunggame.features.ratinghistory

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.RATING_HISTORY] modal with the runtime registry. */
object RatingHistoryFeature {
    /** Registers [RouteIds.RATING_HISTORY] with host-owned overlay and route-state seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> RatingHistoryOverlay = { RatingHistoryOverlay() },
        history: () -> RatingHistoryClient = defaultRatingHistory(),
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.RATING_HISTORY)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                RatingHistoryComponent(
                    route = registered,
                    overlay = overlay(),
                    history = history,
                    camera = camera,
                    state = routeUi.port(registered.key, RatingHistoryCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = RatingHistoryActions,
        )
    }
}

/**
 * Maps the rating-history surface's close control and system back to
 * [Intent.RatingHistoryDismiss]. The chart's own gestures **and its explicit
 * viewport controls** are handled by [RatingHistoryComponent] and write the route's
 * window through [inego.zungjunggame.ui.RouteUiActions], so they need no entry in
 * this table.
 */
object RatingHistoryActions : RouteActions {
    const val DISMISS = "ratingHistory.dismiss"

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.RatingHistoryDismiss
        else -> null
    }

    override fun back(route: Route): Intent = Intent.RatingHistoryDismiss
}
