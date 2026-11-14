package inego.zungjunggame.runtime

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.ComponentFactory
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.LayerSpecProvider
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteControllerTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private fun roleFor(route: Route): LayerRole = when (route) {
        is Route.Screen -> LayerRole.Screen
        is Route.Layer -> LayerRole.Context
        is Route.Modal -> LayerRole.Modal
        is Route.Overlay -> LayerRole.Overlay
        is Route.Tooltip -> LayerRole.Tooltip
        is Route.Reference -> LayerRole.Overlay
    }

    private val home = Route.Screen("home")
    private val confirm = Route.Modal("confirm")
    private val banner = Route.Overlay("banner")

    private fun controller(): RouteController {
        val components = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
        val specs = LayerSpecProvider { route -> LayerSpec.defaultsFor(roleFor(route)) }
        return RouteController(Router(home, components, specs))
    }

    @Test
    fun pushIfAbsentSkipsDuplicates() {
        val controller = controller()
        controller.pushIfAbsent(confirm)
        controller.pushIfAbsent(confirm)

        assertEquals(listOf(home.key, confirm.key), controller.stack.entries.map { it.route.key })
        assertEquals(confirm, controller.current)
    }

    @Test
    fun popIfPresentOnlyPopsTopMatch() {
        val controller = controller()
        controller.push(confirm)
        controller.push(banner)

        assertFalse(controller.popIfPresent(confirm.key))
        assertTrue(controller.popIfPresent(banner.key))
        assertTrue(controller.popIfPresent(confirm.key))
        assertFalse(controller.popIfPresent(confirm.key))
        assertEquals(home, controller.current)
    }
}
