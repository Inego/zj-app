package inego.zungjunggame.ui.stack

import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.Scope

/**
 * A route paired with its component, its [LayerSpec], and its entry [scope].
 *
 * The component is the presenter the host drives per phase; it reads the frame's
 * model-derived snapshot from [inego.zungjunggame.ui.Frame.model] rather than a
 * view the host binds to it. The stack is heterogeneous because the host holds
 * [Component] and nothing narrower.
 *
 * The spec is the declared one with the entry's resolved policy: a [Route.Layer]
 * pushed with a policy of its own carries it here, so paint order, persistence,
 * and restore all read the layer's policy from one place.
 *
 * The scope is the entry's lifetime: a resource a component registers there is
 * released exactly when the entry leaves the stack. [Router] builds one per
 * entry as a child of its own scope; a test fixture may supply its own (the
 * default is a parentless scope so hand-built instances stay legal).
 */
class RouteInstance(
    val route: Route,
    val component: Component,
    val spec: LayerSpec,
    val scope: Scope = Scope.root("entry:${route.key.value}"),
)
