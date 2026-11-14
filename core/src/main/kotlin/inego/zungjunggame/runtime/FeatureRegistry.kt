package inego.zungjunggame.runtime

import inego.zungjunggame.input.Intent
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey

/** Per-route intents for a feature's controls, outside-press, and back behavior. */
interface RouteActions {
    fun toIntent(route: Route, action: ComponentAction): Intent?

    /** Intent for a press inside the layer that missed every control; null to ignore. */
    fun outside(): Intent? = null

    /**
     * The app's system-back intent for this route; null when the route declares
     * none. Independent of [LayerSpec.dismissOnBack] — the generic stack dismissal
     * [inego.zungjunggame.ui.stack.Router.popDismissible] — see there.
     */
    fun back(route: Route): Intent? = null
}

/**
 * Route-keyed [ComponentRegistry]: the sole lookup table for component factories,
 * layer specs, and action mapping.
 *
 * A route declares its presenter factory; the component reads the frame's model
 * itself through [inego.zungjunggame.ui.Frame.model], so the registry holds no
 * projection and the runtime can build any presenter without seeing its view type.
 *
 * Registering a key twice or looking up an unregistered route fails fast, which
 * replaces the old exhaustive `when` with a single-update-site guarantee.
 */
class FeatureRegistry : ComponentRegistry {
    private class Entry(
        val factory: (Route) -> Component,
        val spec: LayerSpec,
        val actions: RouteActions,
    )

    private val entries = LinkedHashMap<RouteKey, Entry>()

    /** Declare one route and its presenter factory. */
    fun register(
        routeKey: RouteKey,
        factory: (Route) -> Component,
        spec: LayerSpec,
        actions: RouteActions,
    ) {
        require(routeKey !in entries) { "Route already registered: ${routeKey.value}" }
        entries[routeKey] = Entry(factory = factory, spec = spec, actions = actions)
    }

    override fun create(route: Route): Component = entryFor(route).factory(route)

    override fun specFor(route: Route): LayerSpec = entryFor(route).spec

    /**
     * The shared route-state write first: the route's own feature never sees it,
     * so a stateful surface does not need an intent family per feature.
     */
    override fun actionToIntent(route: Route, action: ComponentAction): Intent? {
        if (action.id == RouteUiActions.WRITE) {
            RouteUiActions.valueOf(action)?.let {
                // The route's spec carries retention; an unregistered route (a widget
                // driven without a full registry) simply does not remember.
                val remember = entries[route.key]?.spec?.remembersState == true
                return Intent.WriteRouteUi(route.key.value, it, remember)
            }
        }
        return entryFor(route).actions.toIntent(route, action)
    }

    override fun outsideIntent(route: Route): Intent? = entryFor(route).actions.outside()

    override fun back(route: Route): Intent? = entryFor(route).actions.back(route)

    private fun entryFor(route: Route): Entry = entries[route.key]
        ?: throw IllegalArgumentException("No registered feature for route: ${route.key.value}")
}
