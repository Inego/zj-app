package inego.zungjunggame.features.online

import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.ONLINE_CONNECT] modal with the runtime registry. */
object OnlineConnectFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.ONLINE_CONNECT)
        registry.register(
            routeKey = route.key,
            factory = { registered -> OnlineConnectComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = OnlineConnectActions,
        )
    }

    /** Registers the [RouteIds.ONLINE_CONNECT] modal with host-owned status seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> OnlineConnectOverlay,
        status: () -> OnlineConnectOverlay.Status = { OnlineConnectOverlay.Status.Connecting },
        latencyMs: () -> Int? = { null },
        snapshot: () -> PresenceSnapshot? = { null },
    ) {
        val route = Route.Modal(RouteIds.ONLINE_CONNECT)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                OnlineConnectComponent(registered, overlay(), status, latencyMs, snapshot)
            },
            spec = RouteSpecs.specFor(route),
            actions = OnlineConnectActions,
        )
    }
}

/**
 * Maps the connection card's buttons to [Intent]s. A press outside the buttons
 * is ignored: the card has no dismiss control, only explicit actions.
 */
object OnlineConnectActions : RouteActions {
    const val CANCEL = "onlineConnect.cancel"
    const val RETRY = "onlineConnect.retry"
    const val RETURN_HOME = "onlineConnect.returnHome"

    /**
     * Maps the control a completed press on the card resolved to its action, or
     * null when the release resolved the panel or nothing.
     */
    fun actionFor(result: OnlineConnectOverlay.PointerResult): ComponentAction? =
        idFor(result)?.let { ComponentAction(it) }

    /** Stable action id for one card button; null for panel/outside. */
    fun idFor(result: OnlineConnectOverlay.PointerResult): String? = when (result) {
        OnlineConnectOverlay.PointerResult.Cancel -> CANCEL
        OnlineConnectOverlay.PointerResult.Retry -> RETRY
        OnlineConnectOverlay.PointerResult.ReturnHome -> RETURN_HOME
        OnlineConnectOverlay.PointerResult.Panel -> null
        OnlineConnectOverlay.PointerResult.Outside -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        CANCEL -> Intent.OnlineConnectCancel
        RETRY -> Intent.OnlineConnectRetry
        RETURN_HOME -> Intent.OnlineReturnHome
        else -> null
    }
}
