package inego.zungjunggame.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureBridgeTest {

    private val a = ComponentAction("a")
    private val b = ComponentAction("b")

    private class FakeSurface(
        private val release: String = "release",
        private val dragVerdict: String? = null,
        private val scrollConsumed: Boolean = false,
        private val settledOffset: Float? = null,
        private val press: String? = null,
        private val claimsPress: Boolean = true,
    ) : GestureSurface<String> {
        override fun onPointerDown(x: Float, y: Float): Boolean = claimsPress
        override fun pressVerdict(): String? = press
        override fun onPointerDrag(x: Float, y: Float): String? = dragVerdict
        override fun onPointerUp(x: Float, y: Float): String = release
        override fun onScroll(amountY: Float, x: Float, y: Float): Boolean = scrollConsumed
        override fun scrollOffset(): Float? = settledOffset
    }

    private class Recorder {
        val emitted = mutableListOf<ComponentAction>()
        val frame = testFrame(emit = { emitted += it })
    }

    private class TestBridge(
        surface: GestureSurface<String>,
        actionFor: (String) -> ComponentAction? = { null },
        consumeFor: (String) -> Boolean = { true },
        scrollActionFor: (Float) -> ComponentAction? = { null },
        stateActionFor: (String) -> ComponentAction? = { null },
    ) : GestureBridge<String>({ surface }, actionFor, consumeFor, scrollActionFor, stateActionFor)

    @Test
    fun releaseThatMapsToAnActionEmitsAndConsumes() {
        val recorder = Recorder()
        val bridge = TestBridge(FakeSurface(release = "go"), actionFor = { if (it == "go") a else null })

        assertTrue(bridge.onGestureRelease(0f, 0f, recorder.frame))
        assertEquals(listOf(a), recorder.emitted)
    }

    @Test
    fun releaseThatMapsToNothingStillConsumes() {
        val recorder = Recorder()
        val bridge = TestBridge(FakeSurface(release = "ignored"), actionFor = { if (it == "go") a else null })

        assertTrue(bridge.onGestureRelease(0f, 0f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }

    @Test
    fun consumeForVetoesAReleaseAndEmitsNothing() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(release = "keep"),
            actionFor = { if (it == "consumed") a else null },
            consumeFor = { it == "consumed" },
        )

        assertFalse(bridge.onGestureRelease(0f, 0f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }

    @Test
    fun theVerdictsStateActionIsEmittedBeforeItsFeatureAction() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(release = "both"),
            actionFor = { if (it == "both") b else null },
            stateActionFor = { if (it == "both") a else null },
        )

        bridge.onGestureRelease(0f, 0f, recorder.frame)

        assertEquals(listOf(a, b), recorder.emitted)
    }

    @Test
    fun pressVerdictEmitsThroughActionFor() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(press = "pressed"),
            actionFor = { if (it == "pressed") a else null },
        )

        assertTrue(bridge.onGesturePress(1f, 2f, recorder.frame))
        assertEquals(listOf(a), recorder.emitted)
    }

    @Test
    fun nullPressVerdictEmitsNothing() {
        val recorder = Recorder()
        val bridge = TestBridge(FakeSurface(press = null), actionFor = { a })

        assertTrue(bridge.onGesturePress(1f, 2f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }

    @Test
    fun declinedPressEmitsNothing() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(press = "pressed", claimsPress = false),
            actionFor = { a },
        )

        assertFalse(bridge.onGesturePress(1f, 2f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }

    @Test
    fun dragVerdictEmitsThroughActionFor() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(dragVerdict = "moved"),
            actionFor = { if (it == "moved") b else null },
        )

        bridge.onGestureDrag(1f, 2f, dragging = true, recorder.frame)
        assertEquals(listOf(b), recorder.emitted)
    }

    @Test
    fun unconsumedScrollEmitsNothing() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(scrollConsumed = false, settledOffset = 42f),
            scrollActionFor = { b },
        )

        assertFalse(bridge.onWheel(0f, 0f, 0f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }

    @Test
    fun consumedScrollWithoutOffsetEmitsNothing() {
        val recorder = Recorder()
        val bridge = TestBridge(
            FakeSurface(scrollConsumed = true, settledOffset = null),
            scrollActionFor = { b },
        )

        assertTrue(bridge.onWheel(0f, 0f, 0f, recorder.frame))
        assertTrue(recorder.emitted.isEmpty())
    }
}
