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
 * The account card as a stack component.
 *
 * **The account controller owns the identity.** The component reads the signed-in
 * label through [displayName] every layout and hands it to the wrapped
 * [AccountOverlay]; it keeps no copy and writes nothing. The overlay owns the
 * press → drag → release machine and reports the control its release resolved;
 * this component publishes the card as the gesture region and bridges that
 * verdict to a `ComponentAction` via [AccountActions.actionFor] — it publishes no
 * hit regions, so no control travels as a payload.
 *
 * Geometry comes from the overlay via a [measure] seam (GL-free); painting
 * delegates to [AccountOverlay.render].
 */
class AccountComponent(
    route: Route,
    private val overlay: AccountOverlay = AccountOverlay(),
    /** The signed-in label for this frame, read from the account model. */
    private val displayName: () -> String = { "" },
    private val measure: (Float, Float) -> AccountGeometry = { _, _ -> overlay.measureGeometry() },
) : GestureBridge<AccountOverlay.PointerResult>(
    surface = { overlay },
    actionFor = AccountActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        // The measured panel and the painted name are one value.
        overlay.setDisplayName(displayName())
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
