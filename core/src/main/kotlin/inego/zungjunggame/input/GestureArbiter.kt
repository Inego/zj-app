package inego.zungjunggame.input

import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureHost
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.localPoint
import inego.zungjunggame.ui.stack.Stack
import kotlin.math.sqrt

/**
 * Stateful pointer coordinator for the Stage-2 pipeline.
 *
 * Turns [InputEvent] pointer samples into either a control tap or a drag owned
 * by a [GestureHost] component. Keeps the legacy pointer lifecycle semantics:
 * the layer that received
 * pointer-down keeps drag/release unless the gesture is cancelled, a press that
 * travels past [slopPx] is a drag rather than a tap, and an OS touch-cancel
 * aborts the gesture and swallows the remainder of the touch.
 *
 * GL-free: it reads [FrameModel] layouts and a [Stack], never a device.
 */
class GestureArbiter(
    /** Travel in world px before a press becomes a drag. */
    private val slopPx: Float = DEFAULT_SLOP_PX,
) {
    /** Where a completed tap is dispatched, in world coordinates. */
    fun interface Sink {
        /**
         * @param suppressMenuOpen true when the release was a swipe or a
         *   multi-finger gesture, so the empty-felt menu must stay shut.
         */
        fun tap(x: Float, y: Float, touchStartedOnTarget: Boolean, suppressMenuOpen: Boolean)
    }

    private var pressed = false
    private var host: GestureHost? = null

    /**
     * The owning entry's frame, kept for the gesture's whole life so a drag or
     * release emits through the channel the press arrived on.
     */
    private var hostFrame: Frame? = null
    private var pressX = 0f
    private var pressY = 0f
    private var startedOnTarget = false
    private var dragging = false
    private var maxTravel = 0f
    private var peakPointers = 1
    private var swallowUntilUp = false

    /**
     * Translation from world space to the owning layer's local space. A layer
     * laid out inside a parent scope (e.g. the tutorial shell content zone) is
     * shifted by its panel origin; drag/release must use the same space as the
     * press, or the first move reads as a jump the size of that origin.
     */
    private var ownerOffsetX = 0f
    private var ownerOffsetY = 0f

    /** True while a press is being tracked (with or without a drag owner). */
    val active: Boolean get() = pressed

    /** Processes one pointer event; non-pointer events are ignored. */
    fun handle(event: InputEvent, stack: Stack, frames: FrameModel, sink: Sink) {
        when (event) {
            is InputEvent.PointerCancel -> onCancel(event.stillTouched)
            is InputEvent.PointerDown -> onDown(event, stack, frames)
            is InputEvent.PointerMove -> onMove(event)
            is InputEvent.PointerUp -> onUp(event.x, event.y, sink)
            else -> Unit
        }
    }

    /**
     * Abort any in-flight gesture without dispatching (a modal opened, the app
     * paused). Unlike an OS cancel this does not swallow a live touch.
     */
    fun cancel() {
        val owner = host
        val frame = hostFrame
        if (owner != null && frame != null) owner.onGestureCancel(frame)
        reset()
    }

    private fun onDown(event: InputEvent.PointerDown, stack: Stack, frames: FrameModel) {
        if (swallowUntilUp) {
            TouchDiagnostics.log("arb down swallowed world=${xy(event.x, event.y)}")
            return
        }
        TouchDiagnostics.log(
            "arb down world=${xy(event.x, event.y)} pointers=${event.pointerCount} " +
                "slop=${TouchDiagnostics.fmt(slopPx)}",
        )
        pressed = true
        host = null
        hostFrame = null
        dragging = false
        maxTravel = 0f
        peakPointers = event.pointerCount.coerceAtLeast(1)
        pressX = event.x
        pressY = event.y
        startedOnTarget = false
        ownerOffsetX = 0f
        ownerOffsetY = 0f

        for (instance in stack.paintRange.asReversed()) {
            val layout = frames.layoutFor(instance)
            val local = layout.localPoint(event.x, event.y)
            if (local == null) {
                if (instance.spec.ownsInput) {
                    TouchDiagnostics.log(
                        "arb down stop layer=${instance.route.key.value} local=null ownsInput",
                    )
                    return
                }
                continue
            }
            val (lx, ly) = local
            val controlHit = layout.hitRegions.hitAt(lx, ly) != null
            if (controlHit) startedOnTarget = true

            val gesture = frames.componentFor(instance) as? GestureHost
            val claimed = claims(layout, lx, ly)
            if (claimed) {
                val frame = frames.frameFor(instance)
                if (gesture != null && gesture.onGesturePress(lx, ly, frame)) {
                    host = gesture
                    hostFrame = frame
                    // Local space is the world space translated by the panel origin
                    // (scopes are pure translations), so the press gives the offset.
                    ownerOffsetX = event.x - lx
                    ownerOffsetY = event.y - ly
                    startedOnTarget = true
                    TouchDiagnostics.log(
                        "arb down host layer=${instance.route.key.value} local=${xy(lx, ly)} " +
                            "regions=${layout.gestureRegions.size} scrollable=${layout.scrollable}",
                    )
                    return
                }
            }
            TouchDiagnostics.log(
                "arb down layer=${instance.route.key.value} local=${xy(lx, ly)} claimed=$claimed " +
                    "gesture=${gesture != null} controlHit=$controlHit ownsInput=${instance.spec.ownsInput}",
            )
            if (instance.spec.ownsInput) return
        }
        TouchDiagnostics.log("arb down no-host world=${xy(event.x, event.y)} startedOnTarget=$startedOnTarget")
    }

    private fun onMove(event: InputEvent.PointerMove) {
        if (swallowUntilUp) return
        if (event.pointerCount > peakPointers) peakPointers = event.pointerCount
        if (!pressed) return
        val dx = event.x - pressX
        val dy = event.y - pressY
        val travel = sqrt(dx * dx + dy * dy)
        if (travel > maxTravel) maxTravel = travel
        if (!dragging && maxTravel > slopPx) {
            dragging = true
            TouchDiagnostics.log(
                "arb drag start travel=${TouchDiagnostics.fmt(maxTravel)} " +
                    "slop=${TouchDiagnostics.fmt(slopPx)} world=${xy(event.x, event.y)}",
            )
        }
        val owner = host ?: return
        val frame = hostFrame ?: return
        owner.onGestureDrag(event.x - ownerOffsetX, event.y - ownerOffsetY, dragging, frame)
    }

    private fun onUp(x: Float, y: Float, sink: Sink) {
        if (swallowUntilUp) {
            swallowUntilUp = false
            TouchDiagnostics.log("arb up swallowed world=${xy(x, y)}")
            reset()
            return
        }
        if (!pressed) return
        val owner = host
        val frame = hostFrame
        val ownerX = x - ownerOffsetX
        val ownerY = y - ownerOffsetY
        // The lift is a sample too: an OS that withholds every move and still
        // delivers the finger-up must still show the swipe.
        val swiped = dragging || maxTravel > slopPx || travel(x, y) > slopPx
        val suppressMenuOpen = peakPointers > 1 || swiped
        val wasOnTarget = startedOnTarget
        reset()

        if (owner != null && frame != null && owner.onGestureRelease(ownerX, ownerY, frame)) {
            TouchDiagnostics.log(
                "arb up owned world=${xy(x, y)} local=${xy(ownerX, ownerY)} " +
                    "swiped=$swiped startedOnTarget=$wasOnTarget",
            )
            return
        }
        TouchDiagnostics.log(
            "arb up sink.tap world=${xy(x, y)} owner=${owner != null} swiped=$swiped " +
                "suppress=$suppressMenuOpen startedOnTarget=$wasOnTarget",
        )
        sink.tap(x, y, wasOnTarget, suppressMenuOpen)
    }

    private fun xy(x: Float, y: Float): String =
        "${TouchDiagnostics.fmt(x)},${TouchDiagnostics.fmt(y)}"

    private fun travel(x: Float, y: Float): Float {
        val dx = x - pressX
        val dy = y - pressY
        return sqrt(dx * dx + dy * dy)
    }

    private fun onCancel(stillTouched: Boolean) {
        TouchDiagnostics.log("arb cancel stillTouched=$stillTouched owner=${host != null}")
        val owner = host
        val frame = hostFrame
        if (owner != null && frame != null) owner.onGestureCancel(frame)
        reset()
        if (stillTouched) swallowUntilUp = true
    }

    private fun reset() {
        pressed = false
        host = null
        hostFrame = null
        dragging = false
        maxTravel = 0f
        peakPointers = 1
        startedOnTarget = false
        ownerOffsetX = 0f
        ownerOffsetY = 0f
    }

    /** True when `(x, y)` lies in a region this layer lets [GestureHost] own. */
    private fun claims(layout: Layout, x: Float, y: Float): Boolean {
        return layout.gestureRegions.any { it.contains(x, y) } || layout.scrollable && (layout.panel?.contains(x, y) ?: true)
    }

    companion object {
        /** Design-px slop; the legacy router scaled this by device density. */
        const val DEFAULT_SLOP_PX = 12f
    }
}
