package inego.zungjunggame.features.menu

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.Route

/**
 * Everything the legacy [GameMenuOverlay.render] needs beyond the viewport.
 * A provider seam so the component can stay GL-free and a host can drive the
 * context/flags from the live session once this is wired in.
 */
data class GameMenuRenderInput(
    val context: MenuContext = MenuContext.Root,
    val showScoreTable: Boolean = false,
    val showForfeit: Boolean = false,
    val showContinue: Boolean = false,
    /**
     * Whether the card draws the identity badge / account dropdown. False on the
     * chrome `game_menu` card over a running context and on the local play menu:
     * the root menu is the one sign-in entry (see `RouteIds.GAME_MENU`).
     */
    val showIdentity: Boolean = true,
    val signedInLabel: String? = null,
    val eloRating: Double? = null,
    /**
     * What the signed-out badge has to say about the Google sign-in flow
     * ([MenuSignIn]). The menu is the only surface a Google failure reaches, so
     * the flow's state rides with the identity the host already builds. The
     * chooser keeps the special-key row available even when this is
     * [MenuSignIn.Unavailable].
     */
    val signIn: MenuSignIn = MenuSignIn.Ready,
    val showExit: Boolean = GameMenuOverlay.canExit(),
    val showNotices: Boolean = false,
    val hasUnseenNotices: Boolean = false,
)

/**
 * The game menu as a stack component.
 *
 * Geometry comes from the wrapped [GameMenuOverlay] via a [measure] seam, so
 * [layout] is GL-free and headless tests inject a fake. Painting delegates to
 * [GameMenuOverlay.render] with the batch from [Canvas].
 *
 * The card is gesture-owned: the overlay runs its own press/drag/release state
 * machine (rows, scroll body, the identity badge, and its account dropdown), and
 * the [GestureBridge] forwards the pointer and emits the row the release resolved.
 * The identity badge/dropdown live outside the row hit-test, so publishing hit
 * regions alone left them dead.
 *
 * One card, three layers: the root screen (the main menu) and the local play menu
 * render it as their own content, and the `game_menu` modal renders it as chrome
 * over a running context. The rows come from [renderInput], so which of them is up
 * decides what the card shows. The felt behind the card is the runtime's
 * fundamental overlay, not this component's business.
 *
 * One shared widget, three entries, and each entry owns its own card state in the
 * route UI store ([GameMenuCardState]): [draw] hands the entry's stored offset and
 * drawer to the card every frame, and every value the card reports — a release
 * verdict, a drag step, or a consumed wheel — is emitted through that frame's
 * channel when it differs. The card keeps no player-set value, so no entry can
 * leak one into another and no show/hide capture is needed.
 */
class GameMenuComponent(
    private val route: Route,
    private val overlay: GameMenuOverlay = GameMenuOverlay(),
    private val renderInput: (Route) -> GameMenuRenderInput = { GameMenuRenderInput() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> GameMenuGeometry = { _, _ -> overlay.measureGeometry() },
    /** The route's stored card state; absent means this entry kept none. */
    private val state: StatePort<GameMenuCardState> = StatePort { null },
) : GestureBridge<GameMenuVerdict>(
    surface = { overlay },
    // A row resolution is the primary action; the state the same release carries
    // (the drawer closing on selection, a settled offset) goes through the
    // bridge's state channel, so both land in the frame that resolved them.
    actionFor = GameMenuActions::actionFor,
    stateActionFor = { verdict -> cardActionFor(state, verdict) },
    scrollActionFor = { offset -> cardWrite(state, offset, accountDropdownOpen = false) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        cardWrite(state, after, accountDropdownOpen = state.read()?.accountDropdownOpen ?: false)
            ?.let(frame.emit)
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The card (and its open dropdown, which extends past the card) is one
        // gesture region; the overlay resolves the row/badge/dropdown control.
        val gestureRegions: List<Rect> =
            listOfNotNull(geometry.panel, geometry.dropdownItem)

        return Layout(panel = geometry.panel, gestureRegions = gestureRegions, scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val input = renderInput(route)
        val card = state.read()
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = card?.scrollOffset ?: 0f,
            // Never hand an open drawer into a card that is not showing identity:
            // the persisted drawer is meaningless on the chrome card.
            accountDropdownOpen = input.showIdentity && (card?.accountDropdownOpen ?: false),
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            context = input.context,
            showScoreTable = input.showScoreTable,
            showForfeit = input.showForfeit,
            showContinue = input.showContinue,
            showIdentity = input.showIdentity,
            signedInLabel = input.signedInLabel,
            eloRating = input.eloRating,
            signIn = input.signIn,
            showExit = input.showExit,
            showNotices = input.showNotices,
            hasUnseenNotices = input.hasUnseenNotices,
        )
    }
}

/**
 * The write for the state [verdict] reports, or null when it changes nothing. A
 * drawer report alone keeps the stored offset; a drag or settle alone keeps the
 * stored drawer.
 */
private fun cardActionFor(
    state: StatePort<GameMenuCardState>,
    verdict: GameMenuVerdict,
): ComponentAction? {
    val stored = state.read()
    val scroll = verdict.dragScrollOffset ?: verdict.settledScrollOffset
    val drawer = verdict.accountDropdownOpen ?: stored?.accountDropdownOpen ?: false
    return when {
        scroll != null -> cardWrite(state, scroll, drawer)
        verdict.accountDropdownOpen != null -> cardWrite(state, stored?.scrollOffset ?: 0f, drawer)
        else -> null
    }
}

private fun cardWrite(
    state: StatePort<GameMenuCardState>,
    scroll: Float,
    accountDropdownOpen: Boolean,
): ComponentAction? {
    val next = GameMenuCardState(scroll, accountDropdownOpen)
    if (next == state.read()) return null
    return RouteUiActions.write(GameMenuCardCodec, next)
}
