package inego.zungjunggame.features.ratinghistory

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.chart.ChartGesture
import inego.zungjunggame.chart.ChartGestureRecognizer
import inego.zungjunggame.chart.ChartViewport
import inego.zungjunggame.chart.ChartWindowEase
import inego.zungjunggame.chart.applying
import inego.zungjunggame.input.GestureArbiter
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.net.RatingCandle
import inego.zungjunggame.net.RatingHistoryClient
import inego.zungjunggame.net.RatingHistoryPublication
import inego.zungjunggame.net.RatingHistoryStatus
import inego.zungjunggame.net.RatingHistoryTiles
import inego.zungjunggame.platform.PlatformTimeZone
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureHost
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One owner for a chart built without a host — a headless assembly, or a feature
 * registration that runs before the composition root hands its own in. Shared
 * rather than rebuilt per factory call, so the tile store survives a route
 * instance being rebuilt.
 */
internal fun defaultRatingHistory(): () -> RatingHistoryClient {
    val owner = RatingHistoryClient()
    return { owner }
}

/**
 * The rating-history screen as a stack component.
 *
 * **Ownership.** The wrapped [RatingHistoryOverlay] owns the surface's geometry,
 * paint, axes and the view-local Y ease. This component owns the chart's
 * *orchestration*:
 *
 * - the visible window — and the rung it is drawn at — are rebuilt from the
 *   route's own [RatingHistoryState] every frame, seeded from E1's persisted view
 *   or else from the publication's all-time bounds, and written back through
 *   [RouteUiActions.write] after a gesture (the window and the rung together).
 *   The route holds the **target**; the paint shows [ChartWindowEase]'s displayed
 *   window, which eases toward it, so a control press or a wheel notch animates
 *   instead of jumping. A direct manipulation — a drag, a pinch, a coast — snaps the
 *   ease: a window under a finger is never behind the finger;
 * - [ChartGestureRecognizer] turns the frame's whole pointer set and both wheel
 *   axes into [inego.zungjunggame.chart.ChartGesture]s, which are applied through
 *   `ChartViewport.applying`; the component never does its own pan/zoom maths;
 * - [RatingChartLevel] picks the requested rung with hysteresis, seeded from the
 *   stored rung on a reopen so the same window is drawn at the density it was left
 *   at, while the *displayed* rung keeps the retained coarse tiles until the finer
 *   request holds the whole window;
 * - E2's [RatingHistoryClient] is driven by `requestCoverage` only when the
 *   visible tile run or the rung actually changes (plus one immediate request on
 *   a settle), never once per frame;
 * - the retained tiles are flattened/cached by [RatingChartSeries] against
 *   [RatingHistoryClient.coverageVersion], so pan and zoom draw immediately from
 *   cache and never wait on the network.
 *
 * The chart is a **snapshot**: the window the player leaves is the window they
 * get back, and nothing in this component chases new settlements. A reading is
 * taken when the surface appears, and reopening it is what takes another.
 *
 * **Gestures.** The plot rect is published as a [Layout.gestureRegions] entry, so
 * the modal's own drag/outside handling cannot take a chart drag: the press is
 * claimed here and its release is consumed even when it was a tap. The wheel
 * needs [Layout.scrollable] (that is the gate `ScrollRouter` routes on); the
 * component then accepts it only inside the plot, and measures it — like a drag or
 * a pinch — in the painted **candle area**, not in the plot: the plot's inset and
 * rating gutter are chrome, and an anchor measured across them holds an instant
 * beside the pointer instead of under it. Dragging reads `constraints.pointers` in
 * [motion], the same frame the pointer events were normalized from, which is what
 * makes a two-finger pinch possible.
 *
 * **Controls.** The explicit zoom in/out and All Time controls sit in their own
 * row beside the plot with their own rects. They are handled here —
 * not through the tap/action table — because a command is applied to the live
 * [ChartViewport] and persisted through the same route-state write a gesture uses;
 * routing it through an effect would duplicate the viewport semantics. Each
 * enabled rect is also a [Layout.gestureRegions] entry, so the chart's plot claim
 * can never swallow a control; a disabled control is not claimed at all.
 *
 * **States.** The component picks one [RatingHistoryPhase] from E2's publication,
 * the displayed series and `holds`: publication lag ([RatingHistoryPhase.Pending])
 * and a backfilling account ([RatingHistoryPhase.NotReady]) are never painted as
 * empty, and a held window with no candle is the distinct
 * [RatingHistoryPhase.EmptyView]. The overlay formats the copy and paints it.
 *
 * **Frames.** Continuous presents are requested only while the recognizer is
 * active/coasting, or the Y scale or the window ease is still easing. An open
 * request is not a pulse: while
 * the picture is otherwise settled, the component schedules **one** wake at E2's
 * earliest request deadline ([Frame.wakeAfter]), so the web backend's 15 s
 * retirement still fires even though no frame is being painted meanwhile.
 */
class RatingHistoryComponent(
    route: Route,
    private val overlay: RatingHistoryOverlay = RatingHistoryOverlay(),
    /** The chart's tile owner: the retained coverage and the request scheduler. */
    private val history: () -> RatingHistoryClient = defaultRatingHistory(),
    /** The camera used to clip the plot with a scissor rect. */
    private val camera: () -> Camera = { OrthographicCamera() },
    /** The route's stored target and visible window. */
    private val state: StatePort<RatingHistoryState> = StatePort { null },
    /**
     * How the player interacts with this platform.
     *
     * The popup follows the profile: a pointer platform names the candle or flat
     * carry under the cursor, a touch platform names what the player tapped. One
     * rule per platform, not two features.
     */
    private val profile: InputProfile = InputProfile.detect(),
) : Component, GestureHost {
    override val id: ComponentId = ComponentId(route.key.value)

    init {
        // A route instance is a new surface. The overlay is shared across
        // instances, so its chart must not ease the new window from the scale
        // the previous visit left (see [RatingHistoryOverlay.resetChart]).
        overlay.resetChart()
    }

    private val recognizer = ChartGestureRecognizer()

    /**
     * The time axis's ease: the route owns where the window is going, the paint
     * shows where it is. A zoom command (a control press, a wheel sample, All
     * Time) retargets it; a drag or a pinch snaps it, because a window under a
     * finger must be where the finger put it.
     */
    private val windowEase = ChartWindowEase()

    /** The player whose history the ease belongs to; a different one snaps. */
    private var windowTarget: RatingHistoryTarget? = null

    /** The window [motion] decided to paint; [draw] paints exactly this. */
    private var paintedWindow: ChartViewport? = null

    /** The rung [RatingChartLevel] currently wants requested. */
    private var selectedLevel: Int? = null

    /** The rung actually painted; lags a finer request until it holds the window. */
    private var displayLevel: Int? = null

    /** The last requested run, so a still window sends no repeated requests. */
    private var lastRequestLevel: Int? = null
    private var lastRequestRun: LongRange? = null

    /** True once the current settle has already sent its one immediate request. */
    private var settleRequested = false

    /** True when the recognizer was active/coasting on the previous motion frame. */
    private var wasInteracting = false

    /** True before the first window has been resolved, so it requests immediately. */
    private var firstWindow = true

    /**
     * When this surface opened, on a monotonic millisecond clock.
     *
     * A chartless loading state is silent for [OPENING_LOADING_GRACE_MS] after
     * this instant, so a fast all-time answer never flashes a sentence or a
     * skeleton on its way to the chart.
     */
    private val openedAtMs = System.nanoTime() / 1_000_000L

    /** True once the single wake that ends the opening grace has been asked for. */
    private var openingWakeAsked = false

    /** The last measured plot, for gesture callbacks that arrive after motion. */
    private var plot: Rect = Rect(0f, 0f, 0f, 0f)

    /** The explicit control a press claimed, if any; released outside it cancels. */
    private var pressedControl: Control? = null

    /** True while the current press is on the plot, so its release can be a tap. */
    private var pressedOnPlot = false

    /** Where the current press landed, so its release can be measured against it. */
    private var pressX = 0f
    private var pressY = 0f

    /** True once the current gesture moved: a drag is not a tap. */
    private var dragged = false

    /**
     * The world point of the tapped selection on a touch platform, or null when
     * nothing is selected.
     *
     * A touch platform has no pointer to follow, so the popup names what the
     * player last chose and hangs beside the point they chose it at. The point
     * is kept, not the selection: the window may still change under it, and the
     * popup then names the ink that is really there.
     */
    private var tap: Tap? = null

    /** Where a press landed, in world coordinates. */
    private data class Tap(val x: Float, val y: Float)

    private enum class Control { ZoomOut, ZoomIn, AllTime, Retry }

    private var series: RatingChartSeries? = null
    private var seriesVersion = Long.MIN_VALUE
    private var seriesLevel = -1
    private var seriesTarget: RatingHistoryTarget? = null

    override fun motion(frame: Frame) {
        val owner = history()
        // A presented frame is what lets E2 retire a request the web backend
        // never fails. The deadline wake below is the only frame asked for while
        // the picture is otherwise settled.
        owner.tick()

        val constraints = frame.constraints
        val stored = state.read()
        val target = stored?.target
        plot = RatingHistoryOverlay.measure(
            constraints.worldWidth,
            constraints.worldHeight,
            footer = showsFooter(target),
        ).plot

        if (target == null) {
            windowEase.clear()
            windowTarget = null
            paintedWindow = null
            overlay.advanceChart(constraints.deltaSec, null, null, null)
            scheduleDeadlineWake(frame, owner)
            wasInteracting = false
            return
        }
        // The surface load effect opens on route appearance; this is the same
        // idempotent seam for a route materialized before that observer ran.
        if (owner.target != target) owner.open(target)
        scheduleOpeningGraceWake(frame)
        // A different player is a different history: there is no window to ease
        // from, so the first one snaps.
        if (target != windowTarget) {
            windowEase.clear()
            windowTarget = target
        }
        val publication = owner.publication

        // The window the route asks for: what a control, the wheel or a gesture
        // wrote, and what the rung, the requests and the store are about.
        var settled = viewportFor(stored, publication)
        var moved = false
        // A gesture starts where the player's eye is — the painted window — so a
        // drag that begins mid-ease does not jump to the window the ease aimed at.
        val paintedBefore = paintedWindowFor(stored, publication)
        if (paintedBefore != null) {
            var projected: ChartViewport = paintedBefore
            val content = gestureArea()
            val gestures = recognizer.update(
                constraints.pointers,
                constraints.deltaSec,
                content,
            )
            val before = projected
            for (gesture in gestures) {
                val next = projected.applying(gesture, content.width)
                if (windowMoved(projected, next)) moved = true
                projected = next
            }
            if (moved) {
                // Direct manipulation is 1:1 with the pointer: the window it
                // leaves is the route's, and the ease must not trail the finger.
                settled = projected
                windowEase.snapTo(projected.fromEpochMs, projected.toEpochMs)
            } else if (recognizer.isCoasting && gestures.isNotEmpty() && !windowMoved(before, projected)) {
                // The recognizer never clamps; the owner does. A coast that
                // spends its whole frame against a bound has no motion left, so
                // it ends here rather than spinning frames until it decays.
                recognizer.stopInertia()
            }
        }

        val interacting = recognizer.isActive || recognizer.isCoasting
        val settledNow = wasInteracting && !interacting
        // A window that moved takes the tapped candle with it: the popup would
        // otherwise name a candle that is no longer under the player's finger.
        if (moved) tap = null

        // A command eases; the target is the route's window (or the gesture's, on
        // the frame it took over). A paint frame reads what this leaves behind.
        if (settled != null) {
            windowEase.setTarget(settled.fromEpochMs, settled.toEpochMs)
            windowEase.advance(constraints.deltaSec)
        } else {
            windowEase.clear()
        }
        val painted = paintedWindowFor(stored, publication)
        paintedWindow = painted

        if (settled != null) {
            // The rung is part of the remembered view, not of the window: the ladder
            // keeps a rung across a four-times-wide band, so the same window can be
            // drawn at two densities. Resuming the stored rung is what makes a
            // close/reopen show the candles the player was looking at instead of a
            // fresh density pick that splits them one rung finer.
            if (selectedLevel == null) selectedLevel = stored.level
            val level = resolveLevel(settled, publication)
            if (level != null) {
                val display = resolveDisplay(owner, level, settled, publication)
                val requested = driveRequests(
                    owner = owner,
                    level = level,
                    viewport = settled,
                    publication = publication,
                    immediate = settledNow || firstWindow,
                )
                if (requested) firstWindow = false
                // The Y scale eases toward the window the zoom is *going to*, not
                // toward each intermediate one: two eases chained would trail the
                // zoom by another time constant.
                overlay.advanceChart(
                    constraints.deltaSec,
                    seriesFor(owner, display, target),
                    settled,
                    target,
                )
            } else {
                overlay.advanceChart(constraints.deltaSec, null, null, target)
            }
            // A moved window is the player's; write it back. A stored window is
            // also corrected once if the publication's bounds clamped it.
            persistWindow(frame, stored, target, settled, force = moved)
        } else {
            overlay.advanceChart(constraints.deltaSec, null, null, target)
        }

        wasInteracting = interacting
        if (interacting) frame.keepPainting()
        if (overlay.chartAnimating || windowEase.isEasing) frame.keepPainting()
        if (!interacting && !overlay.chartAnimating && !windowEase.isEasing) {
            scheduleDeadlineWake(frame, owner)
        }
    }

    /**
     * An open request is a wall-clock deadline, not a moving picture. Ask for one
     * frame at the earliest deadline instead of presenting every frame until it
     * fires; E2's [RatingHistoryClient.tick] then retires the request and the
     * failure surface appears. A response before then wakes its own frame through
     * the transport's posted callback.
     */
    private fun scheduleDeadlineWake(frame: Frame, owner: RatingHistoryClient) {
        if (!owner.loading) return
        val remaining = owner.nextDeadlineInMs
        if (remaining == null || remaining <= 0L) {
            frame.keepPainting()
        } else {
            frame.wakeAfter(remaining)
        }
    }

    /**
     * Ask for the one frame that ends the opening loading grace.
     *
     * The loading state is silent before it, so a fast all-time answer never
     * presents a flash. If the answer is slower, this wake is what presents the
     * skeleton at the moment the wait becomes worth acknowledging.
     */
    private fun scheduleOpeningGraceWake(frame: Frame) {
        if (openingWakeAsked) return
        openingWakeAsked = true
        val remaining = OPENING_LOADING_GRACE_MS - (nowMs() - openedAtMs)
        if (remaining > 0L) frame.wakeAfter(remaining)
    }

    /** True once the opening grace has elapsed, so a loading state may paint. */
    private fun loadingVisible(): Boolean =
        nowMs() - openedAtMs >= OPENING_LOADING_GRACE_MS

    /** The monotonic millisecond clock the opening grace is measured on. */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val owner = history()
        val stored = state.read()
        val target = stored?.target
        val targetOpen = target != null && owner.target == target
        val geometry = RatingHistoryOverlay.measure(
            constraints.worldWidth,
            constraints.worldHeight,
            footer = showsFooter(target),
        )
        val regions = HitRegions<ComponentAction>()
        regions.add(geometry.close, ComponentAction(RatingHistoryActions.DISMISS))
        val publication = if (targetOpen) owner.publication else null
        val viewport = if (target != null) viewportFor(stored, publication) else null
        val controls = if (targetOpen && viewport != null) {
            controlsFor(owner, viewport, publication)
        } else {
            RatingHistoryControlsState(retryEnabled = targetOpen && retryEnabled(owner))
        }
        // The plot is the chart's gesture surface. Claiming it here is what keeps
        // a drag inside the chart from becoming the modal's outside press. The
        // controls and Retry have their own rects **outside** the plot and are
        // claimed only while enabled, so neither surface can swallow the other.
        val gestureRegions = ArrayList<Rect>(6)
        gestureRegions += geometry.plot
        if (controls.zoomOutEnabled) gestureRegions += geometry.controls.zoomOut
        if (controls.zoomInEnabled) gestureRegions += geometry.controls.zoomIn
        if (controls.allTimeEnabled) gestureRegions += geometry.controls.allTime
        if (controls.retryEnabled) geometry.retry?.let { gestureRegions += it }
        return Layout(
            hitRegions = regions,
            panel = geometry.panel,
            gestureRegions = gestureRegions,
            // The wheel only reaches a layer through this gate (ScrollRouter);
            // onWheel still accepts only samples inside the plot.
            scrollable = true,
        )
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val constraints = frame.constraints
        val owner = history()
        val stored = state.read()
        val target = stored?.target
        if (target != null) scheduleOpeningGraceWake(frame)
        val targetOpen = target != null && owner.target == target
        val publication = if (targetOpen) owner.publication else null
        // The route's window: the state decision and the controls are about where
        // the chart is going, not about the frame the ease happens to show.
        val settled = if (target != null) viewportFor(stored, publication) else null
        // What to paint is what motion decided; a paint-only frame (a zero delta)
        // keeps the last decision.
        val painted = paintedWindow ?: settled
        val level = displayLevel ?: settled?.let { resolvedLevel(it, publication) }
        val series = if (target != null && level != null) seriesFor(owner, level, target) else null
        val chart = if (target != null) {
            RatingChartRender(
                target = target,
                viewport = painted,
                level = level,
                series = series,
                phase = if (targetOpen) {
                    phaseFor(owner, level, settled, publication, series)
                } else {
                    RatingHistoryPhase.Loading
                },
                publication = publication,
                failure = if (targetOpen) owner.error else null,
                controls = if (targetOpen && settled != null) {
                    controlsFor(owner, settled, publication)
                } else {
                    RatingHistoryControlsState(retryEnabled = targetOpen && retryEnabled(owner))
                },
                popup = popupFor(constraints, painted, series),
                loadingVisible = loadingVisible(),
            )
        } else {
            null
        }

        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = constraints.worldWidth,
            screenH = constraints.worldHeight,
            target = target,
            pointerWorldX = constraints.pointerX,
            pointerWorldY = constraints.pointerY,
            chart = chart,
        )
    }

    // ── The popup: a candle or a flat carry ─────────────────────────────────

    /**
     * The selection the popup names this frame, if any, and the point it hangs
     * from.
     *
     * A pointer platform names the flat carry under the cursor first — its ink is
     * a hairline, so the candle's wider reach must not steal the line's ends — and
     * otherwise the candle under the cursor. A touch platform has no cursor, so it
     * names what the player last tapped, the same two kinds; a tap on empty space
     * clears that, and so does a window that moved.
     */
    private fun popupFor(
        constraints: Constraints,
        viewport: ChartViewport?,
        series: RatingChartSeries?,
    ): RatingHistoryPopup? {
        if (viewport == null || series == null) return null
        val area = overlay.chartArea ?: return null
        val anchorX: Float
        val anchorY: Float
        if (profile.usesPressHover) {
            val tap = tap ?: return null
            anchorX = tap.x
            anchorY = tap.y
        } else {
            val pointerX = constraints.pointerX
            val pointerY = constraints.pointerY
            if (pointerX.isNaN() || pointerY.isNaN()) return null
            if (!area.contains(pointerX, pointerY)) return null
            anchorX = pointerX
            anchorY = pointerY
        }
        // The flat line's ink is a hairline, while the candle's band is a reach
        // that includes the carry's own rating: take the carry first, or a
        // neighbouring candle would steal the line's ends.
        val carry = overlay.carryAt(anchorX, anchorY)
        if (carry != null) {
            return RatingHistoryPopup.Carry(
                carry = carry,
                anchorX = anchorX,
                anchorY = anchorY,
                offsetMinutes = PlatformTimeZone.offsetMinutesAt(carry.fromMs),
            )
        }
        val candle = candleUnderPointer(anchorX, anchorY, viewport, series, area) ?: return null
        return RatingHistoryPopup.Candle(
            candle = candle,
            anchorX = anchorX,
            anchorY = anchorY,
            offsetMinutes = PlatformTimeZone.offsetMinutesAt(candle.startMs),
        )
    }

    /**
     * The candle a pointer at ([worldX], [worldY]) names, or null when none is
     * close enough.
     *
     * The reach is the pointer's: a mouse picks the candle it is over (half a
     * bucket, which is the width of a candle), a finger picks the nearest
     * candle within a finger's width.
     *
     * Vertically the pointer must be over the candle's own range. A pointer far
     * above or below the ink names nothing — the popup names one candle and sits
     * beside the pointer, so a pointer that is nowhere near that candle must not
     * summon it. A candle too short to hit keeps a minimum band, so a flat one
     * stays reachable.
     *
     * A flat carry at the same point is answered first in [popupFor]: the carry's
     * ink is exactly at the pointer, while this band is only a reach.
     */
    private fun candleUnderPointer(
        worldX: Float,
        worldY: Float,
        viewport: ChartViewport,
        series: RatingChartSeries,
        area: RatingChartArea,
    ): RatingCandle? {
        val epochMs = viewport.epochAtPlotX(worldX, area.left, area.width)
        val reachMs = if (profile.usesPressHover) {
            viewport.spanMs * (Fonts.px(TOUCH_REACH_DP) / area.width)
        } else {
            series.bucketMs / 2.0
        }
        val candle = series.candleNear(epochMs, reachMs) ?: return null
        val band = overlay.candleBand(candle) ?: return null
        val middle = (band.start + band.endInclusive) * 0.5f
        val reach = max(band.endInclusive - band.start, Fonts.px(MIN_PICK_BAND_DP)) * 0.5f
        return if (abs(worldY - middle) <= reach) candle else null
    }

    // ── GestureHost ─────────────────────────────────────────────────────────

    /**
     * The rect gestures are measured in: the candle area the chart painted, not
     * the whole plot.
     *
     * The plot also holds the left inset and the rating gutter on its right. Those
     * are chrome: an anchor measured across them holds the instant at the same
     * fraction of the *panel*, which sits beside the pointer rather than under it,
     * and a drag scaled by their width trails the finger. The paint, the popup's
     * hit test and this all read the same area.
     *
     * Before the chart has painted there is no candle area; the plot is then the
     * only frame there is, and with nothing drawn in it the difference cannot be
     * seen.
     */
    private fun gestureArea(): Rect {
        val area = overlay.chartArea ?: return plot
        return Rect(area.left, area.bottom, area.width, area.height)
    }

    override fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean {
        // A control press is a discrete command, not a chart drag. Only the first
        // pointer can claim one; a second finger during a pinch must not turn the
        // chart gesture into a button press.
        if (pressedControl != null) return true
        if (!recognizer.isActive) {
            val stored = state.read()
            val target = stored?.target
            val geometry = RatingHistoryOverlay.measure(
                frame.constraints.worldWidth,
                frame.constraints.worldHeight,
                footer = showsFooter(target),
            )
            val onControl = geometry.controls.rects.any { it.contains(x, y) } ||
                geometry.retry?.contains(x, y) == true
            if (onControl) {
                // The finger leaving the chart for a button takes over: a live
                // coast must not keep moving the window under the control.
                recognizer.stopInertia()
                val owner = history()
                if (target != null) {
                    val targetOpen = owner.target == target
                    val publication = if (targetOpen) owner.publication else null
                    val viewport = viewportFor(stored, publication)
                    val controls = if (targetOpen && viewport != null) {
                        controlsFor(owner, viewport, publication)
                    } else {
                        RatingHistoryControlsState(retryEnabled = targetOpen && retryEnabled(owner))
                    }
                    pressedControl = controlAt(geometry, controls, x, y)
                }
                // A disabled control still absorbs the press: the player pressed
                // chrome it can see, and the chart must not start a gesture from
                // it just because the control happens to be disabled.
                frame.keepPainting()
                return true
            }
        }
        // A second finger re-enters through the arbiter and may land beside the
        // plot; while this press owns the gesture it must keep owning it, or a
        // pinch that starts with one finger on the title band would be lost.
        if (!recognizer.isActive) {
            if (!plot.contains(x, y)) {
                // A press away from the plot dismisses what the popup named.
                tap = null
                return false
            }
            pressedOnPlot = true
            dragged = false
            pressX = x
            pressY = y
        }
        recognizer.begin()
        frame.keepPainting()
        return true
    }

    /**
     * Drag deltas are read from the frame's pointer set in [motion], not from this
     * event: a pinch is a relation between two pointers on the same frame, and the
     * event stream carries one pointer at a time. This override exists so the
     * lifecycle is explicit; the recognizer already owns the press.
     *
     * It also answers one question the release needs: a gesture that moved is a
     * pan or a pinch, never a tap.
     */
    override fun onGestureDrag(x: Float, y: Float, dragging: Boolean, frame: Frame) {
        if (dragging) dragged = true
    }

    override fun onGestureRelease(x: Float, y: Float, frame: Frame): Boolean {
        val control = pressedControl
        if (control != null) {
            pressedControl = null
            // A release that left the control is a cancel, not the command under
            // the finger at lift time.
            if (releasedOver(control, x, y, frame)) applyControl(control, frame)
            frame.keepPainting()
            return true
        }
        recognizer.end()
        // A tap on the plot names the candle under the finger. A tap that found no
        // candle names none, which is how the player dismisses the popup. The
        // travel is the arbiter's own slop: a flick that never reported a drag
        // is still not a tap.
        val travel = hypot(x - pressX, y - pressY)
        if (profile.usesPressHover && pressedOnPlot && !dragged &&
            travel <= GestureArbiter.DEFAULT_SLOP_PX
        ) {
            // The popup hangs beside the point the player chose, so the point is
            // kept — not only the candle, whose place may change with the window.
            tap = Tap(x, y)
        }
        pressedOnPlot = false
        // A release with no fling still has to be seen once more to send the
        // settle's immediate request; a coast asks for its own frames below.
        frame.keepPainting()
        // The claimed release is the chart's even for a tap: it must not fall
        // through to the modal's outside/dismiss path.
        return true
    }

    override fun onGestureCancel(frame: Frame) {
        pressedControl = null
        pressedOnPlot = false
        recognizer.cancel()
        frame.keepPainting()
    }

    /** The control under [x],[y], among only those this frame accepts. */
    private fun controlAt(
        geometry: RatingHistoryGeometry,
        controls: RatingHistoryControlsState,
        x: Float,
        y: Float,
    ): Control? = when {
        controls.retryEnabled && geometry.retry?.contains(x, y) == true -> Control.Retry
        controls.zoomOutEnabled && geometry.controls.zoomOut.contains(x, y) -> Control.ZoomOut
        controls.zoomInEnabled && geometry.controls.zoomIn.contains(x, y) -> Control.ZoomIn
        controls.allTimeEnabled && geometry.controls.allTime.contains(x, y) -> Control.AllTime
        else -> null
    }

    /** True when [x],[y] is still inside [control]'s painted rect. */
    private fun releasedOver(control: Control, x: Float, y: Float, frame: Frame): Boolean {
        val geometry = RatingHistoryOverlay.measure(
            frame.constraints.worldWidth,
            frame.constraints.worldHeight,
            footer = showsFooter(state.read()?.target),
        )
        val rect = when (control) {
            Control.ZoomOut -> geometry.controls.zoomOut
            Control.ZoomIn -> geometry.controls.zoomIn
            Control.AllTime -> geometry.controls.allTime
            Control.Retry -> geometry.retry ?: return false
        }
        return rect.contains(x, y)
    }

    /**
     * Apply one explicit control through the same [ChartGesture] funnel the
     * recognizer uses, then persist the window exactly as a gesture does. A
     * command that the viewport's clamp leaves unchanged (zoom at the floor or
     * all time, All Time while already there) writes nothing.
     *
     * Retry is the one control that does not change the window: it drives E2's
     * [RatingHistoryClient.retry], which clears the failure/backoff once and asks
     * again. The button is only offered while no request is open, so a tap cannot
     * hammer the server.
     */
    private fun applyControl(control: Control, frame: Frame) {
        val owner = history()
        val stored = state.read() ?: return
        val target = stored.target
        if (owner.target != target) return
        if (control == Control.Retry) {
            owner.retry()
            frame.keepPainting()
            return
        }
        val publication = owner.publication
        val current = viewportFor(stored, publication) ?: return
        val gesture = when (control) {
            Control.ZoomOut -> zoomGesture(zoomIn = false)
            Control.ZoomIn -> zoomGesture(zoomIn = true)
            Control.AllTime -> ChartGesture.AllTime
            Control.Retry -> return
        }
        val next = current.applying(gesture, gestureArea().width)
        if (!windowMoved(current, next)) return

        // The window moved under the popup: the selection it named is not the one
        // the player chose any more.
        tap = null
        val level = resolveLevel(next, publication)
        if (level != null) {
            resolveDisplay(owner, level, next, publication)
            driveRequests(
                owner = owner,
                level = level,
                viewport = next,
                publication = publication,
                immediate = true,
            )
            firstWindow = false
        }
        // Written after the rung is chosen, so the window and the density it was
        // drawn at are remembered together.
        persistWindow(frame, stored, target, next, force = true)
        frame.keepPainting()
    }

    /**
     * Both axes of one wheel sample, in libGDX's convention: horizontal pans the
     * time axis and vertical zooms at the cursor. A chart already at a time bound
     * consumes the sample but produces no change.
     *
     * A notch is a **command**, exactly like a zoom press: it composes on the route's
     * window — the one the player has asked for — and the paint eases toward the
     * result. Composing on the asked-for window rather than on the painted one is
     * what makes a fast sequence add up: each notch multiplies the window the
     * previous notches asked for, so ten notches zoom as far as ten notches, instead
     * of each one re-zooming a window that has only caught up part of the way and
     * leaving the wheel sluggish exactly when it is spun. The anchor is a fraction of
     * the painted candle area ([gestureArea]), not of the whole plot.
     */
    override fun onWheel(amountY: Float, x: Float, y: Float, amountX: Float, frame: Frame): Boolean {
        // The surface opts into the wheel (Layout.scrollable), so it owns every
        // sample over its panel — which is now the whole window. Outside the plot
        // there is nothing to pan or zoom, but the sample is still consumed: a
        // wheel over the title or a control must not scroll anything.
        if (!plot.contains(x, y)) return true
        val owner = history()
        val stored = state.read() ?: return true
        val target = stored.target
        if (owner.target != target) return true
        val publication = owner.publication
        var viewport = viewportFor(stored, publication) ?: return true
        val content = gestureArea()
        val gestures = recognizer.wheel(amountY, x, y, amountX, content)
        if (gestures.isEmpty()) return true
        val before = viewport
        for (gesture in gestures) viewport = viewport.applying(gesture, content.width)
        val moved = windowMoved(before, viewport)

        val level = resolveLevel(viewport, publication)
        if (level != null) {
            // A wheel is discrete: the window it leaves is settled, so ask for the
            // final coverage now rather than waiting for a throttle tick.
            resolveDisplay(owner, level, viewport, publication)
            driveRequests(
                owner = owner,
                level = level,
                viewport = viewport,
                publication = publication,
                immediate = true,
            )
            firstWindow = false
        }
        // Written after the rung is chosen: the remembered view is the window *and*
        // the density it was drawn at.
        persistWindow(frame, stored, target, viewport, force = moved)
        frame.keepPainting()
        return true
    }

    // ── Window ──────────────────────────────────────────────────────────────

    /**
     * The route's window as a [ChartViewport], or the publication's all-time
     * bounds when the player has not moved it yet.
     *
     * A stored window is restored exactly (D2 clamps it into the server's bounds);
     * no window means "all time", which is E1's null-window default made concrete.
     * The value is rebuilt from the route store every frame, so the store — not
     * this component — remains the authority.
     *
     * [window] overrides the stored one: the ease passes the range it is showing,
     * so the paint is clamped into exactly the bounds the route's window is.
     */
    private fun viewportFor(
        state: RatingHistoryState?,
        publication: RatingHistoryPublication?,
        window: RatingHistoryWindow? = state?.window,
    ): ChartViewport? {
        val bounds = allTimeBounds(publication)
        if (bounds != null) {
            val (allFrom, allTo) = bounds
            val seeded = if (window != null && window.toEpochMs > window.fromEpochMs) {
                ChartViewport.of(
                    allFrom,
                    allTo,
                    window.fromEpochMs,
                    window.toEpochMs,
                )
            } else {
                ChartViewport.of(allFrom, allTo, allFrom, allTo)
            }
            return seeded.withBounds(allFrom, allTo)
        }
        if (window != null && window.toEpochMs > window.fromEpochMs) {
            return ChartViewport.of(
                window.fromEpochMs,
                window.toEpochMs,
                window.fromEpochMs,
                window.toEpochMs,
            )
        }
        return null
    }

    /**
     * The publication's all-time extent as a **half-open** window,
     * `[first event, last event + 1)`.
     *
     * Every window, candle search and Y-axis fold treats an instant range as
     * `[from, to)`: a candle is in view when its bucket starts before `to`.
     * `last_event_at` is the instant of the last event, not an exclusive end —
     * the event sits at the start of its bucket — so passing it straight through
     * drops the newest candle from the plot and from the axis. The server pads
     * the same bound when it picks tiles (`tiles_covering(first, last + 1,
     * level)`); this is the client's one place to do the same.
     *
     * Null when nothing is published, or when the publication names no extent.
     */
    private fun allTimeBounds(
        publication: RatingHistoryPublication?,
    ): Pair<Double, Double>? {
        val first = publication?.firstEventAt ?: return null
        val last = publication.lastEventAt ?: return null
        return first.toDouble() to (last + 1).toDouble()
    }

    /**
     * The window to paint this frame: the ease's displayed range when it has one,
     * clamped into the same all-time bounds as the route's window. Before the first
     * target it is the route's window itself, so an opened route paints where the
     * store says without an animation nobody asked for.
     */
    private fun paintedWindowFor(
        state: RatingHistoryState?,
        publication: RatingHistoryPublication?,
    ): ChartViewport? =
        if (windowEase.hasValue) {
            viewportFor(
                state,
                publication,
                RatingHistoryWindow(windowEase.fromEpochMs, windowEase.toEpochMs),
            )
        } else {
            viewportFor(state, publication)
        }

    private fun resolveLevel(
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): Int? = RatingChartLevel.select(
        previous = selectedLevel,
        spanMs = viewport.spanMs,
        plotWidthPx = plot.width,
        atAllTime = isAllTime(viewport, publication),
        allTimeLevel = publication?.allTimeLevel,
        fromMs = viewport.fromEpochMs,
        toMs = viewport.toEpochMs,
    ).also { selectedLevel = it }

    /** The same choice without mutating hysteresis state, for a first paint. */
    private fun resolvedLevel(
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): Int? = RatingChartLevel.select(
        previous = selectedLevel,
        spanMs = viewport.spanMs,
        plotWidthPx = plot.width,
        atAllTime = isAllTime(viewport, publication),
        allTimeLevel = publication?.allTimeLevel,
        fromMs = viewport.fromEpochMs,
        toMs = viewport.toEpochMs,
    )

    /**
     * The rung to paint. A new rung takes over at once when the client already
     * holds the whole window at it (a coarsening, or a retained level). A finer
     * rung waits until [RatingHistoryClient.holds] the window, so the coarse
     * candles stay up instead of the plot blanking or mixing two rungs.
     *
     * The first choice after a route (re)opens is the same rule seen backwards:
     * if the requested rung is not held yet, the server's all-time rung is the
     * one coarse level whose tiles the parameterless answer has already delivered
     * for the whole history. Painting it now means a restored window shows real
     * candles immediately instead of waiting for its first tile response.
     */
    private fun resolveDisplay(
        owner: RatingHistoryClient,
        targetLevel: Int,
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): Int {
        val current = displayLevel
        if (current == null) {
            val first = if (holds(owner, targetLevel, viewport)) {
                targetLevel
            } else {
                coarseFallback(owner, targetLevel, viewport, publication)
            }
            displayLevel = first
            return first
        }
        if (current == targetLevel) {
            // The requested rung is the one on screen, but its first tiles may
            // still be arriving. If a coarse level is already held, paint that
            // rather than an empty plot; the next frame re-checks and swaps up.
            if (holds(owner, targetLevel, viewport)) return targetLevel
            val fallback = coarseFallback(owner, targetLevel, viewport, publication)
            if (fallback != targetLevel) displayLevel = fallback
            return displayLevel ?: targetLevel
        }
        if (holds(owner, targetLevel, viewport)) displayLevel = targetLevel
        return displayLevel ?: targetLevel
    }

    /**
     * A retained coarse rung that already holds the whole window, or [target] when
     * there is none. [RatingHistoryPublication.allTimeLevel] is the one rung the
     * client is guaranteed to have addressed for the entire history, so it is the
     * fallback the opening frame can rely on without asking E2 for its level set.
     */
    private fun coarseFallback(
        owner: RatingHistoryClient,
        target: Int,
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): Int {
        val coarse = publication?.allTimeLevel ?: return target
        if (coarse == target || coarse !in 0..RatingHistoryTiles.MAX_TILE_LEVEL) return target
        return if (holds(owner, coarse, viewport)) coarse else target
    }

    private fun holds(owner: RatingHistoryClient, level: Int, viewport: ChartViewport): Boolean {
        val from = floor(viewport.fromEpochMs).toLong()
        val to = ceil(viewport.toEpochMs).toLong()
        if (to <= from) return false
        return owner.holds(level, from, to)
    }

    private fun isAllTime(
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): Boolean {
        val (from, to) = allTimeBounds(publication) ?: return false
        val tolerance = max(1.0, (to - from) * 1e-9)
        return abs(viewport.fromEpochMs - from) <= tolerance &&
            abs(viewport.toEpochMs - to) <= tolerance
    }

    /**
     * Which explicit controls this frame accepts. Zoom is enabled only when the
     * clamped step actually changes the span (so a button at the floor/ceiling
     * does nothing and reads disabled); All Time only when there is a server
     * extent to jump to and the window is not already there. Retry follows E2's
     * error and loading state, so it cannot be tapped while a request is already
     * open.
     */
    private fun controlsFor(
        owner: RatingHistoryClient,
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
    ): RatingHistoryControlsState {
        val boundsKnown = allTimeBounds(publication) != null
        // Without the server's extent there is no meaningful viewport command: a
        // zoom would only rewrite a window over an unknown history, and All Time
        // has no bound to name. Keep them visibly disabled until the publication
        // arrives; Retry remains available on its own error state.
        if (!boundsKnown) {
            return RatingHistoryControlsState(retryEnabled = retryEnabled(owner))
        }
        return RatingHistoryControlsState(
            zoomOutEnabled = zoomMoves(viewport, zoomIn = false),
            zoomInEnabled = zoomMoves(viewport, zoomIn = true),
            allTimeEnabled = !isAllTime(viewport, publication),
            retryEnabled = retryEnabled(owner),
        )
    }

    /**
     * True when this frame has a failure to name, and therefore a footer band:
     * Retry lives inside that band and cannot exist without one. The overlay
     * reads the same answer from [RatingChartRender.failure], so every rect this
     * component derives for a frame — the plot gestures use, the painted band,
     * the Retry hit target — is the same geometry.
     */
    private fun showsFooter(target: RatingHistoryTarget?): Boolean {
        if (target == null) return false
        val owner = history()
        return owner.target == target && owner.error != null
    }

    private fun retryEnabled(owner: RatingHistoryClient): Boolean =
        owner.error?.retryable == true && !owner.loading

    /**
     * One explicit zoom press: exactly the zoom one wheel notch asks for, anchored
     * at the chart's centre — the same [ChartGesture.Zoom] the recognizer emits for
     * a sample with the cursor there, so the button and the mouse cannot disagree
     * about the amount. The viewport's clamp still decides whether it moves.
     */
    private fun zoomGesture(zoomIn: Boolean): ChartGesture =
        recognizer.zoomUnits(if (zoomIn) -1.0 else 1.0)

    /**
     * True when one zoom press in [zoomIn]'s direction changes the clamped span.
     */
    private fun zoomMoves(viewport: ChartViewport, zoomIn: Boolean): Boolean {
        val next = viewport.applying(zoomGesture(zoomIn), 1f)
        val epsilon = max(1e-9, viewport.spanMs * 1e-9)
        return if (zoomIn) {
            next.spanMs < viewport.spanMs - epsilon
        } else {
            next.spanMs > viewport.spanMs + epsilon
        }
    }

    /**
     * The visible state, from E2's publication and the retained series. The
     * publication's four states are authoritative and mutually exclusive:
     * `pending` is publication lag and `not_ready` is an account still being
     * prepared, so neither can ever fall through to the empty copy. On a ready
     * publication a held window with no candle is the distinct loaded-empty
     * state; a window with no coverage yet is still loading.
     */
    private fun phaseFor(
        owner: RatingHistoryClient,
        level: Int?,
        viewport: ChartViewport?,
        publication: RatingHistoryPublication?,
        series: RatingChartSeries?,
    ): RatingHistoryPhase {
        val failure = owner.error
        if (publication == null) {
            return if (failure != null) RatingHistoryPhase.Error else RatingHistoryPhase.Loading
        }
        return when (publication.state) {
            RatingHistoryStatus.Empty -> RatingHistoryPhase.Empty
            RatingHistoryStatus.NotReady -> RatingHistoryPhase.NotReady
            RatingHistoryStatus.Pending -> RatingHistoryPhase.Pending
            RatingHistoryStatus.Ready -> when {
                failure != null -> RatingHistoryPhase.Error
                viewport != null && series != null && series.hasCandleBetween(
                    viewport.fromEpochMs,
                    viewport.toEpochMs,
                ) -> RatingHistoryPhase.Ready

                viewport != null && level != null && holds(owner, level, viewport) -> {
                    val covered = series != null && series.hasCoveredTileBetween(
                        viewport.fromEpochMs,
                        viewport.toEpochMs,
                    )
                    if (covered) RatingHistoryPhase.EmptyView else RatingHistoryPhase.Unavailable
                }

                else -> RatingHistoryPhase.Loading
            }
        }
    }

    /**
     * Push the visible window to E2, but only when the tile run or the rung
     * changed, or once for a settle. The client coalesces and throttles from
     * there; sending the same run every frame would allocate a request plan for
     * no new information.
     */
    private fun driveRequests(
        owner: RatingHistoryClient,
        level: Int,
        viewport: ChartViewport,
        publication: RatingHistoryPublication?,
        immediate: Boolean,
    ): Boolean {
        if (owner.target == null) return false
        // The first publication is the parameterless all-time answer, and it is
        // the only body that names the server's rung or bounds a restored window
        // is clamped into. Asking for tiles before it exists would pick a rung
        // from density alone (a restored all-time window says nothing about its
        // all-time rung), and the fetch is superseded the moment the answer
        // lands. Afterwards, a non-ready publication is an answer (empty /
        // pending / not ready): asking for tiles cannot change it, so the chart
        // does not hammer.
        if (publication?.state != RatingHistoryStatus.Ready) return false
        val from = floor(viewport.fromEpochMs).toLong()
        val to = ceil(viewport.toEpochMs).toLong()
        if (to <= from) return false

        val run = RatingHistoryTiles.tilesCovering(level, from, to)
        val capped = run.first..min(
            run.last,
            run.first + RatingHistoryClient.MAX_VISIBLE_TILES - 1,
        )
        val changed = level != lastRequestLevel || capped != lastRequestRun
        if (!changed && (!immediate || settleRequested)) return false

        owner.requestCoverage(level, from, to, immediate)
        lastRequestLevel = level
        lastRequestRun = capped
        settleRequested = immediate
        return true
    }

    /**
     * Persist the visible `[from, to]` and the rung it is drawn at through the
     * route-state channel. Doubles stay Doubles end to end; epoch milliseconds are
     * never narrowed to `Float`. A default all-time view (no stored window) is not
     * written until the player moves it, so "null means open on all time" keeps
     * working across relaunches. The rung is written only beside a window: at all
     * time the server's own rung is the answer on every open.
     */
    private fun persistWindow(
        frame: Frame,
        stored: RatingHistoryState?,
        target: RatingHistoryTarget,
        viewport: ChartViewport,
        force: Boolean,
    ) {
        if (stored == null || stored.target != target) return
        val current = stored.window
        if (current == null && !force) return
        val next = RatingHistoryWindow(
            fromEpochMs = viewport.fromEpochMs,
            toEpochMs = viewport.toEpochMs,
        )
        // A frame that could not choose a rung (a degenerate plot) must not erase
        // the one the visit already had.
        val level = selectedLevel ?: stored.level
        if (current != null &&
            abs(current.fromEpochMs - next.fromEpochMs) <= WINDOW_EPSILON_MS &&
            abs(current.toEpochMs - next.toEpochMs) <= WINDOW_EPSILON_MS &&
            stored.level == level
        ) {
            return
        }
        frame.emit(
            RouteUiActions.write(
                RatingHistoryCodec,
                RatingHistoryState(target = target, window = next, level = level),
            ),
        )
    }

    // ── Cached series ───────────────────────────────────────────────────────

    /**
     * The flattened draw list for [level], rebuilt only when E2's coverage version
     * or the drawn rung changes. [RatingHistoryClient.coverage] allocates a fresh
     * sorted snapshot per call, which is why the cache is keyed this way rather
     * than read every frame.
     */
    private fun seriesFor(
        owner: RatingHistoryClient,
        level: Int,
        target: RatingHistoryTarget,
    ): RatingChartSeries? {
        if (owner.target != target) return null
        val version = owner.coverageVersion
        if (series == null || seriesVersion != version || seriesLevel != level ||
            seriesTarget != target
        ) {
            series = RatingChartSeries.of(owner.coverage(level))
            seriesVersion = version
            seriesLevel = level
            seriesTarget = target
        }
        return series
    }

    /** True when the two values are the same visible window (not just the same span). */
    private fun windowMoved(before: ChartViewport, after: ChartViewport): Boolean =
        before.fromEpochMs != after.fromEpochMs ||
            before.toEpochMs != after.toEpochMs ||
            before.originEpochMs != after.originEpochMs ||
            before.latestOffsetMs != after.latestOffsetMs

    private companion object {
        /** Two windows closer than this are the same instant, not a new write. */
        const val WINDOW_EPSILON_MS = 0.5

        /**
         * How long the opening wait stays silent before a loading state paints.
         *
         * Above a fast round trip, below the point an empty plot feels broken: a
         * fast all-time answer should go straight to the chart, while a slow one
         * gets a skeleton rather than a one-frame sentence.
         */
        const val OPENING_LOADING_GRACE_MS = 250L

        /**
         * How far from a tap a candle may be and still be named, in design px.
         *
         * A finger is wide; the candles are a few pixels across. This is the
         * reach that makes a tap land on a candle instead of between two.
         */
        const val TOUCH_REACH_DP = 24f

        /**
         * The narrowest band a candle may be picked by, in design px.
         *
         * A candle whose high and low agree is a hairline, and a hairline is not
         * a target. The band is centred on the candle's own range, so the test
         * still asks "is the pointer on this candle" — with room for a candle
         * that has no height to point at.
         */
        const val MIN_PICK_BAND_DP = 24f
    }
}
