package inego.zungjunggame.input

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureHost
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScrollRouterTest {

    private val constraints = Constraints(1000f, 1000f, 0f, 0f, 0f)

    private class WheelHost(
        override val id: ComponentId,
        private val content: Layout,
        private val consumes: Boolean = true,
    ) : Component, GestureHost {
        val amounts = mutableListOf<Float>()
        var lastFrame: Frame? = null
            private set
        override fun layout(frame: Frame): Layout = content
        override fun draw(frame: Frame, canvas: Canvas) {}
        override fun onWheel(amount: Float, x: Float, y: Float, frame: Frame): Boolean {
            amounts += amount
            lastFrame = frame
            return consumes
        }
    }

    private class PlainComponent(override val id: ComponentId, private val content: Layout) : Component {
        override fun layout(frame: Frame): Layout = content
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private fun instance(route: Route, component: Component, role: LayerRole): RouteInstance =
        RouteInstance(route, component, LayerSpec.defaultsFor(role))

    private fun framesOf(vararg instances: RouteInstance): FrameModel =
        FrameModel { instance ->
            val frame = testFrame(constraints)
            FrameModel.Entry(frame, instance.component, instance.component.layout(frame))
        }.also { frames -> instances.forEach(frames::layoutFor) }

    @Test
    fun topScrollableLayerConsumesWheel() {
        val host = WheelHost(ComponentId("list"), Layout(panel = Rect(0f, 0f, 500f, 500f), scrollable = true))
        val screen = instance(Route.Screen("list"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)

        assertTrue(ScrollRouter.route(stack, frames, -3f, 100f, 100f, 0f))
        assertEquals(listOf(-3f), host.amounts)
        assertSame(frames.frameFor(screen), host.lastFrame, "the wheel rides the entry's own frame")
    }

    @Test
    fun inputOwningLayerSwallowsWheelBeforeLowerScroll() {
        val host = WheelHost(ComponentId("list"), Layout(panel = Rect(0f, 0f, 500f, 500f), scrollable = true))
        val screen = instance(Route.Screen("list"), host, LayerRole.Screen)
        val modal = instance(Route.Modal("confirm"), PlainComponent(ComponentId("confirm"), Layout()), LayerRole.Modal)
        val stack = Stack(listOf(screen, modal))
        val frames = framesOf(screen, modal)

        assertTrue(ScrollRouter.route(stack, frames, -3f, 100f, 100f, 0f))
        assertTrue(host.amounts.isEmpty(), "the modal must swallow the wheel")
    }

    @Test
    fun transparentOverlayLetsLowerScrollReceiveWheel() {
        val host = WheelHost(ComponentId("list"), Layout(panel = Rect(0f, 0f, 500f, 500f), scrollable = true))
        val screen = instance(Route.Screen("list"), host, LayerRole.Screen)
        val overlay = instance(Route.Overlay("banner"), PlainComponent(ComponentId("banner"), Layout()), LayerRole.Overlay)
        val stack = Stack(listOf(screen, overlay))
        val frames = framesOf(screen, overlay)

        assertTrue(ScrollRouter.route(stack, frames, 2f, 100f, 100f, 0f))
        assertEquals(listOf(2f), host.amounts)
    }

    @Test
    fun pointerOutsideScrollPanelDoesNotConsume() {
        val host = WheelHost(ComponentId("list"), Layout(panel = Rect(0f, 0f, 100f, 100f), scrollable = true))
        val screen = instance(Route.Screen("list"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)

        assertFalse(ScrollRouter.route(stack, frames, 2f, 900f, 900f, 0f))
        assertTrue(host.amounts.isEmpty())
    }
}
