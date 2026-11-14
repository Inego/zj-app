package inego.zungjunggame.chart

import inego.zungjunggame.ui.FramePointers
import inego.zungjunggame.ui.PointerSample
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Tunables of [ChartGestureRecognizer]. The pixel-shaped ones are fractions of the
 * content width rather than absolute pixels, so the same values read the same on a
 * phone and a desktop window; the wheel scales are the exception, because a mouse
 * notch, a trackpad delta and the web's ±1 are not comparable units and the only
 * calibration is the platform the chart ships on.
 */
data class ChartGestureConfig(
    /** Release speed that launches a fling, in content widths per second. */
    val flingLaunchWidthsPerSec: Double = 0.28,

    /** Speed below which a coast ends, in content widths per second. */
    val flingStopWidthsPerSec: Double = 0.045,

    /**
     * Exponential friction of a coast, in 1/s: velocity decays as `e^(-f·t)`, so
     * the same coefficient the vertical lists use reads as the same weight here.
     */
    val flingFrictionPerSec: Double = 5.2,

    /** Weight of the newest sample in the fling velocity (light EMA). */
    val velocityBlend: Double = 0.35,

    /** A step longer than this is a stall, not a sample, and does not feed the fling. */
    val maxVelocitySampleSec: Double = 0.1,

    /** Fingers closer than this cannot define a pinch scale (division guard). */
    val minPinchDistancePx: Float = 12f,

    /** Wheel X: content widths the time axis pans per scroll unit. */
    val wheelPanWidthsPerUnit: Double = 0.06,

    /**
     * Wheel Y: powers of two of zoom per scroll unit. One unit is one notch, and
     * one explicit zoom press is one unit — the same amount, so a button and the
     * mouse cannot ask for different windows at the same anchor.
     */
    val wheelZoomStepsPerUnit: Double = 0.25,
)

/**
 * Turns the device frames a chart's gesture surface receives into the typed pan and
 * zoom moves [ChartViewport] applies.
 *
 * **Why a raw frame and not pointer events.** A pinch is a relation between two
 * pointers on the *same* frame; the pointer-event path carries one pointer per
 * event by design, so the recognizer reads the frame's whole down set
 * ([FramePointers], on [inego.zungjunggame.ui.Constraints.pointers]) and the
 * pipeline only tells it whether the chart owns the gesture ([begin] on a claimed
 * press, [end]/[cancel] on the release). It is not a second pipeline: the same
 * frame the pointer events were normalized from is the one it reads, and the
 * ownership rules (region claim, slop, OS cancel, occlusion) stay the pipeline's.
 *
 * **What it emits.** Per frame, the deltas *this* frame contributed — never an
 * absolute position — so the recognizer holds no authoritative copy of the
 * window. A one-finger drag emits [ChartGesture.Pan]; two or more emit a
 * [ChartGesture.Zoom] (anchored at the *previous* midpoint) followed by the
 * midpoint's [ChartGesture.Pan], which together keep the instant under the
 * fingers under them. The factor is the span multiplier the gesture vocabulary
 * names, so fingers moving apart — the usual zoom-in — emit below 1. A release
 * with enough residual speed arms a coast that emits [ChartGesture.Pan] on
 * later frames until it decays or is stopped.
 *
 * **Pointer identity.** The pinch pair is the two longest-tracked pointers, in
 * adoption order; a third finger is ignored until one of the pair lifts, and a
 * pair change re-bases without emitting (so a change cannot move the chart). A
 * platform slot id means "this pointer while it stays down": an id the frame stops
 * reporting is dropped, so an id that comes back is a new pointer, adopted at the
 * end of the order and again without emitting. What one frame's samples cannot
 * express is a slot freed *and* reused inside a single frame gap — the sample
 * carries no generation — so that residual is read as motion; nothing here can
 * tell it apart.
 *
 * GL-free, clock-free, and free of any route: the owner hands the frame in and
 * applies what comes back. The fling's motion is visible to the present decision
 * through [isCoasting], so a settled app keeps painting while it coasts.
 */
class ChartGestureRecognizer(private val config: ChartGestureConfig = ChartGestureConfig()) {

    /** True between a claimed press and its release (or cancel). */
    private var claimed = false

    /** Pointers down while claimed, in adoption order; the order picks the pinch pair. */
    private val tracked = LinkedHashMap<Int, PointerSample>()

    /** Scratch for the drop pass: the ids the frame still reports, to keep the map small. */
    private val downIds = ArrayList<Int>(4)

    /** The single-pointer drag baseline: the last x the drag's pointer was seen at. */
    private var lastDragX = 0f
    private var lastFirstId = NO_ID

    /** The two-finger baseline: midpoint x and finger distance of the last frame. */
    private var lastMidX = 0f
    private var lastDistance = 0.0
    private var lastSecondId = NO_ID

    /** Residual drag velocity in px/s, and whether it is still coasting. */
    private var velocityPxPerSec = 0.0
    private var flinging = false

    /** The content width of the last frame, for the width-relative speed bounds. */
    private var contentWidthPx = 0f

    /** True while a released drag is still coasting; report it to the paint decision. */
    val isCoasting: Boolean get() = flinging

    /** True between a claimed press and its release. */
    val isActive: Boolean get() = claimed

    /**
     * A press the pipeline claimed for this chart (the surface's
     * `onPointerDown` returned true). A second finger's press arrives as its own
     * pointer-down; it keeps the live baselines, so the pinch does not re-anchor.
     */
    fun begin() {
        stopInertia()
        if (claimed) return
        claimed = true
        tracked.clear()
        resetBaselines()
        velocityPxPerSec = 0.0
    }

    /**
     * The pipeline released the gesture. The last sampled drag velocity arms a
     * coast when it is fast enough; a tap (nothing moved) coasts nowhere.
     */
    fun end() {
        claimed = false
        tracked.clear()
        resetBaselines()
        if (contentWidthPx > 0f &&
            abs(velocityPxPerSec) >= config.flingLaunchWidthsPerSec * contentWidthPx.toDouble()
        ) {
            flinging = true
        } else {
            stopInertia()
        }
    }

    /**
     * Abort without coasting: an OS touch-cancel, or a modal taking the pointer.
     * Distinct from [end] on purpose — a withdrawn touch must not fling.
     */
    fun cancel() {
        claimed = false
        tracked.clear()
        resetBaselines()
        stopInertia()
    }

    /** The owner decided the coast must end (the window hit its bound, the route left). */
    fun stopInertia() {
        flinging = false
        velocityPxPerSec = 0.0
    }

    /**
     * One frame of device state.
     *
     * @param pointers the frame's down set, in the frame's own space.
     * @param deltaSec the frame's step (clamped by the host, as motion's always is).
     * @param content the rect the chart's content — its candles — is drawn in. A
     *   pinch's midpoint is a fraction of it, so the instant under the fingers is
     *   the one held; the width also sets the fling's speed bounds.
     * @return the moves this frame contributed, in the order they must be applied
     *   (a pinch's zoom before the midpoint pan that follows the fingers); empty
     *   when nothing moved.
     */
    fun update(pointers: FramePointers, deltaSec: Float, content: Rect): List<ChartGesture> {
        // Nothing owns the gesture and no coast is running: no move is possible,
        // so the idle frame allocates nothing at all.
        if (!flinging && !claimed) return emptyList()
        val out = ArrayList<ChartGesture>(2)
        val dt = if (deltaSec.isFinite() && deltaSec > 0f) deltaSec.toDouble() else 0.0
        if (content.width > 0f) contentWidthPx = content.width

        // A coast is advanced first and survives frames with no pointer down: the
        // finger that launched it is already gone.
        if (flinging) advanceFling(dt, out)

        if (!claimed) {
            tracked.clear()
            resetBaselines()
            return out
        }

        dropLifted(pointers)
        adopt(pointers)

        val first = tracked.values.firstOrNull()
        val second = tracked.entries.elementAtOrNull(1)?.value
        if (first == null) {
            // Every pointer vanished without the pipeline's release. Drop the
            // gesture's energy rather than coast from a pointer nobody holds.
            velocityPxPerSec = 0.0
            resetBaselines()
            return out
        }

        val firstId = first.id
        val secondId = second?.id ?: NO_ID
        // A family change re-bases without emitting: a new pointer and a lifted pair
        // member both move the baseline, never the chart.
        val sameFamily = firstId == lastFirstId && secondId == lastSecondId
        lastFirstId = firstId
        lastSecondId = secondId

        if (second != null) {
            val midX = (first.x + second.x) / 2f
            val distance = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
            if (sameFamily && distance >= config.minPinchDistancePx &&
                lastDistance >= config.minPinchDistancePx
            ) {
                // [ChartGesture.Zoom]'s factor multiplies the visible span (below
                // 1 zooms in), so fingers moving apart — the distance growing —
                // must emit below 1: the reciprocal of the distance ratio.
                val factor = lastDistance / distance
                if (abs(factor - 1.0) > PINCH_FACTOR_EPS) {
                    out += ChartGesture.Zoom(factor, contentFraction(lastMidX, content))
                }
                val dxMid = midX - lastMidX
                if (dxMid != 0f) out += ChartGesture.Pan(dxMid)
            }
            lastMidX = midX
            lastDistance = distance
            // A two-finger release coasts nowhere: the coast is the one-finger
            // flick's, and the midpoint's drift is not a flick.
            velocityPxPerSec = 0.0
        } else {
            if (sameFamily) {
                val dx = first.x - lastDragX
                if (dx != 0f) out += ChartGesture.Pan(dx)
                sampleVelocity(dx.toDouble(), dt)
            }
            lastDragX = first.x
        }
        return out
    }

    /**
     * One wheel / trackpad sample, both axes of the same notch.
     *
     * Wheel X pans the time axis (libGDX's sign: positive when the content slides
     * right, so a wheel turned right reports a negative); wheel Y zooms anchored
     * at the cursor, positive (scroll down) zooming out. A sample ends any coast —
     * the wheel is the user taking over.
     *
     * @param x the cursor's x in the surface's own space; the zoom anchors there.
     * @param y the cursor's y of the same sample (the time axis has no use for it;
     *   a Y-axis zoom would).
     * @param amountX the sample's horizontal delta, beside [amountY] the way
     *   [inego.zungjunggame.input.InputEvent.Scroll] carries it.
     * @param content the rect the chart's content is drawn in. The anchor is a
     *   fraction of *this*, not of the panel around it: the instant held still is
     *   the one under the cursor, and a point outside it (a label gutter, an inset)
     *   anchors at the nearest edge.
     * @return the moves this sample contributed, in the order they must be
     *   applied: the pan, then the zoom anchored where the cursor is.
     */
    fun wheel(
        amountY: Float,
        x: Float,
        y: Float,
        amountX: Float,
        content: Rect,
    ): List<ChartGesture> {
        if (amountY == 0f && amountX == 0f) return emptyList()
        stopInertia()
        if (content.width > 0f) contentWidthPx = content.width

        val out = ArrayList<ChartGesture>(2)
        if (amountX != 0f && content.width > 0f) {
            val panPx = amountX.toDouble() *
                config.wheelPanWidthsPerUnit * content.width.toDouble()
            out += ChartGesture.Pan(panPx.toFloat())
        }
        if (amountY != 0f) {
            out += zoomUnits(amountY.toDouble(), contentFraction(x, content))
        }
        return out
    }

    /**
     * The zoom [units] of scroll input ask for, anchored at [anchorFraction].
     *
     * This is the one definition of how much a zoom request is worth: the wheel
     * passes its sample's amount, and an explicit zoom control passes ±1 — one
     * notch — anchored at the chart's centre. A button press and a notch over
     * the same point therefore produce the same window. The amount lives here, not in
     * the gesture or the viewport, because it is platform calibration: a mouse
     * notch, a trackpad delta and the web's ±1 are not comparable units.
     */
    fun zoomUnits(units: Double, anchorFraction: Double = 0.5): ChartGesture.Zoom =
        ChartGesture.Zoom(
            factor = 2.0.pow(units * config.wheelZoomStepsPerUnit),
            anchorFraction = anchorFraction,
        )

    // ── Pointer bookkeeping ──────────────────────────────────────────────────

    /** Forget the pointers the frame no longer reports: a lift, or an OS cancel. */
    private fun dropLifted(pointers: FramePointers) {
        downIds.clear()
        for (sample in pointers) downIds += sample.id
        tracked.keys.retainAll(downIds.toSet())
    }

    /**
     * Adopt the pointers that appeared while the gesture is live, in the order they
     * first appeared: a new id appends to that order, and one already tracked keeps
     * its place (an id is only ever re-adopted after a frame it was absent from, so
     * it appends then). The frame that adopts a pointer emits nothing — a new
     * pointer can never move the chart by arriving.
     */
    private fun adopt(pointers: FramePointers) {
        for (sample in pointers) tracked[sample.id] = sample
    }

    private fun resetBaselines() {
        lastFirstId = NO_ID
        lastSecondId = NO_ID
        lastDistance = 0.0
    }

    // ── Motion ───────────────────────────────────────────────────────────────

    /** One frame of the coast: its step, then the decay, then the stop test. */
    private fun advanceFling(dt: Double, out: MutableList<ChartGesture>) {
        if (!flinging) return
        if (dt <= 0.0) return
        if (contentWidthPx <= 0f) {
            stopInertia()
            return
        }
        val dx = velocityPxPerSec * dt
        if (dx != 0.0) out += ChartGesture.Pan(dx.toFloat())
        // Frame-rate independent exponential decay, as the vertical lists use.
        velocityPxPerSec *= exp(-config.flingFrictionPerSec * dt)
        if (abs(velocityPxPerSec) < config.flingStopWidthsPerSec * contentWidthPx.toDouble()) {
            stopInertia()
        }
    }

    /** Fold this frame's movement into the drag velocity; a stall is not a sample. */
    private fun sampleVelocity(dx: Double, dt: Double) {
        if (dt <= 0.0 || dt > config.maxVelocitySampleSec) return
        val instant = dx / dt
        velocityPxPerSec =
            velocityPxPerSec * (1.0 - config.velocityBlend) + instant * config.velocityBlend
    }

    /** Where [x] sits across [content]; the cursor anchor of a wheel zoom. */
    private fun contentFraction(x: Float, content: Rect): Double =
        if (content.width <= 0f) {
            0.5
        } else {
            ((x - content.left) / content.width.toDouble()).coerceIn(0.0, 1.0)
        }

    private companion object {
        const val NO_ID = Int.MIN_VALUE

        /** A pinch factor within this of 1 is rounding noise, not a zoom. */
        const val PINCH_FACTOR_EPS = 1e-4
    }
}
