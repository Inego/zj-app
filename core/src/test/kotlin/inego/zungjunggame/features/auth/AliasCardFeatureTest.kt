package inego.zungjunggame.features.auth

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import com.badlogic.gdx.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AliasCardFeatureTest {

    private val route = Route.Modal(RouteIds.FIRST_LOGIN_ALIAS)
    private val changeRoute = Route.Modal(RouteIds.CHANGE_DISPLAY_NAME)

    private val panel = Rect(50f, 50f, 300f, 300f)
    private val submit = Rect(180f, 80f, 130f, 48f)
    private val back = Rect(90f, 80f, 130f, 48f)
    private val field = Rect(80f, 180f, 240f, 44f)

    private fun component() = AliasCardComponent(
        route = route,
        measure = { _, _ ->
            AliasCardGeometry(
                panel = panel,
                submit = submit,
                back = back,
                field = field,
            )
        },
    )

    private fun constraints() = Constraints(400f, 440f, 0f, 0f, 0f)

    private fun shown(): AliasCardOverlay =
        AliasCardOverlay().apply { seedLayoutForTest(panel, submit, back) }

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(constraints()) { emitted += it } to emitted
    }

    @Test
    fun layoutPublishesPanelAsGestureAndNoHits() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun aSubmitPressReleaseEmitsSubmit() {
        val overlay = shown()
        val component = AliasCardComponent(route = route, overlay = overlay)
        val (frame, emitted) = recordingFrame()

        component.onGesturePress(submit.centerX, submit.centerY, frame)
        component.onGestureRelease(submit.centerX, submit.centerY, frame)

        assertEquals(listOf(ComponentAction(AliasCardActionIds.SUBMIT)), emitted)
        overlay.dispose()
    }

    @Test
    fun aBackPressReleaseEmitsBack() {
        val overlay = shown()
        val component = AliasCardComponent(route = route, overlay = overlay)
        val (frame, emitted) = recordingFrame()

        component.onGesturePress(back.centerX, back.centerY, frame)
        component.onGestureRelease(back.centerX, back.centerY, frame)

        assertEquals(listOf(ComponentAction(AliasCardActionIds.BACK)), emitted)
        overlay.dispose()
    }

    @Test
    fun aFieldPressEmitsNothing() {
        val overlay = shown()
        val component = AliasCardComponent(route = route, overlay = overlay)
        val (frame, emitted) = recordingFrame()

        component.onGesturePress(field.centerX, field.centerY, frame)
        component.onGestureRelease(field.centerX, field.centerY, frame)

        assertTrue(emitted.isEmpty())
        overlay.dispose()
    }

    @Test
    fun aDragFromSubmitEmitsNothing() {
        val overlay = shown()
        val component = AliasCardComponent(route = route, overlay = overlay)
        val (frame, emitted) = recordingFrame()

        component.onGesturePress(submit.centerX, submit.centerY, frame)
        component.onGestureDrag(submit.centerX, submit.centerY + 40f, true, frame)
        component.onGestureRelease(submit.centerX, submit.centerY + 40f, frame)

        assertTrue(emitted.isEmpty())
        overlay.dispose()
    }

    @Test
    fun enterEmitsSubmitFromTheCard() {
        val overlay = AliasCardOverlay()
        overlay.edit("user-k7m4p9q2")
        val component = AliasCardComponent(
            route = route,
            overlay = overlay,
            measure = { _, _ -> AliasCardGeometry(panel, submit, back, field) },
        )
        val (frame, emitted) = recordingFrame()

        assertTrue(component.onKeyDown(Input.Keys.ENTER, frame))
        assertEquals(listOf(ComponentAction(AliasCardActionIds.SUBMIT)), emitted)

        // A caret key belongs to the field and emits nothing.
        component.onKeyDown(Input.Keys.LEFT, frame)
        assertEquals(1, emitted.size)

        overlay.dispose()
    }

    @Test
    fun actionsMapEditControls() {
        assertEquals(
            ComponentAction(AliasCardActionIds.SUBMIT),
            AliasCardActionIds.actionFor(ModalVerdict(AliasCardOverlay.ROW_SUBMIT)),
        )
        assertEquals(
            ComponentAction(AliasCardActionIds.BACK),
            AliasCardActionIds.actionFor(ModalVerdict(AliasCardOverlay.ROW_BACK)),
        )
        assertNull(AliasCardActionIds.actionFor(ModalVerdict()))

        assertEquals(
            Intent.FirstLoginSubmit,
            FirstLoginAliasActions.toIntent(route, ComponentAction(AliasCardActionIds.SUBMIT)),
        )
        assertEquals(
            Intent.FirstLoginBack,
            FirstLoginAliasActions.toIntent(route, ComponentAction(AliasCardActionIds.BACK)),
        )
        assertNull(FirstLoginAliasActions.outside())

        assertEquals(
            Intent.SaveDisplayName,
            ChangeDisplayNameActions.toIntent(changeRoute, ComponentAction(AliasCardActionIds.SUBMIT)),
        )
        assertEquals(
            Intent.CancelDisplayName,
            ChangeDisplayNameActions.toIntent(changeRoute, ComponentAction(AliasCardActionIds.BACK)),
        )
        assertEquals(Intent.CancelDisplayName, ChangeDisplayNameActions.outside())
        assertEquals(Intent.CancelDisplayName, ChangeDisplayNameActions.back(changeRoute))
    }

    @Test
    fun registerWiresBothAliasRoutes() {
        val registry = FeatureRegistry()
        AliasCardFeature.register(registry, overlay = { AliasCardOverlay() })

        assertTrue(registry.create(route) is AliasCardComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.FirstLoginSubmit,
            registry.actionToIntent(route, ComponentAction(AliasCardActionIds.SUBMIT)),
        )

        assertTrue(registry.create(changeRoute) is AliasCardComponent)
        assertEquals(LayerRole.Modal, registry.specFor(changeRoute).role)
        assertEquals(
            Intent.SaveDisplayName,
            registry.actionToIntent(changeRoute, ComponentAction(AliasCardActionIds.SUBMIT)),
        )
    }
}
