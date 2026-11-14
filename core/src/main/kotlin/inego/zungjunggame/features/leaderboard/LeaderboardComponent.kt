package inego.zungjunggame.features.leaderboard

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.net.LeaderboardReading
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
 * Everything the leaderboard card needs beyond the viewport: the pinned reading
 * (its generation, total, viewer row, and fetched windows) and the load flags.
 * Plain data, so a host builds it from [inego.zungjunggame.net.LeaderboardClient]
 * and the overlay renders from it.
 */
data class LeaderboardRenderInput(
    val reading: LeaderboardReading? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The leaderboard card as a stack component.
 *
 * Geometry comes from the wrapped [LeaderboardOverlay] through a `measure` seam
 * (GL-free); painting delegates to [LeaderboardOverlay.render]. The card's
 * gestures are the [GestureBridge]'s: the overlay owns the press/drag/release
 * machine, and [LeaderboardActions.actionFor] maps the control a release resolved.
 *
 * The offset the player left the list at is the route's state: every frame the
 * store's value is handed to the overlay, and every wheel, drag step, or settled
 * drag emits a `routeUi.write`; [motion] coasts a fling through that same channel.
 * **Top** and **Me** resolve a scroll offset in the overlay, so they travel that
 * same route-state channel rather than a global intent.
 *
 * [motion] also owns the fetch trigger: the overlay reports the window the viewport
 * has reached but the reading does not hold, and the component emits
 * [LeaderboardActions.loadWindow] for it. The effect fetches that window of the
 * pinned generation; the widget never fetches.
 */
class LeaderboardComponent(
    route: Route,
    private val overlay: LeaderboardOverlay = LeaderboardOverlay(),
    private val renderInput: () -> LeaderboardRenderInput = { LeaderboardRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> LeaderboardGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored scroll offset; 0 when it keeps none (the list's top). */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: LeaderboardActions.actionFor(verdict)
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after != before && after != state.read()) {
            frame.emit(RouteUiActions.scroll(after))
        }
        // The viewport may have reached a window the reading does not hold (a
        // scroll, a fling, or a jump to a far self). Ask for it unless a fetch is
        // already in flight; the client ignores a window it already holds.
        val input = renderInput()
        if (!input.loading) {
            overlay.missingWindowOffset()?.let { offset ->
                frame.emit(LeaderboardActions.loadWindow(offset))
            }
        }
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The card's own bounds: it owns the wheel over them and the drag that
        // scrolls them, and the overlay reports its close, Top, and Me controls on
        // release, so no hit region duplicates them.
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
            reading = input.reading,
            loading = input.loading,
            error = input.error,
        )
    }
}
