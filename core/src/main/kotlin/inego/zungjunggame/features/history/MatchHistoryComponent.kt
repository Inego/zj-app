package inego.zungjunggame.features.history

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.net.MatchSummary
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
 * Everything the legacy [MatchHistoryOverlay.render] needs beyond the viewport.
 * A provider seam so the component stays GL-free until a host supplies the
 * paginated match list and its loading flags.
 */
data class MatchHistoryRenderInput(
    val matches: List<MatchSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

/**
 * Paginated online match history as a stack component.
 *
 * Geometry comes from the wrapped [MatchHistoryOverlay] through a [measure]
 * seam (GL-free); painting delegates to [MatchHistoryOverlay.render]. The card's
 * gestures are the [GestureBridge]'s: the overlay owns the press/drag/release
 * machine, and [MatchHistoryActions.actionFor] maps the control a release resolved.
 *
 * The offset the player left the list at is the route's state: every frame the
 * store's value is handed to [MatchHistoryOverlay.render], and every wheel,
 * drag step, or settled drag emits a `routeUi.write`; [motion] coasts a fling
 * through that same channel.
 */
class MatchHistoryComponent(
    route: Route,
    private val overlay: MatchHistoryOverlay = MatchHistoryOverlay(),
    private val renderInput: () -> MatchHistoryRenderInput = { MatchHistoryRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> MatchHistoryGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored scroll offset; 0 when it keeps none (the list's top). */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: MatchHistoryActions.actionFor(verdict)
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        if (after == state.read()) return
        frame.emit(RouteUiActions.scroll(after))
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The card's own bounds: it owns the wheel over them and the drag that
        // scrolls them, and the overlay reports both its close control and its
        // load-more row on release, so no hit region duplicates them.
        val panel = geometry.panel
        return Layout(panel = panel, gestureRegions = listOfNotNull(panel), scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val input = renderInput()
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = state.read() ?: 0f,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            matches = input.matches,
            loading = input.loading,
            error = input.error,
            hasMore = input.hasMore,
            nowMs = input.nowMs,
        )
    }
}
