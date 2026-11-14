package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.Route

/**
 * Language picker as a stack component.
 *
 * Geometry arrives through a [measure] seam from the wrapped
 * [LanguagePickerOverlay] (GL-free), so [layout] is headless-testable; painting
 * delegates to [LanguagePickerOverlay.render].
 *
 * The card is gesture-owned: the overlay resolves the tapped locale (and its
 * body scroll) on release, so the component forwards the pointer and emits the
 * pick action with the locale tag (a hit region alone left the rows dead). The
 * offset the player left the list at is the route's state: [draw] hands the
 * store's value in every frame, every wheel/drag/settle emits a `routeUi.write`,
 * and [motion] coasts a fling through the same channel and reveals the active
 * locale into view on a first open that carries no stored offset.
 */
class LanguagePickerComponent(
    route: Route,
    private val overlay: LanguagePickerOverlay = LanguagePickerOverlay(),
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> LanguagePickerGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored scroll offset; null until the player (or the reveal) sets one. */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: LanguagePickerActions.actionFor(verdict)
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /** True once this open's reveal has been emitted; the entry is rebuilt per open. */
    private var revealed = false

    override fun motion(frame: Frame) {
        pendingReveal()?.let {
            revealed = true
            frame.emit(RouteUiActions.scroll(it))
        }
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        if (after == state.read()) return
        frame.emit(RouteUiActions.scroll(after))
    }

    /**
     * The offset that brings the active locale into view, or null when nothing is
     * to do: this open already revealed, the store holds the player's offset, the
     * locale is already visible, or the list fits. A query against the last layout
     * — it moves nothing; the caller emits the value it resolves.
     */
    private fun pendingReveal(): Float? {
        if (revealed || state.read() != null) return null
        val offset = overlay.revealLocaleOffset(LocaleController.current, 0f)
        return offset.takeIf { it > 0f }
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions, scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = state.read() ?: 0f,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
        // Motion runs before the first paint, so the frame that pushed the picker
        // cannot reveal it and no later frame is requested on its own; ask for the
        // one whose motion writes the reveal. A stored offset or a visible locale
        // needs none, and the reveal runs once, so this stops after that frame.
        if (pendingReveal() != null) frame.keepPainting()
    }
}
