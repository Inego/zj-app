package inego.zungjunggame.input

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.HitRegions
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
import kotlin.test.assertNull
import kotlin.test.assertSame

class HitResolverTest {

    private val constraints = Constraints(100f, 100f, 0f, 0f, 0f)

    private class RegionComponent(
        override val id: ComponentId,
        private val content: Layout,
    ) : Component {
        override fun layout(frame: Frame): Layout = content
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private fun layoutWith(x: Float, y: Float, id: String): Layout {
        val regions = HitRegions<ComponentAction>()
        regions.add(Rect(x - 1f, y - 1f, 2f, 2f), ComponentAction(id))
        return Layout(hitRegions = regions)
    }

    private fun instance(route: Route, role: LayerRole, layout: Layout): RouteInstance =
        RouteInstance(route, RegionComponent(ComponentId(route.key.value), layout), LayerSpec.defaultsFor(role))

    private fun resolver(): HitResolver = HitResolver(
        FrameModel { instance ->
            val frame = testFrame(constraints)
            FrameModel.Entry(frame, instance.component, instance.component.layout(frame))
        },
    )

    @Test
    fun higherLayerWinsTopDown() {
        val screen = instance(Route.Screen("home"), LayerRole.Screen, layoutWith(5f, 5f, "screen"))
        val overlay = instance(Route.Overlay("banner"), LayerRole.Overlay, layoutWith(5f, 5f, "overlay"))
        val stack = Stack(listOf(screen, overlay))

        val on = resolver().resolve(stack, 5f, 5f) as ResolvedHit.On
        assertSame(overlay, on.instance)
        assertEquals("overlay", on.action.id)
    }

    @Test
    fun transparentNonOwningOverlayLetsLowerLayerReceiveHit() {
        val screen = instance(Route.Screen("home"), LayerRole.Screen, layoutWith(5f, 5f, "screen"))
        val overlay = instance(Route.Overlay("banner"), LayerRole.Overlay, Layout())
        val stack = Stack(listOf(screen, overlay))

        val on = resolver().resolve(stack, 5f, 5f) as ResolvedHit.On
        assertSame(screen, on.instance)
        assertEquals("screen", on.action.id)
    }

    @Test
    fun inputOwningLayerWithoutHitReturnsOutsideAndStops() {
        val screen = instance(Route.Screen("home"), LayerRole.Screen, layoutWith(5f, 5f, "screen"))
        val modal = instance(Route.Modal("confirm"), LayerRole.Modal, Layout())
        val stack = Stack(listOf(screen, modal))

        val outside = resolver().resolve(stack, 5f, 5f) as ResolvedHit.Outside
        assertSame(modal, outside.instance)
    }

    @Test
    fun nothingHitReturnsNull() {
        val screen = instance(Route.Screen("home"), LayerRole.Screen, layoutWith(5f, 5f, "screen"))
        val stack = Stack(listOf(screen))

        assertNull(resolver().resolve(stack, 50f, 50f))
    }
}
