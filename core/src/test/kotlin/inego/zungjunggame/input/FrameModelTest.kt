package inego.zungjunggame.input

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FrameModelTest {

    private class CountingComponent(override val id: ComponentId) : Component {
        var layouts: Int = 0
            private set

        override fun layout(frame: Frame): Layout {
            layouts++
            return Layout()
        }

        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private fun instance(route: Route, component: Component): RouteInstance =
        RouteInstance(route, component, LayerSpec.defaultsFor(roleFor(route)))

    private fun roleFor(route: Route): LayerRole = when (route) {
        is Route.Screen -> LayerRole.Screen
        is Route.Layer -> LayerRole.Context
        is Route.Modal -> LayerRole.Modal
        is Route.Overlay -> LayerRole.Overlay
        is Route.Tooltip -> LayerRole.Tooltip
        is Route.Reference -> LayerRole.Screen
    }

    private fun model(builds: () -> Unit): FrameModel =
        FrameModel { instance ->
            builds()
            val frame = testFrame()
            FrameModel.Entry(frame, instance.component, instance.component.layout(frame))
        }

    @Test
    fun cachesFrameAndLayoutPerRouteAcrossCalls() {
        val component = CountingComponent(ComponentId("screen"))
        val route = instance(Route.Screen("home"), component)
        var builds = 0
        val frames = model(builds = { builds++ })

        val frame = frames.frameFor(route)
        val layout = frames.layoutFor(route)

        assertSame(frame, frames.frameFor(route), "layout and draw must share one frame")
        assertSame(layout, frames.layoutFor(route))
        assertEquals(1, builds)
        assertEquals(1, component.layouts)
    }

    @Test
    fun freshFrameModelRecomputes() {
        val component = CountingComponent(ComponentId("screen"))
        val route = instance(Route.Screen("home"), component)
        var builds = 0

        model { builds++ }.layoutFor(route)
        model { builds++ }.layoutFor(route)

        assertEquals(2, builds)
        assertEquals(2, component.layouts)
    }

    @Test
    fun clearForcesRecompute() {
        val component = CountingComponent(ComponentId("screen"))
        val route = instance(Route.Screen("home"), component)
        var builds = 0
        val frames = model(builds = { builds++ })

        frames.layoutFor(route)
        frames.clear()
        frames.layoutFor(route)

        assertEquals(2, builds)
    }
}
