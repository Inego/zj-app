package inego.zungjunggame.runtime

import inego.zungjunggame.input.FrameModel
import inego.zungjunggame.input.HitResolver
import inego.zungjunggame.input.ResolvedHit
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Headless parity: paint/input order and flags derived from a real [RouteSpecs]-spec'd stack. */
class StackDerivationTest {

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

    private fun instance(route: Route, layout: Layout = Layout()): RouteInstance =
        RouteInstance(route, RegionComponent(ComponentId(route.key.value), layout), RouteSpecs.specFor(route))

    private fun root(): RouteInstance = instance(Route.Screen(RouteIds.ROOT_MENU))

    private fun board(layout: Layout = Layout()): RouteInstance =
        instance(Route.Layer(RouteIds.LOCAL_BOARD), layout)

    private fun resolver(): HitResolver = HitResolver(
        FrameModel { instance ->
            val frame = testFrame(constraints)
            FrameModel.Entry(frame, instance.component, instance.component.layout(frame))
        },
    )

    @Test
    fun entriesArePaintOrderBottomToTopAndReverseIsInputOrder() {
        val paintOrder = listOf(
            Route.Screen(RouteIds.ROOT_MENU).key,
            Route.Layer(RouteIds.LOCAL_BOARD).key,
            Route.Modal(RouteIds.GAME_MENU).key,
            Route.Modal(RouteIds.CONFIRM).key,
        )
        val stack = Stack(
            listOf(
                root(),
                board(),
                instance(Route.Modal(RouteIds.GAME_MENU)),
                instance(Route.Modal(RouteIds.CONFIRM)),
            ),
        )

        assertEquals(paintOrder, stack.entries.map { it.route.key })
        assertEquals(paintOrder.reversed(), stack.entries.asReversed().map { it.route.key })
    }

    @Test
    fun routeSpecsFlagsAggregateOverStack() {
        val blocking = Stack(
            listOf(
                root(),
                board(),
                instance(Route.Modal(RouteIds.CONFIRM)),
            ),
        )
        assertTrue(blocking.anyBlocksAutomation)
        assertFalse(blocking.onFelt, "the board supersedes the fundamental overlay")

        val passThroughOverlay = Stack(
            listOf(
                root(),
                instance(Route.Overlay(RouteIds.SCORE_BREAKDOWN)),
            ),
        )
        assertFalse(passThroughOverlay.anyBlocksAutomation)
        assertTrue(passThroughOverlay.onFelt)
    }

    @Test
    fun modalOwnsInputAndConsumesPointFromTheLayerBelow() {
        val stack = Stack(
            listOf(
                root(),
                board(layoutWith(5f, 5f, "board")),
                instance(Route.Modal(RouteIds.GAME_MENU)),
            ),
        )

        val outside = resolver().resolve(stack, 5f, 5f) as ResolvedHit.Outside
        assertSame(stack.top, outside.instance)
    }

    @Test
    fun topmostOwningLayerWinsTopDown() {
        val away = instance(Route.Overlay(RouteIds.AWAY), layoutWith(5f, 5f, "away"))
        val stack = Stack(
            listOf(
                root(),
                board(layoutWith(5f, 5f, "board")),
                away,
            ),
        )

        val on = resolver().resolve(stack, 5f, 5f) as ResolvedHit.On
        assertSame(away, on.instance)
        assertEquals("away", on.action.id)
    }
}
