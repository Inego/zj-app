package inego.zungjunggame.features.away

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwayComponentTest {

    private val route = Route.Overlay(RouteIds.AWAY)
    private val panel = Rect(100f, 100f, 400f, 200f)
    private val resume = Rect(200f, 120f, 200f, 50f)

    private fun shown(): AwayOverlay =
        AwayOverlay().apply { seedLayoutForTest(panel = panel, resume = resume) }

    private fun component(overlay: AwayOverlay = AwayOverlay()): AwayComponent = AwayComponent(
        route = route,
        overlay = overlay,
        measure = { _, _ -> AwayGeometry(panel, resume) },
    )

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(Constraints(600f, 400f, 0f, 0f, 0f)) { emitted += it } to emitted
    }

    @Test
    fun layoutPublishesPanelAsTheGestureRegion() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun aResumePressReleaseEmitsResume() {
        val component = component(shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(resume.centerX, resume.centerY, frame)
        component.onGestureRelease(resume.centerX, resume.centerY, frame)

        assertEquals(listOf(ComponentAction(AwayActions.RESUME)), emitted)
    }

    @Test
    fun aPanelBodyReleaseEmitsNothing() {
        val component = component(shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(panel.centerX, panel.top - 1f, frame)
        component.onGestureRelease(panel.centerX, panel.top - 1f, frame)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun aDragFromResumeEmitsNothing() {
        val component = component(shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(resume.centerX, resume.centerY, frame)
        component.onGestureDrag(resume.centerX, resume.centerY + 40f, true, frame)
        component.onGestureRelease(resume.centerX, resume.centerY + 40f, frame)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun resumeMapsAndOutsideIsIgnored() {
        assertEquals(
            AwayActions.actionFor(AwayOverlay.PointerResult.Resume),
            ComponentAction(AwayActions.RESUME),
        )
        assertNull(AwayActions.actionFor(AwayOverlay.PointerResult.None))

        assertEquals(Intent.AwayResume, AwayActions.toIntent(route, ComponentAction(AwayActions.RESUME)))
        assertNull(AwayActions.toIntent(route, ComponentAction("away.other")))
        assertNull(AwayActions.outside())
    }

    @Test
    fun registerWiresAwayRoute() {
        val registry = FeatureRegistry()
        AwayFeature.register(registry)

        assertTrue(registry.create(route) is AwayComponent)
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        assertEquals(Intent.AwayResume, registry.actionToIntent(route, ComponentAction(AwayActions.RESUME)))
        assertNull(registry.outsideIntent(route))
    }
}
