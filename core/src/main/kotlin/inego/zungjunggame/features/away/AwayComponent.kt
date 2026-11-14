package inego.zungjunggame.features.away

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * The away-during-online-match overlay as a stack component.
 *
 * The wrapped [AwayOverlay] owns the press → drag → release state machine and
 * reports the control its release resolved; this component publishes the card as
 * the gesture region and bridges that verdict to a `ComponentAction` via
 * [AwayActions.actionFor] — it publishes no hit regions, so no control travels as
 * a payload. Painting delegates to the overlay, so the pixels are unchanged.
 */
class AwayComponent(
    route: Route,
    private val overlay: AwayOverlay = AwayOverlay(),
    private val measure: (Float, Float) -> AwayGeometry = { _, _ -> overlay.measureGeometry() },
) : GestureBridge<AwayOverlay.PointerResult>(
    surface = { overlay },
    actionFor = AwayActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            canvas.batch,
            c.worldWidth,
            c.worldHeight,
            c.pointerX,
            c.pointerY,
        )
    }
}
