package inego.zungjunggame.features.notices

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.NoticeEntry
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
 * Everything [NoticesOverlay.render] needs beyond the viewport, supplied by the
 * host so the component stays GL-free until a frame is painted.
 *
 * The same shape serves the list and the popup: the popup supplies one notice and
 * its own title.
 */
data class NoticesRenderInput(
    val titleKey: String = "notice.title",
    val notices: List<NoticeEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Read clock, so a notice's "when" is judged against the frame that draws it. */
    val nowMs: Long = System.currentTimeMillis(),
)

/**
 * The notices inbox as a stack card.
 *
 * Geometry comes from the wrapped [NoticesOverlay] through a [measure] seam
 * (GL-free); painting delegates to [NoticesOverlay.render]. The body is the shared
 * rich-text view, so a click on a link never resolves here.
 *
 * The offset the player left the body at is the route's state: every frame the
 * store's value is handed to the overlay, and every wheel, drag step, or settled
 * drag emits a `routeUi.write`; [motion] coasts a fling through the same channel.
 */
class NoticesComponent(
    route: Route,
    private val overlay: NoticesOverlay = NoticesOverlay(),
    private val renderInput: () -> NoticesRenderInput = { NoticesRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> NoticesGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored scroll offset; 0 when it keeps none (the list's top). */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
        surface = { overlay },
        actionFor = { verdict ->
            verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
                ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
                ?: NoticesActions.actionFor(verdict)
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
        val panel = geometry.panel
        return Layout(panel = panel, gestureRegions = listOfNotNull(panel), scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val input = renderInput()
        overlay.applyMarkup(NoticeMarkup.list(input.notices, input.loading, input.error, input.nowMs))
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            title = L10n.get(input.titleKey),
            scrollY = state.read() ?: 0f,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }
}
