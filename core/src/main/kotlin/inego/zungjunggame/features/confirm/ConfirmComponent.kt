package inego.zungjunggame.features.confirm

import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * Yes/no confirm dialog as a stack component.
 *
 * The wrapped [ConfirmOverlay] owns the press → drag → release state machine and
 * reports the control its release resolved; this component publishes the card as
 * the gesture region and bridges that verdict to a `ComponentAction` via
 * [ConfirmActions.actionFor] — it publishes no hit regions, so no control travels
 * as a payload. Painting delegates to the overlay, so the pixels are unchanged.
 * The question is the route's, so both a just-pushed and a restored card render
 * the same pixels from the same entry.
 */
class ConfirmComponent(
    private val route: Route,
    private val overlay: ConfirmOverlay = ConfirmOverlay(),
    private val measure: (Float, Float) -> ConfirmGeometry = { w, h -> overlay.measure(w, h) },
) : GestureBridge<ConfirmOverlay.PointerResult>(
    surface = { overlay },
    actionFor = ConfirmActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /**
     * The question this card asks, from the route that is its layer.
     *
     * A confirm route that names none is not a card — its `Yes` would answer a
     * question nothing asked — so it fails here, where [ConfirmPrompt.route] and
     * this line are the only two sides of that arg.
     */
    val prompt: ConfirmPrompt = requireNotNull(ConfirmPrompt.of(route)) {
        "confirm route carries no question: ${route.args}"
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        // The route's question is what the card asks; handing it in before the
        // measurement keeps the measured panel and the painted title one value.
        overlay.setQuestion(prompt.titleKey)
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val constraints = frame.constraints
        overlay.render(
            canvas.batch,
            constraints.worldWidth,
            constraints.worldHeight,
            constraints.pointerX,
            constraints.pointerY,
        )
    }
}
