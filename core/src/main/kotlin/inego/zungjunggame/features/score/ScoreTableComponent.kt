package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.Route

/**
 * The session score table as a stack component.
 *
 * Geometry comes from the wrapped [ScoreTableOverlay] through a [measure] seam
 * (GL-free); painting delegates to [ScoreTableOverlay.render]. The table and
 * human seat shown are supplied by the host. The card's gestures are the
 * [GestureBridge]'s: the overlay owns the press/drag/release machine, and
 * [ScoreTableActions.actionFor] maps the control a release resolved.
 *
 * The offset the player left the table at is the route's state, not the card's:
 * [draw] hands [state] in every frame (absence opens at the newest hand), and
 * every wheel, drag step, or settled drag emits a `routeUi.write` action, so the
 * store is the one owner.
 */
class ScoreTableComponent(
    route: Route,
    private val table: () -> SessionScoreTable,
    private val humanSeat: () -> Int,
    private val overlay: ScoreTableOverlay = ScoreTableOverlay(),
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> ScoreTableGeometry = { _, _ -> overlay.measureGeometry() },
    /**
     * The route's stored scroll offset. Absent (null) means the player never
     * scrolled this table, so the overlay keeps its own opening default — the
     * newest hand.
     */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: ScoreTableActions.actionFor(verdict)
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The table's own bounds: it owns the wheel over them and the drag that
        // scrolls them, and the overlay reports its close control on release, so no
        // hit region duplicates either.
        val panel = geometry.panel
        return Layout(panel = panel, gestureRegions = listOfNotNull(panel), scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            canvas.batch,
            camera(),
            table(),
            humanSeat(),
            c.worldWidth,
            c.worldHeight,
            // The player's stored offset; absence opens at the newest hand.
            scrollY = state.read(),
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }
}
