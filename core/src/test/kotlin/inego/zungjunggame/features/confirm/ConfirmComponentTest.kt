package inego.zungjunggame.features.confirm

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmComponentTest {

    private val panel = Rect(10f, 20f, 100f, 80f)
    private val yes = Rect(20f, 30f, 30f, 20f)
    private val no = Rect(60f, 30f, 30f, 20f)

    private fun shown(): ConfirmOverlay =
        ConfirmOverlay().apply { seedLayoutForTest(panel = panel, yes = yes, no = no) }

    private fun component(
        route: Route = ConfirmPrompt.AbandonHand.route(),
        overlay: ConfirmOverlay = ConfirmOverlay(),
    ): ConfirmComponent =
        ConfirmComponent(
            route = route,
            overlay = overlay,
            measure = { _, _ -> ConfirmGeometry(panel, yes, no) },
        )

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(Constraints(200f, 200f, 0f, 0f, 0f)) { emitted += it } to emitted
    }

    @Test
    fun layoutPublishesPanelAndGestureRegion() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun pressOriginDecidesWhenYesAndNoOverlap() {
        val overlapping = Rect(20f, 30f, 30f, 20f)
        val component = component(
            overlay = ConfirmOverlay().apply {
                seedLayoutForTest(panel = panel, yes = yes, no = overlapping)
            },
        )
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        // The press begins inside both controls; the Yes control is the origin.
        component.onGesturePress(25f, 35f, frame)
        component.onGestureRelease(25f, 35f, frame)

        assertEquals(listOf(ComponentAction("confirm.yes")), emitted)
    }

    @Test
    fun aYesPressReleaseEmitsConfirmYes() {
        val component = component(overlay = shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(yes.centerX, yes.centerY, frame)
        component.onGestureRelease(yes.centerX, yes.centerY, frame)

        assertEquals(listOf(ComponentAction("confirm.yes")), emitted)
    }

    @Test
    fun aNoPressReleaseEmitsConfirmNo() {
        val component = component(overlay = shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(no.centerX, no.centerY, frame)
        component.onGestureRelease(no.centerX, no.centerY, frame)

        assertEquals(listOf(ComponentAction("confirm.no")), emitted)
    }

    @Test
    fun aPanelBodyReleaseEmitsNothing() {
        val component = component(overlay = shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(panel.centerX, panel.top - 1f, frame)
        component.onGestureRelease(panel.centerX, panel.top - 1f, frame)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun aDragFromYesEmitsNothing() {
        val component = component(overlay = shown())
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(yes.centerX, yes.centerY, frame)
        component.onGestureDrag(yes.centerX, yes.centerY + 40f, true, frame)
        component.onGestureRelease(yes.centerX, yes.centerY + 40f, frame)

        assertTrue(emitted.isEmpty())
    }

    /**
     * The question is the route's, so a card the stack restores asks the same thing
     * it asked before; a confirm route that names no question is not a card.
     */
    @Test
    fun questionComesFromTheRoute() {
        val prompt = component(ConfirmPrompt.NewGame.route()).prompt

        assertEquals(ConfirmPrompt.NewGame, prompt)
        assertEquals("confirm.new_game", prompt.titleKey)
        assertFailsWith<IllegalArgumentException> {
            component(Route.Modal(ConfirmPrompt.AbandonHand.route().id))
        }
    }

    @Test
    fun actionsMapYesNoAndOutside() {
        val route = ConfirmPrompt.Forfeit.route()

        assertEquals(
            ConfirmActions.actionFor(ConfirmOverlay.PointerResult.Confirm),
            ComponentAction("confirm.yes")
        )
        assertEquals(
            ConfirmActions.actionFor(ConfirmOverlay.PointerResult.Cancel),
            ComponentAction("confirm.no")
        )
        assertNull(ConfirmActions.actionFor(ConfirmOverlay.PointerResult.None))

        assertEquals(Intent.ConfirmYes, ConfirmActions.toIntent(route, ComponentAction("confirm.yes")))
        assertEquals(Intent.ConfirmNo, ConfirmActions.toIntent(route, ComponentAction("confirm.no")))
        assertNull(ConfirmActions.toIntent(route, ComponentAction("confirm.other")))
        assertEquals(Intent.ConfirmNo, ConfirmActions.outside())
    }

    @Test
    fun registerWiresConfirmRoute() {
        val registry = FeatureRegistry()
        ConfirmFeature.register(registry)
        val route = ConfirmPrompt.LogOut.route()

        assertTrue(registry.create(route) is ConfirmComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(Intent.ConfirmYes, registry.actionToIntent(route, ComponentAction("confirm.yes")))
        assertEquals(Intent.ConfirmNo, registry.outsideIntent(route))
    }
}
