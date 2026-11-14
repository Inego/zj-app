package inego.zungjunggame.features.online

import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * The online-table connection card as a stack component.
 *
 * The wrapped [OnlineConnectOverlay] owns the press → drag → release state
 * machine and reports the control its release resolved; this component publishes
 * the card as the gesture region and bridges that verdict to a `ComponentAction`
 * via [OnlineConnectActions.actionFor] — it publishes no hit regions, so no
 * control travels as a payload. Geometry comes from the overlay via a [measure]
 * seam (GL-free); the live status and presence lines are supplied by seams.
 *
 * **Status is published in [layout].** The status chooses the card's buttons, so
 * it is set before [measure] runs and [draw] only paints the geometry layout
 * published; a status published in `draw` could disagree with the gesture region
 * the frame laid out.
 */
class OnlineConnectComponent(
    route: Route,
    private val overlay: OnlineConnectOverlay = OnlineConnectOverlay(),
    private val status: () -> OnlineConnectOverlay.Status = {
        OnlineConnectOverlay.Status.Connecting
    },
    private val latencyMs: () -> Int? = { null },
    private val snapshot: () -> PresenceSnapshot? = { null },
    private val measure: (Float, Float) -> OnlineConnectGeometry = { _, _ -> overlay.measureGeometry() },
) : GestureBridge<OnlineConnectOverlay.PointerResult>(
    surface = { overlay },
    actionFor = OnlineConnectActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        // The card is a long-lived route while the connection state changes, so
        // the current status is handed in each frame — before the geometry is
        // measured, because the status chooses the buttons.
        overlay.setStatus(status())
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            latencyMs = latencyMs(),
            snapshot = snapshot(),
        )
    }
}
