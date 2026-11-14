package inego.zungjunggame.features.challenges

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.net.ChallengeHands
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
 * Everything [ShadowRankedHandsOverlay.render] needs beyond the viewport: the
 * challenge's hand tables and the Who bar's local position. A provider seam so
 * the component stays GL-free until a host supplies the model.
 */
data class ShadowRankedHandsRenderInput(
    val document: ChallengeHands? = null,
    val selectedIndex: Int = 0,
    val dropdownOpen: Boolean = false,
    val loading: Boolean = false,
    /** True when the document failed to load; the UI localizes [errorCode]. */
    val failed: Boolean = false,
    /** The server's machine-readable failure code (`NOT_FOUND`, `CHALLENGE_NOT_FINISHED`, …). */
    val errorCode: String? = null,
)

/**
 * The hand-tables card as a stack component.
 *
 * Geometry comes from the wrapped [ShadowRankedHandsOverlay] through a [measure]
 * seam (GL-free); painting delegates to the overlay's render. The card's gestures
 * are the [GestureBridge]'s: the overlay owns the press/drag/release machine, and
 * [ShadowRankedHandsActions.actionFor] maps the control a release resolved.
 */
class ShadowRankedHandsComponent(
    private val route: Route,
    private val overlay: ShadowRankedHandsOverlay = ShadowRankedHandsOverlay(),
    private val renderInput: () -> ShadowRankedHandsRenderInput = { ShadowRankedHandsRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> ShadowRankedHandsGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored body scroll; absent means the top of the table. */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<ModalVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        verdict.dragScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: verdict.settledScrollOffset?.let { offset -> RouteUiActions.scroll(offset) }
            ?: ShadowRankedHandsActions.actionFor(verdict)
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    private val publicId: String = route.args[ARG_PUBLIC_ID].orEmpty()

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        if (after == state.read()) return
        frame.emit(RouteUiActions.scroll(after))
    }

    override fun layout(frame: Frame): Layout {
        val panel = measure(frame.constraints.worldWidth, frame.constraints.worldHeight).panel
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
            publicId = publicId,
            scrollY = state.read() ?: 0f,
            document = input.document,
            selectedIndex = input.selectedIndex,
            dropdownOpen = input.dropdownOpen,
            loading = input.loading,
            failed = input.failed,
            errorCode = input.errorCode,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }

    companion object {
        /** The route arg naming the challenge whose tables this modal shows. */
        const val ARG_PUBLIC_ID = "publicId"
    }
}
