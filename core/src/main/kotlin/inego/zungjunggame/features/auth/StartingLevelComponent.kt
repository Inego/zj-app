package inego.zungjunggame.features.auth

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * The starting-level picker as a stack component.
 *
 * The wrapped [StartingLevelOverlay] owns the press → drag → release state
 * machine and reports the level its release resolved; this component publishes
 * the card as the gesture region and bridges that verdict to a `ComponentAction`
 * via [StartingLevelActions.actionFor] — it publishes no hit regions, so no
 * control travels as a payload. Geometry comes from the overlay via a [measure]
 * seam (GL-free); painting delegates to [StartingLevelOverlay.render].
 */
class StartingLevelComponent(
    route: Route,
    private val overlay: StartingLevelOverlay = StartingLevelOverlay(),
    private val measure: (Float, Float) -> StartingLevelGeometry = { _, _ ->
        overlay.measureGeometry()
    },
) : GestureBridge<StartingLevelOverlay.PointerResult>(
    surface = { overlay },
    actionFor = StartingLevelActions::actionFor,
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
            batch = canvas.batch,
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }
}
