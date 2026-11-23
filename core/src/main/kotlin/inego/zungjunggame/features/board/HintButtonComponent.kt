package inego.zungjunggame.features.board

import inego.zungjunggame.render.hint.HintButtonGeometry
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * Everything the legacy [HintButtonOverlay.render] needs beyond the viewport:
 * the hand row pose and the HINT button's visual [HintButtonOverlay.Mode].
 */
data class HintButtonRenderInput(
    val rowRightX: Float = Float.NaN,
    val rowCenterY: Float = Float.NaN,
    val handH: Float = 0f,
    val edgeInsetBottom: Float = 0f,
    val mode: HintButtonOverlay.Mode = HintButtonOverlay.Mode.Idle,
    val metricText: String? = null,
)

/**
 * The board's movable HINT button as a stack overlay component.
 *
 * The wrapped [HintButtonOverlay] arrives as a provider because its `UiChip`
 * requires a live GL context, and it is resolved exactly once: every invocation
 * may build GL state. Geometry comes through a [measure] seam so [layout] stays
 * headless-testable; without a seam the widget measures from [renderInput].
 */
class HintButtonComponent(
    route: Route,
    private val overlay: () -> HintButtonOverlay,
    private val renderInput: () -> HintButtonRenderInput = { HintButtonRenderInput() },
    measure: ((Float, Float) -> HintButtonGeometry)? = null,
) : Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /** The single wrapped overlay, built on first use. */
    private val widget: HintButtonOverlay by lazy { overlay() }

    /**
     * The geometry source. A nullable parameter is required because a default
     * parameter cannot reference an instance member.
     */
    private val measure: (Float, Float) -> HintButtonGeometry =
        measure ?: { w, h ->
            val input = renderInput()
            val box = widget.hitBox(
                rowRightX = input.rowRightX,
                rowCenterY = input.rowCenterY,
                handH = input.handH,
                screenW = w,
                screenH = h,
                edgeInsetBottom = input.edgeInsetBottom,
            )
            HintButtonGeometry(Rect(box.cx - box.w * 0.5f, box.cy - box.h * 0.5f, box.w, box.h))
        }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val regions = HitRegions<ComponentAction>()
        geometry.hit?.let { regions.add(it, ComponentAction(HintButtonActions.TAP)) }

        return Layout(hitRegions = regions)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val input = renderInput()
        widget.render(
            batch = canvas.batch,
            rowRightX = input.rowRightX,
            rowCenterY = input.rowCenterY,
            handH = input.handH,
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            edgeInsetBottom = input.edgeInsetBottom,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            mode = input.mode,
            metricText = input.metricText,
        )
    }
}
