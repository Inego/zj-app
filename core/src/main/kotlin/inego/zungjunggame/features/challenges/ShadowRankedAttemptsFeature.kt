package inego.zungjunggame.features.challenges

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
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.SHADOW_RANKED_ATTEMPTS] card with the runtime registry. */
object ShadowRankedAttemptsFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS)
        registry.register(
            routeKey = route.key,
            factory = { registered -> ShadowRankedAttemptsComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = ShadowRankedAttemptsActions,
        )
    }

    /** Registers the card with a host-owned overlay and model seam. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> ShadowRankedAttemptsOverlay,
        renderInput: () -> ShadowRankedAttemptsRenderInput = { ShadowRankedAttemptsRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ShadowRankedAttemptsComponent(
                    route = registered,
                    overlay = overlay(),
                    renderInput = renderInput,
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = ShadowRankedAttemptsActions,
        )
    }
}

/**
 * Maps the attempts card's controls to intents.
 */
object ShadowRankedAttemptsActions : RouteActions {
    const val DISMISS = "shadowRankedAttempts.dismiss"
    const val LOAD_MORE = "shadowRankedAttempts.loadMore"
    const val OPEN_HANDS = "shadowRankedAttempts.openHands"
    const val PUBLIC_ID = "publicId"

    /** Maps the control a completed press on the card resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? = when {
        verdict.close -> ComponentAction(DISMISS)
        verdict.selectedId == inego.zungjunggame.net.ShadowRankedAttemptsClient.LOAD_MORE_ID -> ComponentAction(LOAD_MORE)
        verdict.selectedId != null ->
            ComponentAction(OPEN_HANDS, mapOf(PUBLIC_ID to verdict.selectedId))
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        LOAD_MORE -> Intent.LoadMoreShadowRankedAttempts
        OPEN_HANDS -> (action.payload[PUBLIC_ID] as? String)?.let { Intent.OpenShadowRankedHands(it) }
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.ShadowRankedAttemptsDismiss
}
