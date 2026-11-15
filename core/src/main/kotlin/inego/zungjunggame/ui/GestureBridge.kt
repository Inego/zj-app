package inego.zungjunggame.ui

/**
 * Makes a [GestureSurface] the [GestureHost] of the component wrapping it: every
 * pipeline call is one forward, and a release becomes the [ComponentAction] the
 * surface's verdict maps to, emitted through the frame the callback arrived with.
 *
 * A component uses it by **being** the bridge — extending it beside [Component] —
 * so "the widget's gestures reach the pipeline" holds by construction instead of
 * being retyped per component:
 *
 * ```kotlin
 * class SettingsComponent(route: Route, private val overlay: SettingsOverlay) :
 *     GestureBridge<ModalVerdict>(
 *         surface = { overlay },
 *         actionFor = SettingsActions::actionFor,
 *     ),
 *     Component {
 *
 *     override fun layout(frame: Frame): Layout = ...
 *     override fun draw(frame: Frame, canvas: Canvas) = ...
 * }
 * ```
 *
 * Five channels run through the bridge, each an optional mapping beside the
 * release verdict:
 *
 * - a **release** verdict goes through [actionFor]; its return value is also the
 *   verdict handed to [consumeFor], which decides whether the pipeline may still
 *   re-read the lift as a tap;
 * - a **press** verdict (the value a claimed press resolved, e.g. a slider's
 *   value at the point that was pressed) goes through the same [actionFor];
 * - a **drag** verdict (a per-step value the surface resolves mid-drag) goes
 *   through the same [actionFor];
 * - a **state action** the same verdict carries (a drawer closing on the row that
 *   selected it, a settled offset) goes through [stateActionFor] **before**
 *   [actionFor], so a feature action's command refines the same state instead of
 *   being clobbered by the value the surface reported beside it;
 * - a **consumed scroll** goes through [scrollActionFor], keyed by the offset the
 *   surface settled at ([GestureSurface.scrollOffset]) — and only when that
 *   offset actually changed, so a sample a surface merely had to look at (an
 *   axis it does not use) writes no state.
 *
 * The defaults preserve today's behavior: always-consume (`{ true }`), no press
 * or drag verdict (the surface reports null, so nothing is emitted), and no
 * state action.
 */
open class GestureBridge<V>(
    private val surface: () -> GestureSurface<V>,
    private val actionFor: (V) -> ComponentAction? = { null },
    private val consumeFor: (V) -> Boolean = { true },
    private val scrollActionFor: (Float) -> ComponentAction? = { null },
    private val stateActionFor: (V) -> ComponentAction? = { null },
) : GestureHost {

    /**
     * A component may override this to decline a press that belongs to a control
     * rather than to the surface — a scrolling card whose chrome is published as
     * hit regions needs the control's tap to stay with the control. Returning
     * false lets the pipeline resolve the press against the layer's hit regions.
     */
    open override fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean {
        if (!surface().onPointerDown(x, y)) return false
        // A press may already have resolved a value (a slider jumps to where it
        // was pressed); emit it before the drag steps that follow.
        surface().pressVerdict()?.let { verdict -> emit(verdict, frame) }
        return true
    }

    override fun onGestureDrag(x: Float, y: Float, dragging: Boolean, frame: Frame) {
        surface().onPointerDrag(x, y)?.let { verdict -> emit(verdict, frame) }
    }

    override fun onGestureRelease(x: Float, y: Float, frame: Frame): Boolean {
        val verdict = surface().onPointerUp(x, y)
        emit(verdict, frame)
        // Whether the surface's own resolution consumed the release is the
        // surface's call, not the pipeline's: the bridge only reports the verdict.
        return consumeFor(verdict)
    }

    override fun onGestureCancel(frame: Frame) = surface().cancelGesture()

    /** The surface's coast, for the present decision after input has armed it. */
    internal fun scrollCoasting(): Boolean = surface().scrollCoasting()

    override fun onWheel(amountY: Float, x: Float, y: Float, amountX: Float, frame: Frame): Boolean {
        val surface = surface()
        // A surface that only scrolls vertically is offered a horizontal-only
        // trackpad sample by the pipeline (one axis may be zero); it must not
        // report a settlement it did not make. Capture the offset and emit only a
        // value that actually changed, so an X-only sample cannot write a no-op
        // route-state revision through every scrollable card.
        val before = surface.scrollOffset()
        val consumed = surface.onScroll(amountY, x, y, amountX)
        if (consumed) {
            val after = surface.scrollOffset()
            if (after != null && after != before) {
                scrollActionFor(after)?.let(frame.emit)
            }
        }
        return consumed
    }

    /**
     * The vertical-only call is the same sample with no horizontal delta, and it
     * funnels through the two-axis form so the emission above has one site: a
     * surface that overrides either form is consumed exactly once.
     */
    override fun onWheel(amount: Float, x: Float, y: Float, frame: Frame): Boolean =
        onWheel(amount, x, y, 0f, frame)

    private fun emit(verdict: V, frame: Frame) {
        stateActionFor(verdict)?.let(frame.emit)
        actionFor(verdict)?.let(frame.emit)
    }
}
