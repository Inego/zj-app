package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.features.board.BoardAnchors
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.appModel
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.Route

/**
 * The inline hand-end score breakdown as a stack component.
 *
 * Geometry comes from the wrapped [ScoreBreakdownOverlay] through a [measure]
 * seam (GL-free); painting delegates to [ScoreBreakdownOverlay.render].
 * [AppModel.scoreBreakdown][inego.zungjunggame.runtime.AppModel.scoreBreakdown]
 * is the content: this component applies it each motion, layout, and draw.
 * Null clears the same fields show fills. Hosts only announce the route.
 *
 * The card's gestures are the [GestureBridge]'s: the overlay owns the
 * press/drag/release machine (body scroll, card park, fold toggle, footer chips)
 * and [ScoreBreakdownActions.actionFor] maps what a release resolved. The pattern
 * rows are the one exception — the card hands those presses back — so they stay
 * hit regions.
 *
 * Where the player parked the card, whether they folded it, and how far they
 * scrolled its body are the three things the card does not own: all are read from
 * the display preferences — the park and the body scroll when the card appears, the
 * fold every frame, because the card's geometry depends on it — and all are written
 * back through the effect when the player moves, folds, or scrolls it, so the
 * arrangement outlives the card, the hand, and the process.
 */
class ScoreBreakdownComponent(
    route: Route,
    private val preferences: StateSlot<UiPreferences>,
    private val overlay: ScoreBreakdownOverlay = ScoreBreakdownOverlay(),
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> ScoreBreakdownGeometry = { _, _ -> overlay.measureGeometry() },
) : GestureBridge<ScoreBreakdownOverlay.PointerUpResult>(
    surface = { overlay },
    actionFor = ScoreBreakdownActions::actionFor,
    scrollActionFor = ScoreBreakdownActions::scrollActionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /** The live lesson board that publishes the pane this card is bounded by. */
    private val boardRoute = Route.Overlay(RouteIds.TUTORIAL_LESSON).key

    override fun motion(frame: Frame) {
        applyContent(frame)
        val before = overlay.scrollOffset()
        if (overlay.advance(frame.constraints.deltaSec) == before) return
        frame.emit(ScoreBreakdownActions.scrollActionFor(overlay.scrollOffset()))
    }

    override fun layout(frame: Frame): Layout {
        applyContent(frame)
        val constraints = frame.constraints
        // The fold is the model's, and the card's geometry follows it (a folded card
        // is a headline and a Total), so it is handed in before measuring: hit
        // regions and pixels then describe the same fold, in the frame the player
        // pressed.
        val parked = preferences.requireValue()
        overlay.setCollapsed(parked.breakdownCollapsed)
        // The park is the model's too, but a live drag owns it until release, so
        // the stored place is handed in only while no block gesture is in flight.
        if (!overlay.dragging) {
            overlay.restorePark(parked.breakdownParkX, parked.breakdownParkY)
        }
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // Deep-links stay on the click path: the card declines those presses, so the
        // region is the only thing that can resolve them.
        val regions = HitRegions<ComponentAction>()
        for ((rect, pageId) in geometry.patternInfos) {
            regions.add(
                rect,
                ComponentAction(
                    ScoreBreakdownActions.PATTERN_INFO,
                    mapOf(ScoreBreakdownActions.KEY_PAGE_ID to pageId),
                ),
            )
        }

        val block = geometry.block ?: geometry.panel
        return Layout(hitRegions = regions, panel = block, gestureRegions = listOfNotNull(block), scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        applyContent(frame)
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = preferences.requireValue().breakdownScrollY,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            bounds = cardBounds(frame),
        )
        if (overlay.foldAnimating()) frame.keepPainting()
    }

    /**
     * The rect the card may occupy.
     *
     * A full-window card (free play, online) returns null, meaning the shared
     * safe-area viewport. A card composed into the tutorial's content space is
     * bounded by the live table's pane instead: the player's game area, which
     * the board publishes as [BoardAnchors.PANE]. That pane is already inside
     * the safe area, so re-applying the window insets would leave a visible
     * margin; and the coach column beside the pane, like the Back / Next band
     * under a stacked table, is chrome the card must not cover. The board
     * derives the pane for this frame, so the card follows a resize on the
     * frame the board does.
     */
    private fun cardBounds(frame: Frame): OverlayViewport.Rect? {
        if (frame.space == null) return null
        val c = frame.constraints
        val pane = frame.layouts.of(boardRoute)?.regions?.get(BoardAnchors.PANE)
            ?: return OverlayViewport.Rect(
                left = 0f,
                bottom = 0f,
                width = c.worldWidth.coerceAtLeast(1f),
                height = c.worldHeight.coerceAtLeast(1f),
            )
        return OverlayViewport.Rect(
            left = pane.left,
            bottom = pane.bottom,
            width = pane.width.coerceAtLeast(1f),
            height = pane.height.coerceAtLeast(1f),
        )
    }

    private fun applyContent(frame: Frame) {
        overlay.apply(frame.appModel().scoreBreakdown)
    }
}
