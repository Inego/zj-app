package inego.zungjunggame.features.auth

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.STARTING_LEVEL] modal with the runtime registry. */
object StartingLevelFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.STARTING_LEVEL)
        registry.register(
            routeKey = route.key,
            factory = { registered -> StartingLevelComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = StartingLevelActions,
        )
    }

    /** Registers the [RouteIds.STARTING_LEVEL] modal with a host-owned overlay. */
    fun register(registry: FeatureRegistry, overlay: () -> StartingLevelOverlay) {
        val route = Route.Modal(RouteIds.STARTING_LEVEL)
        registry.register(
            routeKey = route.key,
            factory = { registered -> StartingLevelComponent(registered, overlay()) },
            spec = RouteSpecs.specFor(route),
            actions = StartingLevelActions,
        )
    }
}

/** Maps each level row to its [Intent.StartingLevelPick]. */
object StartingLevelActions : RouteActions {
    const val NOVICE = "startingLevel.novice"
    const val AMATEUR = "startingLevel.amateur"
    const val STRONG = "startingLevel.strong"

    /**
     * Maps the level a completed press on the card resolved to its action, or
     * null when the release resolved nothing.
     */
    fun actionFor(verdict: StartingLevelOverlay.PointerResult): ComponentAction? = when (verdict) {
        StartingLevelOverlay.PointerResult.Novice -> ComponentAction(NOVICE)
        StartingLevelOverlay.PointerResult.Amateur -> ComponentAction(AMATEUR)
        StartingLevelOverlay.PointerResult.Strong -> ComponentAction(STRONG)
        StartingLevelOverlay.PointerResult.None -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        NOVICE -> Intent.StartingLevelPick("novice")
        AMATEUR -> Intent.StartingLevelPick("amateur")
        STRONG -> Intent.StartingLevelPick("strong")
        else -> null
    }
}
