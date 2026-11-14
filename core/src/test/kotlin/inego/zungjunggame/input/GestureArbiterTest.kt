package inego.zungjunggame.input

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureHost
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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behavioral spec for the new-pipeline pointer arbitration; covers the cases
 * that are expressible against [Stack] / [Layout].
 */
class GestureArbiterTest {

    private val constraints = Constraints(1000f, 1000f, 0f, 0f, 0f)

    private class FakeHost(
        override val id: ComponentId,
        private val content: Layout,
        private val accept: Boolean = true,
        var releaseConsumed: Boolean = false,
    ) : Component, GestureHost {
        var presses = 0
        var drags = 0
        var cancels = 0
        var lastDragging: Boolean? = null
        var lastPressX = Float.NaN
        var lastPressY = Float.NaN
        var lastDragX = Float.NaN
        var lastDragY = Float.NaN
        var lastReleaseX = Float.NaN
        var lastReleaseY = Float.NaN
        var pressFrame: Frame? = null
        var dragFrame: Frame? = null
        var releaseFrame: Frame? = null

        override fun layout(frame: Frame): Layout = content
        override fun draw(frame: Frame, canvas: Canvas) {}
        override fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean {
            presses++
            lastPressX = x
            lastPressY = y
            pressFrame = frame
            return accept
        }
        override fun onGestureDrag(x: Float, y: Float, dragging: Boolean, frame: Frame) {
            drags++
            lastDragging = dragging
            lastDragX = x
            lastDragY = y
            dragFrame = frame
        }
        override fun onGestureRelease(x: Float, y: Float, frame: Frame): Boolean {
            lastReleaseX = x
            lastReleaseY = y
            releaseFrame = frame
            return releaseConsumed
        }
        override fun onGestureCancel(frame: Frame) {
            cancels++
        }
    }

    private class PlainComponent(override val id: ComponentId, private val content: Layout) : Component {
        override fun layout(frame: Frame): Layout = content
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private class RecordingSink : GestureArbiter.Sink {
        val taps = mutableListOf<Triple<Float, Float, Boolean>>()
        val suppressed = mutableListOf<Boolean>()
        override fun tap(x: Float, y: Float, touchStartedOnTarget: Boolean, suppressMenuOpen: Boolean) {
            taps += Triple(x, y, touchStartedOnTarget)
            suppressed += suppressMenuOpen
        }
    }

    private fun regions(vararg rects: Rect): HitRegions<ComponentAction> {
        val result = HitRegions<ComponentAction>()
        rects.forEachIndexed { index, rect -> result.add(rect, ComponentAction("c$index")) }
        return result
    }

    private fun instance(route: Route, component: Component, role: LayerRole): RouteInstance =
        RouteInstance(route, component, LayerSpec.defaultsFor(role))

    private fun framesOf(vararg instances: RouteInstance): FrameModel =
        FrameModel { instance ->
            val frame = testFrame(constraints)
            FrameModel.Entry(frame, instance.component, instance.component.layout(frame))
        }.also { frames -> instances.forEach(frames::layoutFor) }

    private fun down(x: Float, y: Float) = InputEvent.PointerDown(x, y)
    private fun move(x: Float, y: Float) = InputEvent.PointerMove(x, y)
    private fun up(x: Float, y: Float) = InputEvent.PointerUp(x, y)

    @Test
    fun tapWithinSlopOnControlDispatchesStartedOnTarget() {
        val layout = Layout(hitRegions = regions(Rect(10f, 10f, 10f, 10f)))
        val screen = instance(Route.Screen("home"), PlainComponent(ComponentId("home"), layout), LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()
        val arbiter = GestureArbiter()

        arbiter.handle(down(15f, 15f), stack, frames, sink)
        arbiter.handle(up(15f, 15f), stack, frames, sink)

        assertEquals(listOf(Triple(15f, 15f, true)), sink.taps)
        assertEquals(listOf(false), sink.suppressed)
    }

    @Test
    fun fingerRollWithinSlopIsStillATap() {
        val layout = Layout(hitRegions = regions(Rect(10f, 10f, 10f, 10f)))
        val screen = instance(Route.Screen("home"), PlainComponent(ComponentId("home"), layout), LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(15f, 15f), stack, frames, sink)
            handle(move(17f, 18f), stack, frames, sink)
            handle(up(17f, 18f), stack, frames, sink)
        }

        assertEquals(1, sink.taps.size)
        assertEquals(listOf(false), sink.suppressed)
    }

    @Test
    fun swipePastSlopSuppressesTheMenuTapButKeepsControlAction() {
        val layout = Layout(hitRegions = regions(Rect(10f, 10f, 10f, 10f)))
        val screen = instance(Route.Screen("home"), PlainComponent(ComponentId("home"), layout), LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(15f, 15f), stack, frames, sink)
            handle(move(200f, 200f), stack, frames, sink)
            handle(up(200f, 200f), stack, frames, sink)
        }

        assertEquals(1, sink.taps.size)
        assertEquals(listOf(true), sink.suppressed)
    }

    @Test
    fun liftFarFromPressIsASwipeEvenWithNoMoveSamples() {
        val layout = Layout()
        val screen = instance(Route.Screen("home"), PlainComponent(ComponentId("home"), layout), LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(612f, 20f), stack, frames, sink)
            handle(up(612f, 700f), stack, frames, sink)
        }

        assertEquals(listOf(true), sink.suppressed)
    }

    @Test
    fun multiFingerReleaseMarksTapNonMenu() {        val layout = Layout()
        val screen = instance(Route.Screen("home"), PlainComponent(ComponentId("home"), layout), LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(InputEvent.PointerDown(5f, 5f, pointerCount = 1), stack, frames, sink)
            handle(InputEvent.PointerMove(5f, 5f, pointerCount = 3), stack, frames, sink)
            handle(up(5f, 5f), stack, frames, sink)
        }

        assertEquals(listOf(true), sink.suppressed)
    }

    @Test
    fun touchCancelAbortsOwnerAndSwallowsRemainingTouch() {
        val layout = Layout(gestureRegions = listOf(Rect(0f, 0f, 100f, 100f)))
        val host = FakeHost(ComponentId("board"), layout)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()
        val arbiter = GestureArbiter()

        arbiter.handle(down(20f, 20f), stack, frames, sink)
        assertEquals(1, host.presses)

        arbiter.handle(InputEvent.PointerCancel(stillTouched = true), stack, frames, sink)
        assertEquals(1, host.cancels)

        arbiter.handle(down(30f, 30f), stack, frames, sink)
        arbiter.handle(move(40f, 40f), stack, frames, sink)
        arbiter.handle(up(40f, 40f), stack, frames, sink)
        assertEquals(1, host.presses, "the leftover press must be swallowed")
        assertTrue(sink.taps.isEmpty())

        arbiter.handle(down(50f, 50f), stack, frames, sink)
        assertEquals(2, host.presses, "a fresh touch after the swallow recovers")
    }

    @Test
    fun cancellationWithoutLiveTouchDoesNotSwallowNextPress() {
        val layout = Layout(gestureRegions = listOf(Rect(0f, 0f, 100f, 100f)))
        val host = FakeHost(ComponentId("board"), layout)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()
        val arbiter = GestureArbiter()

        arbiter.handle(down(20f, 20f), stack, frames, sink)
        arbiter.handle(InputEvent.PointerCancel(stillTouched = false), stack, frames, sink)
        arbiter.handle(down(30f, 30f), stack, frames, sink)

        assertEquals(2, host.presses)
    }

    @Test
    fun parkDragMovesTheBlockAndConsumesRelease() {
        val layout = Layout(gestureRegions = listOf(Rect(200f, 100f, 400f, 100f)))
        val host = FakeHost(ComponentId("board"), layout, releaseConsumed = true)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(400f, 150f), stack, frames, sink)
            handle(move(460f, 150f), stack, frames, sink)
            handle(up(460f, 150f), stack, frames, sink)
        }

        assertEquals(1, host.presses)
        assertEquals(true, host.lastDragging)
        assertEquals(0, sink.taps.size, "a park drag must not also act on the board")
    }

    @Test
    fun parkPressThatNeverMovesFallsBackToTap() {
        val layout = Layout(gestureRegions = listOf(Rect(200f, 100f, 400f, 100f)))
        val host = FakeHost(ComponentId("board"), layout, releaseConsumed = false)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(400f, 150f), stack, frames, sink)
            handle(move(402f, 151f), stack, frames, sink)
            handle(up(402f, 151f), stack, frames, sink)
        }

        assertEquals(1, sink.taps.size)
        assertEquals(true, sink.taps.single().third, "the tap started on a control")
        assertEquals(false, host.lastDragging)
    }

    @Test
    fun inputOwningLayerWithoutHostStopsTheHostSearch() {
        val boardLayout = Layout(gestureRegions = listOf(Rect(0f, 0f, 200f, 200f)))
        val board = FakeHost(ComponentId("board"), boardLayout)
        val screen = instance(Route.Screen("board"), board, LayerRole.Screen)
        val modal = instance(Route.Modal("confirm"), PlainComponent(ComponentId("confirm"), Layout()), LayerRole.Modal)
        val stack = Stack(listOf(screen, modal))
        val frames = framesOf(screen, modal)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(50f, 50f), stack, frames, sink)
            handle(up(50f, 50f), stack, frames, sink)
        }

        assertEquals(0, board.presses, "the modal above must keep the press")
        assertEquals(1, sink.taps.size)
    }

    @Test
    fun decliningHostLetsLowerLayerAcquire() {
        val topLayout = Layout(gestureRegions = listOf(Rect(0f, 0f, 200f, 200f)))
        val top = FakeHost(ComponentId("banner"), topLayout, accept = false)
        val bottomLayout = Layout(gestureRegions = listOf(Rect(0f, 0f, 200f, 200f)))
        val bottom = FakeHost(ComponentId("board"), bottomLayout)
        val screen = instance(Route.Screen("board"), bottom, LayerRole.Screen)
        val overlay = instance(Route.Overlay("banner"), top, LayerRole.Overlay)
        val stack = Stack(listOf(screen, overlay))
        val frames = framesOf(screen, overlay)

        GestureArbiter().handle(down(50f, 50f), stack, frames, RecordingSink())

        assertEquals(1, top.presses)
        assertEquals(1, bottom.presses)
    }

    @Test
    fun scrollableHostAcquiresDrag() {
        val layout = Layout(panel = Rect(0f, 0f, 500f, 500f), scrollable = true)
        val host = FakeHost(ComponentId("lobby"), layout)
        val screen = instance(Route.Screen("lobby"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(100f, 400f), stack, frames, sink)
            handle(move(100f, 300f), stack, frames, sink)
            handle(up(100f, 300f), stack, frames, sink)
        }

        assertEquals(1, host.presses)
        assertEquals(1, host.drags)
    }

    @Test
    fun scopedHostReceivesDragAndReleaseInItsLocalSpace() {
        // A layer laid out inside a parent scope (world minus panel origin at
        // 100,50). The press, drag, and release must all use that local space,
        // or the first move reads as a jump the size of the origin and turns a
        // tap into a park drag.
        val layout = Layout(
            worldToLocal = { x, y ->
                if (x in 100f..600f && y in 50f..650f) (x - 100f) to (y - 50f) else null
            },
            gestureRegions = listOf(Rect(50f, 50f, 200f, 100f)),
        )
        val host = FakeHost(ComponentId("board"), layout, releaseConsumed = true)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(200f, 100f), stack, frames, sink)
            handle(move(230f, 130f), stack, frames, sink)
            handle(up(230f, 130f), stack, frames, sink)
        }

        assertEquals(100f, host.lastPressX)
        assertEquals(50f, host.lastPressY)
        assertEquals(130f, host.lastDragX)
        assertEquals(80f, host.lastDragY)
        assertEquals(130f, host.lastReleaseX)
        assertEquals(80f, host.lastReleaseY)
        assertEquals(0, sink.taps.size)
    }

    @Test
    fun theOwningEntrysFrameTravelsFromPressToRelease() {
        val layout = Layout(gestureRegions = listOf(Rect(0f, 0f, 100f, 100f)))
        val host = FakeHost(ComponentId("board"), layout)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()

        GestureArbiter().apply {
            handle(down(20f, 20f), stack, frames, sink)
            handle(move(60f, 60f), stack, frames, sink)
            handle(up(60f, 60f), stack, frames, sink)
        }

        val frame = requireNotNull(host.pressFrame)
        assertSame(frame, host.dragFrame)
        assertSame(frame, host.releaseFrame)
    }

    @Test
    fun publicCancelDropsGestureWithoutSwallowing() {
        val layout = Layout(gestureRegions = listOf(Rect(0f, 0f, 100f, 100f)))
        val host = FakeHost(ComponentId("board"), layout)
        val screen = instance(Route.Screen("home"), host, LayerRole.Screen)
        val stack = Stack(listOf(screen))
        val frames = framesOf(screen)
        val sink = RecordingSink()
        val arbiter = GestureArbiter()

        arbiter.handle(down(20f, 20f), stack, frames, sink)
        arbiter.cancel()
        assertFalse(arbiter.active)
        assertEquals(1, host.cancels)

        arbiter.handle(down(20f, 20f), stack, frames, sink)
        assertEquals(2, host.presses)
    }
}
