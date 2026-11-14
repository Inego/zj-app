package inego.zungjunggame.features.away

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.AWAY] overlay with the runtime registry. */
object AwayFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Overlay(RouteIds.AWAY)
        registry.register(
            routeKey = route.key,
            factory = { registered -> AwayComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = AwayActions,
        )
    }

    /** Registers the [RouteIds.AWAY] overlay with a host-owned overlay. */
    fun register(registry: FeatureRegistry, overlay: () -> AwayOverlay) {
        val route = Route.Overlay(RouteIds.AWAY)
        registry.register(
            routeKey = route.key,
            factory = { registered -> AwayComponent(registered, overlay()) },
            spec = RouteSpecs.specFor(route),
            actions = AwayActions,
        )
    }
}

/**
 * Maps the resume control to [Intent.AwayResume].
 *
 * The overlay is blocking, so a press outside the card deliberately maps to no
 * intent (the legacy handler ignored it too).
 */
object AwayActions : RouteActions {
    const val RESUME = "away.resume"

    /**
     * Maps the control a completed press on the card resolved to its action, or
     * null when the release resolved nothing.
     */
    fun actionFor(verdict: AwayOverlay.PointerResult): ComponentAction? = when (verdict) {
        AwayOverlay.PointerResult.Resume -> ComponentAction(RESUME)
        AwayOverlay.PointerResult.None -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        RESUME -> Intent.AwayResume
        else -> null
    }

    override fun back(route: Route): Intent = Intent.AwayResume
}
