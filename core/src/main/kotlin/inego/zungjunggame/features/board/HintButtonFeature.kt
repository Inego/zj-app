package inego.zungjunggame.features.board

import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.HINT_BUTTON] overlay with the runtime registry. */
object HintButtonFeature {
    fun register(registry: FeatureRegistry, overlay: () -> HintButtonOverlay) {
        val route = Route.Overlay(RouteIds.HINT_BUTTON)
        registry.register(
            routeKey = route.key,
            factory = { registered -> HintButtonComponent(registered, overlay) },
            spec = RouteSpecs.specFor(route),
            actions = HintButtonActions,
        )
    }

    /** Registers the [RouteIds.HINT_BUTTON] overlay with host-owned pose seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> HintButtonOverlay,
        renderInput: () -> HintButtonRenderInput,
    ) {
        val route = Route.Overlay(RouteIds.HINT_BUTTON)
        registry.register(
            routeKey = route.key,
            factory = { registered -> HintButtonComponent(registered, overlay, renderInput) },
            spec = RouteSpecs.specFor(route),
            actions = HintButtonActions,
        )
    }
}

/** Maps the button's tap to [Intent.Hint]; it is non-modal, so outside is ignored. */
object HintButtonActions : RouteActions {
    const val TAP = "hintButton.tap"

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        TAP -> Intent.Hint
        else -> null
    }
}
