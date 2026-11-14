package inego.zungjunggame.ui.stack

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout

/** Minimal GL-free [Component] for stack tests. */
class FakeComponent(override val id: ComponentId) : Component {
    override fun layout(frame: Frame): Layout = Layout()
    override fun draw(frame: Frame, canvas: Canvas) {}
}

/** Build a [RouteInstance] with a [LayerSpec] derived from [role] plus overrides. */
fun fakeInstance(
    route: Route,
    role: LayerRole = LayerRole.Screen,
    blocksAutomation: Boolean = false,
    dismissOnBack: Boolean = LayerSpec.defaultsFor(role).dismissOnBack,
    contextKind: SessionKind? = null,
    menuSurface: Boolean = false,
    onFelt: Boolean = LayerSpec.defaultsFor(role).onFelt,
    content: ContentPolicy = ContentPolicy.Normal,
): RouteInstance {
    val spec = LayerSpec.defaultsFor(role).copy(
        dismissOnBack = dismissOnBack,
        blocksAutomation = blocksAutomation,
        contextKind = contextKind,
        menuSurface = menuSurface,
        onFelt = onFelt,
        content = content,
    )
    return RouteInstance(route, FakeComponent(ComponentId(route.key.value)), spec)
}
