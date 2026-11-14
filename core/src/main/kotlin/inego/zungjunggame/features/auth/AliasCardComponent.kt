package inego.zungjunggame.features.auth

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.TextFieldHost
import inego.zungjunggame.ui.stack.Route

/**
 * The shared alias card as a stack component.
 *
 * Geometry comes from the wrapped [AliasCardOverlay] via a [measure] seam,
 * so [layout] is GL-free and headless tests inject a fake. The initial name
 * shown is supplied by a seam; painting delegates to
 * [AliasCardOverlay.render].
 *
 * The card backs both the first-login prompt and the account display-name editor
 * ([RouteIds.CHANGE_DISPLAY_NAME]): it publishes the same field/save/cancel
 * controls and each route's actions map them. The overlay owns the press machine
 * (field caret/selection plus the Save/Cancel taps) and reports the row its release
 * resolved; [AliasCardActionIds.actionFor] maps that neutral verdict, so no control
 * travels as a hit region.
 */
class AliasCardComponent(
    route: Route,
    private val overlay: AliasCardOverlay = AliasCardOverlay(),
    private val measure: (Float, Float) -> AliasCardGeometry = { _, _ ->
        overlay.measureGeometry()
    },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = AliasCardActionIds::actionFor,
),
    Component,
    TextFieldHost {
    override val id: ComponentId = ComponentId(route.key.value)

    override val textFieldFocused: Boolean get() = overlay.hasFocusedTextField()

    /**
     * Enter is the keyboard form of the Save control: it emits the same
     * [AliasCardActionIds.SUBMIT] action the hit region does, so the route's
     * action table decides what it means (first-login submit vs. save name).
     */
    override fun onKeyDown(keycode: Int, frame: Frame): Boolean {
        if (overlay.isSubmitKey(keycode)) {
            frame.emit(ComponentAction(AliasCardActionIds.SUBMIT))
            return true
        }
        return overlay.keyDown(keycode)
    }

    override fun onKeyTyped(character: Char): Boolean = overlay.keyTyped(character)

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The whole card is the gesture surface: field caret/selection and the
        // Save/Cancel taps are the overlay's press machine, so no control is a hit
        // region.
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
