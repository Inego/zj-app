package inego.zungjunggame.features.confirm

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey

/** Registers the [RouteIds.CONFIRM] modal with the runtime registry. */
object ConfirmFeature {
    fun register(registry: FeatureRegistry) = register(registry) { ConfirmOverlay() }

    /** Registers the confirm modal over a host-owned overlay so one instance paints it. */
    fun register(registry: FeatureRegistry, overlay: () -> ConfirmOverlay) {
        registry.register(
            routeKey = RouteKey("modal:${RouteIds.CONFIRM}"),
            factory = { route -> ConfirmComponent(route, overlay()) },
            spec = RouteSpecs.specFor(Route.Modal(RouteIds.CONFIRM)),
            actions = ConfirmActions,
        )
    }
}

/** Maps the dialog's controls and outside press to confirm intents. */
object ConfirmActions : RouteActions {
    /**
     * Maps the control a completed press on the card resolved to its action, or
     * null when the release resolved nothing.
     */
    fun actionFor(verdict: ConfirmOverlay.PointerResult): ComponentAction? = when (verdict) {
        ConfirmOverlay.PointerResult.Confirm -> ComponentAction("confirm.yes")
        ConfirmOverlay.PointerResult.Cancel -> ComponentAction("confirm.no")
        ConfirmOverlay.PointerResult.None -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        "confirm.yes" -> Intent.ConfirmYes
        "confirm.no" -> Intent.ConfirmNo
        else -> null
    }

    override fun outside(): Intent = Intent.ConfirmNo

    override fun back(route: Route): Intent = Intent.ConfirmNo
}
