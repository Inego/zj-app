package inego.zungjunggame.runtime

import inego.zungjunggame.input.Intent
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.ComponentFactory
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.LayerSpecProvider
import inego.zungjunggame.ui.stack.Route

/** Per-route component + layer spec + action→intent mapping. */
interface ComponentRegistry {
    fun create(route: Route): Component
    fun specFor(route: Route): LayerSpec
    fun actionToIntent(route: Route, action: ComponentAction): Intent?

    /** Intent for a press that landed inside an input-owning layer but missed its controls (e.g. dismiss). */
    fun outsideIntent(route: Route): Intent? = null

    /** Intent to apply when back targets this route; null when it opts out. */
    fun back(route: Route): Intent? = null
}

/** Adapts a [ComponentRegistry] to the factories used by Router. */
class RegistryComponentFactory(private val registry: ComponentRegistry) : ComponentFactory {
    override fun create(route: Route): Component = registry.create(route)
}

/** Adapts a [ComponentRegistry] to the factories used by Router. */
class RegistrySpecProvider(private val registry: ComponentRegistry) : LayerSpecProvider {
    override fun specFor(route: Route): LayerSpec = registry.specFor(route)
}
