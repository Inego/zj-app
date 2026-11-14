package inego.zungjunggame.features.auth

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

class StartingLevelFeatureTest {

    private val route = Route.Modal(RouteIds.STARTING_LEVEL)

    private val panel = Rect(40f, 40f, 320f, 360f)
    private val novice = Rect(60f, 260f, 280f, 46f)
    private val amateur = Rect(60f, 200f, 280f, 46f)
    private val strong = Rect(60f, 140f, 280f, 46f)

    private fun component(overlay: StartingLevelOverlay = StartingLevelOverlay()) =
        StartingLevelComponent(
            route = route,
            overlay = overlay,
            measure = { _, _ -> StartingLevelGeometry(panel, novice, amateur, strong) },
        )

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(Constraints(400f, 440f, 0f, 0f, 0f)) { emitted += it } to emitted
    }

    @Test
    fun layoutPublishesPanelAsTheGestureRegion() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun seededPressReleasePicksEachLevel() {
        for ((rect, action) in listOf(
            novice to ComponentAction(StartingLevelActions.NOVICE),
            amateur to ComponentAction(StartingLevelActions.AMATEUR),
            strong to ComponentAction(StartingLevelActions.STRONG),
        )) {
            val overlay = StartingLevelOverlay().apply {
                seedLayoutForTest(panel, novice, amateur, strong)
            }
            val component = component(overlay)
            val (frame, emitted) = recordingFrame()
            component.layout(frame)

            assertTrue(component.onGesturePress(rect.centerX, rect.centerY, frame))
            assertTrue(component.onGestureRelease(rect.centerX, rect.centerY, frame))
            assertEquals(listOf(action), emitted)
        }
    }

    @Test
    fun busyCardClaimsThePressButPicksNothing() {
        val overlay = StartingLevelOverlay().apply {
            seedLayoutForTest(panel, novice, amateur, strong, busy = true)
        }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        assertTrue(component.onGesturePress(novice.centerX, novice.centerY, frame))
        assertTrue(component.onGestureRelease(novice.centerX, novice.centerY, frame))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun actionsMapEachLevel() {
        assertEquals(
            StartingLevelActions.actionFor(StartingLevelOverlay.PointerResult.Novice),
            ComponentAction(StartingLevelActions.NOVICE),
        )
        assertEquals(
            StartingLevelActions.actionFor(StartingLevelOverlay.PointerResult.Amateur),
            ComponentAction(StartingLevelActions.AMATEUR),
        )
        assertEquals(
            StartingLevelActions.actionFor(StartingLevelOverlay.PointerResult.Strong),
            ComponentAction(StartingLevelActions.STRONG),
        )
        assertNull(StartingLevelActions.actionFor(StartingLevelOverlay.PointerResult.None))

        assertEquals(
            Intent.StartingLevelPick("novice"),
            StartingLevelActions.toIntent(route, ComponentAction(StartingLevelActions.NOVICE)),
        )
        assertEquals(
            Intent.StartingLevelPick("amateur"),
            StartingLevelActions.toIntent(route, ComponentAction(StartingLevelActions.AMATEUR)),
        )
        assertEquals(
            Intent.StartingLevelPick("strong"),
            StartingLevelActions.toIntent(route, ComponentAction(StartingLevelActions.STRONG)),
        )
        assertNull(StartingLevelActions.outside())
    }

    @Test
    fun registerWiresStartingLevelRoute() {
        val registry = FeatureRegistry()
        StartingLevelFeature.register(registry)

        assertTrue(registry.create(route) is StartingLevelComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.StartingLevelPick("strong"),
            registry.actionToIntent(route, ComponentAction(StartingLevelActions.STRONG)),
        )
    }
}
