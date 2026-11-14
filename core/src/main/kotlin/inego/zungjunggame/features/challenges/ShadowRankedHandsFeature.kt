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

/** Registers the [RouteIds.SHADOW_RANKED_HANDS] card with the runtime registry. */
object ShadowRankedHandsFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.SHADOW_RANKED_HANDS)
        registry.register(
            routeKey = route.key,
            factory = { registered -> ShadowRankedHandsComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = ShadowRankedHandsActions,
        )
    }

    /** Registers the card with a host-owned overlay and model seam. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> ShadowRankedHandsOverlay,
        renderInput: () -> ShadowRankedHandsRenderInput = { ShadowRankedHandsRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.SHADOW_RANKED_HANDS)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ShadowRankedHandsComponent(
                    route = registered,
                    overlay = overlay(),
                    renderInput = renderInput,
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = ShadowRankedHandsActions,
        )
    }
}

/**
 * Maps the hand-tables card's controls to intents: the close control, the Who
 * arrows and dropdown, and the Who row a release selected.
 */
object ShadowRankedHandsActions : RouteActions {
    const val DISMISS = "shadowRankedHands.dismiss"
    const val STEP_PREV = "shadowRankedHands.stepPrev"
    const val STEP_NEXT = "shadowRankedHands.stepNext"
    const val TOGGLE_DROPDOWN = "shadowRankedHands.toggleDropdown"
    const val SELECT_ATTEMPT = "shadowRankedHands.selectAttempt"
    const val ATTEMPT_INDEX = "index"

    /** Maps the control a completed press on the card resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? = when {
        verdict.close -> ComponentAction(DISMISS)
        else -> when (verdict.selectedId) {
            ShadowRankedHandsControls.PREV -> ComponentAction(STEP_PREV)
            ShadowRankedHandsControls.NEXT -> ComponentAction(STEP_NEXT)
            ShadowRankedHandsControls.WHO -> ComponentAction(TOGGLE_DROPDOWN)
            else -> ShadowRankedHandsControls.attemptIndex(verdict.selectedId)?.let { index ->
                ComponentAction(SELECT_ATTEMPT, mapOf(ATTEMPT_INDEX to index))
            }
        }
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        STEP_PREV -> Intent.ShadowRankedHandsStep(-1)
        STEP_NEXT -> Intent.ShadowRankedHandsStep(1)
        TOGGLE_DROPDOWN -> Intent.ToggleShadowRankedHandsDropdown
        SELECT_ATTEMPT -> (action.payload[ATTEMPT_INDEX] as? Int)
            ?.let { Intent.SelectShadowRankedHandsAttempt(it) }
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.ShadowRankedHandsDismiss
}
