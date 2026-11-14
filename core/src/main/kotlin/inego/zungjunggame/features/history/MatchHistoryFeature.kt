package inego.zungjunggame.features.history

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.MatchHistoryClient
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.RouteScrollCodec
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.MATCH_HISTORY] modal with the runtime registry. */
object MatchHistoryFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.MATCH_HISTORY)
        registry.register(
            routeKey = route.key,
            factory = { registered -> MatchHistoryComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = MatchHistoryActions,
        )
    }

    /** Registers the [RouteIds.MATCH_HISTORY] modal with host-owned overlay and list seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> MatchHistoryOverlay,
        renderInput: () -> MatchHistoryRenderInput = { MatchHistoryRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.MATCH_HISTORY)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                MatchHistoryComponent(
                    route = registered,
                    overlay = overlay(),
                    renderInput = renderInput,
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = MatchHistoryActions,
        )
    }
}

/** Maps the close control to [Intent.Back] and load-more to [Intent.LoadMoreMatchHistory]. */
object MatchHistoryActions : RouteActions {
    const val DISMISS = "matchHistory.dismiss"
    const val LOAD_MORE = "matchHistory.loadMore"

    /** Maps the control a completed press on the card resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? = when {
        verdict.close -> ComponentAction(DISMISS)
        verdict.selectedId == MatchHistoryClient.LOAD_MORE_ID -> ComponentAction(LOAD_MORE)
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        LOAD_MORE -> Intent.LoadMoreMatchHistory
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.MatchHistoryDismiss
}
