package inego.zungjunggame.features.online

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

class OnlineConnectFeatureTest {

    private val route = Route.Modal(RouteIds.ONLINE_CONNECT)

    private val panel = Rect(50f, 50f, 300f, 300f)
    private val retry = Rect(100f, 120f, 200f, 48f)
    private val returnHome = Rect(100f, 60f, 200f, 48f)
    private val cancel = Rect(100f, 90f, 200f, 48f)

    private val reconnectButtons = listOf(
        OnlineConnectButtonGeometry(retry, OnlineConnectOverlay.PointerResult.Retry),
        OnlineConnectButtonGeometry(returnHome, OnlineConnectOverlay.PointerResult.ReturnHome),
    )

    private fun component() = OnlineConnectComponent(
        route = route,
        measure = { _, _ -> OnlineConnectGeometry(panel, reconnectButtons) },
    )

    private fun constraints() = Constraints(400f, 400f, 0f, 0f, 0f)

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(constraints()) { emitted += it } to emitted
    }

    /** Seed the real overlay layout for [status] and release on [rect]. */
    private fun releaseEmits(
        status: OnlineConnectOverlay.Status,
        buttons: List<OnlineConnectButtonGeometry>,
        rect: Rect,
    ): List<ComponentAction> {
        val overlay = OnlineConnectOverlay().apply {
            seedLayoutForTest(status = status, panel = panel, buttons = buttons)
        }
        val component = OnlineConnectComponent(
            route = route,
            overlay = overlay,
            measure = { _, _ -> OnlineConnectGeometry(panel, buttons) },
        )
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        assertTrue(component.onGesturePress(rect.centerX, rect.centerY, frame))
        assertTrue(component.onGestureRelease(rect.centerX, rect.centerY, frame))
        return emitted
    }

    @Test
    fun layoutPublishesPanelAsTheGestureRegion() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun seededPressReleaseFollowsTheStatusFamily() {
        assertEquals(
            listOf(ComponentAction(OnlineConnectActions.RETRY)),
            releaseEmits(OnlineConnectOverlay.Status.ReconnectRequired, reconnectButtons, retry),
        )
        assertEquals(
            listOf(ComponentAction(OnlineConnectActions.RETURN_HOME)),
            releaseEmits(OnlineConnectOverlay.Status.ReconnectRequired, reconnectButtons, returnHome),
        )
        assertEquals(
            listOf(ComponentAction(OnlineConnectActions.RETURN_HOME)),
            releaseEmits(
                OnlineConnectOverlay.Status.TableGone,
                listOf(OnlineConnectButtonGeometry(returnHome, OnlineConnectOverlay.PointerResult.ReturnHome)),
                returnHome,
            ),
        )
        assertEquals(
            listOf(ComponentAction(OnlineConnectActions.CANCEL)),
            releaseEmits(
                OnlineConnectOverlay.Status.Connecting,
                listOf(OnlineConnectButtonGeometry(cancel, OnlineConnectOverlay.PointerResult.Cancel)),
                cancel,
            ),
        )
    }

    @Test
    fun panelBodyReleaseEmitsNothing() {
        val overlay = OnlineConnectOverlay().apply {
            seedLayoutForTest(OnlineConnectOverlay.Status.ReconnectRequired, panel, reconnectButtons)
        }
        val component = OnlineConnectComponent(
            route = route,
            overlay = overlay,
            measure = { _, _ -> OnlineConnectGeometry(panel, reconnectButtons) },
        )
        val (frame, emitted) = recordingFrame()
        component.layout(frame)

        component.onGesturePress(panel.centerX, panel.top - 1f, frame)
        component.onGestureRelease(panel.centerX, panel.top - 1f, frame)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun actionsMapButtons() {
        assertEquals(
            OnlineConnectActions.actionFor(OnlineConnectOverlay.PointerResult.Retry),
            ComponentAction(OnlineConnectActions.RETRY),
        )
        assertEquals(
            OnlineConnectActions.actionFor(OnlineConnectOverlay.PointerResult.ReturnHome),
            ComponentAction(OnlineConnectActions.RETURN_HOME),
        )
        assertNull(OnlineConnectActions.actionFor(OnlineConnectOverlay.PointerResult.Panel))
        assertNull(OnlineConnectActions.actionFor(OnlineConnectOverlay.PointerResult.Outside))

        assertEquals(
            Intent.OnlineConnectCancel,
            OnlineConnectActions.toIntent(route, ComponentAction(OnlineConnectActions.CANCEL)),
        )
        assertEquals(
            Intent.OnlineConnectRetry,
            OnlineConnectActions.toIntent(route, ComponentAction(OnlineConnectActions.RETRY)),
        )
        assertEquals(
            Intent.OnlineReturnHome,
            OnlineConnectActions.toIntent(route, ComponentAction(OnlineConnectActions.RETURN_HOME)),
        )
        assertNull(OnlineConnectActions.toIntent(route, ComponentAction("unknown")))
    }

    @Test
    fun panelAndOutsideOwnNoAction() {
        assertNull(OnlineConnectActions.idFor(OnlineConnectOverlay.PointerResult.Panel))
        assertNull(OnlineConnectActions.idFor(OnlineConnectOverlay.PointerResult.Outside))
        assertNull(OnlineConnectActions.outside())
    }

    @Test
    fun registerWiresOnlineConnectRoute() {
        val registry = FeatureRegistry()
        OnlineConnectFeature.register(registry)

        assertTrue(registry.create(route) is OnlineConnectComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.OnlineConnectCancel,
            registry.actionToIntent(route, ComponentAction(OnlineConnectActions.CANCEL)),
        )
    }
}
