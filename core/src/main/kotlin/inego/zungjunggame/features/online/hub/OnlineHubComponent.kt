package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.online.DEFAULT_SHADOW_RANKED_DRAFT
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.Route
import kotlin.math.max
import kotlin.math.min

/**
 * The draft edits the Shadow Ranked panel reports as [HubVerdict]s.
 *
 * The draft is the hub route's own state, not a global command, so the component
 * writes the whole [OnlineHubState] back through [OnlineHubCodec] exactly as it
 * does a scroll; the feature's `toIntent` never sees these. Null for every other
 * verdict.
 */
private fun draftEdit(verdict: HubVerdict, current: OnlineHubState): ComponentAction? {
    val draft = current.draft ?: DEFAULT_SHADOW_RANKED_DRAFT
    val updated = when (verdict) {
        HubVerdict.SetRoundsOne -> draft.copy(rounds = 1)
        HubVerdict.SetRoundsTwo -> draft.copy(rounds = 2)
        HubVerdict.ToggleChicken -> draft.copy(allowChickenHands = !draft.allowChickenHands)
        else -> return null
    }
    return RouteUiActions.write(OnlineHubCodec, current.copy(draft = updated))
}

/** The live body's primary action, resolved from [view]'s presence and table. */
private fun primaryActionFor(view: OnlineHubView): LiveTabPanel.PrimaryAction =
    LiveTabPanel.resolvePrimaryAction(
        searching = view.searching,
        seated = view.seatedAt != null,
        reserved = view.reserved,
        draining = view.snapshot?.serverShutdown != null,
        storedTableId = view.storedTableId,
    )

/**
 * The unified Online hub's presenter.
 *
 * [OnlineHubOverlay] owns the shell and the two tab panels. This component
 * orchestrates: it publishes the shell's tab/close/rejoin hit regions and the
 * card viewport as the gesture region from the overlay's last fitted geometry
 * (falling back to the pure [OnlineHubOverlay.measure] before the first paint),
 * forwards the body's gestures through the [GestureBridge], and hands the route's
 * [OnlineHubState] and the host-built views in every frame.
 *
 * Discrete taps (tabs, close) travel `Layout.hitRegions`; the body's continuous
 * scroll is the panel's own and is emitted as a `routeUi.write` of the whole
 * hub state, so the tab, both scrolls and the draft stay one value.
 *
 * The card scrolls as one column, so the viewport is the gesture region and a
 * press on a shell control has to be kept out of it ([onGesturePress]) — the
 * control's tap, not a pan, is what that press means.
 */
class OnlineHubComponent(
    route: Route,
    private val overlay: OnlineHubOverlay = OnlineHubOverlay(),
    private val view: () -> OnlineHubView = { OnlineHubView() },
    private val shadowView: () -> ShadowRankedView = { ShadowRankedView() },
    private val camera: () -> Camera = { OrthographicCamera() },
    /** The route's stored tab, both scroll offsets, and the draft. */
    private val state: StatePort<OnlineHubState> = StatePort { null },
) : GestureBridge<HubVerdict>(
        // The lambdas read the constructor parameters, never `this`, so the
        // superclass can be built before this instance exists.
        surface = { overlay.panelFor((state.read() ?: DEFAULT_ONLINE_HUB_STATE).tab) },
        actionFor = { verdict ->
            val current = state.read() ?: DEFAULT_ONLINE_HUB_STATE
            draftEdit(verdict, current)
                ?: verdict.settledScrollOffset?.let { offset ->
                    RouteUiActions.write(OnlineHubCodec, current.withScroll(current.tab, offset))
                }
                ?: OnlineHubActions.actionFor(verdict, primaryActionFor(view()))
        },
        scrollActionFor = { offset ->
            val current = state.read() ?: DEFAULT_ONLINE_HUB_STATE
            RouteUiActions.write(OnlineHubCodec, current.withScroll(current.tab, offset))
        },
    ),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /**
     * The shell controls' visible rects, from the last `layout()`. A press on one
     * belongs to the control even though the card's viewport would otherwise pan.
     */
    private var pressControls: List<Rect> = emptyList()

    private fun currentState(): OnlineHubState = state.read() ?: DEFAULT_ONLINE_HUB_STATE

    override fun motion(frame: Frame) {
        val current = currentState()
        if (overlay.scrollOffset(current.tab) == null) return
        // The body's own value is the offset on screen, not the stored one: it
        // clamps whatever the measured range cannot honour. Adopting it here is
        // what makes an offset the range rejected *gone* rather than dormant — a
        // value kept in the state while nothing could scroll springs back to life
        // the moment the body is tall enough to scroll again, and the control the
        // reader had just pressed jumps up out from under their thumb with it.
        val settled = overlay.advance(current.tab, frame.constraints.deltaSec)
        if (settled == current.scrollFor(current.tab)) return
        frame.emit(RouteUiActions.write(OnlineHubCodec, current.withScroll(current.tab, settled)))
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        // The search indicator and the rejoin band are model-derived; layout and
        // draw read the same view, so a highlight and its region cannot disagree.
        val v = view()
        val geometry = overlay.measureGeometry(constraints.worldWidth, constraints.worldHeight)
            ?: OnlineHubOverlay.measure(
                constraints.worldWidth,
                constraints.worldHeight,
                currentState(),
                v.searching,
                rejoinAvailable = v.rejoinAvailable,
            )

        val regions = HitRegions<ComponentAction>()
        // The card claims the presses no control answers for. Without it the hub's
        // dead space resolves as Outside (see [inego.zungjunggame.input.HitResolver]),
        // which is its `outside()` — so a tap beside a control closed the hub.
        regions.add(geometry.panel, ComponentAction(OnlineHubActions.ABSORB), rank = CARD_RANK)
        // Every control is published only for the part of it the card still shows:
        // the column scrolls, so a tab or the close button can be pushed past the
        // rim, and a tap on the scrim must not hit the invisible remainder.
        val controls = ArrayList<Rect>(6)
        fun control(rect: Rect?, action: String) {
            val visible = rect?.let { visiblePart(it, geometry.viewport) } ?: return
            controls += visible
            regions.add(visible, ComponentAction(action), CONTROL_RANK)
        }
        control(geometry.rejoin, OnlineHubActions.REJOIN)
        control(geometry.liveTab, OnlineHubActions.SELECT_LIVE_TAB)
        control(geometry.shadowTab, OnlineHubActions.SELECT_SHADOW_TAB)
        control(geometry.leaderboard, OnlineHubActions.OPEN_LEADERBOARD)
        // The rating is the viewer's own history link; it is published only when
        // there is a value to print, so the row has no invisible target.
        if (v.eloRating != null) control(geometry.rating, OnlineHubActions.OPEN_RATING_HISTORY)
        control(geometry.close, OnlineHubActions.DISMISS)
        pressControls = controls

        return Layout(
            hitRegions = regions,
            panel = geometry.panel,
            gestureRegions = listOf(geometry.viewport),
            scrollable = true,
        )
    }

    /**
     * A press on a shell control is that control's, not the card's: decline it so
     * the pipeline resolves the tap against [Layout.hitRegions]. Every other press
     * inside the viewport scrolls the whole column. See [control].
     */
    override fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean {
        if (pressControls.any { it.contains(x, y) }) return false
        return super.onGesturePress(x, y, frame)
    }

    /** [rect] intersected with [bounds], or null when none of it is still shown. */
    private fun visiblePart(rect: Rect, bounds: Rect): Rect? {
        val left = max(rect.left, bounds.left)
        val bottom = max(rect.bottom, bounds.bottom)
        val right = min(rect.right, bounds.right)
        val top = min(rect.top, bounds.top)
        if (right <= left || top <= bottom) return null
        return Rect(left, bottom, right - left, top - bottom)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            state = currentState(),
            view = view(),
            shadowView = shadowView(),
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }

    private companion object {
        /** A control outranks the card it sits on. */
        const val CONTROL_RANK = 0
        const val CARD_RANK = 1
    }
}
