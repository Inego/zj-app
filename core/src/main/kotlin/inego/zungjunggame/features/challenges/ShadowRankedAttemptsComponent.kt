package inego.zungjunggame.features.challenges

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.net.ChallengeEntry
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
 * Everything [ShadowRankedAttemptsOverlay.render] needs beyond the viewport: the
 * signed-in account's attempts and their load status. A provider seam so the
 * component stays GL-free until a host supplies the model.
 */
data class ShadowRankedAttemptsRenderInput(
    val attempts: List<ChallengeEntry> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    val hasMore: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

/**
 * The Shadow Ranked attempts card as a stack component.
 *
 * Geometry comes from the wrapped [ShadowRankedAttemptsOverlay] through a [measure]
 * seam (GL-free); painting delegates to [ShadowRankedAttemptsOverlay.render]. The
 * card's gestures are the [GestureBridge]'s: the overlay owns the press/drag/release
 * machine, and [ShadowRankedAttemptsActions.actionFor] maps the control a release
 * resolved.
 *
 * The offset the player left the list at is the route's state, exactly as match
 * history keeps its own.
 */
class ShadowRankedAttemptsComponent(
    route: Route,
    private val overlay: ShadowRankedAttemptsOverlay = ShadowRankedAttemptsOverlay(),
    private val renderInput: () -> ShadowRankedAttemptsRenderInput = { ShadowRankedAttemptsRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> ShadowRankedAttemptsGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored scroll offset; 0 when it keeps none (the list's top). */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: ShadowRankedAttemptsActions.actionFor(verdict)
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
        // attempt rows on release, so no hit region duplicates them.
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
            attempts = input.attempts,
            loading = input.loading,
            loadingMore = input.loadingMore,
            error = input.error,
            loadMoreError = input.loadMoreError,
            hasMore = input.hasMore,
            nowMs = input.nowMs,
        )
    }
}
