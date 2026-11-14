package inego.zungjunggame.features.tutorial

import inego.zungjunggame.features.board.BoardAnchors
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.appModel
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * The tutorial coach / complete panel as a stack overlay above the lesson.
 *
 * The overlay owns the panel's press machine and resolves a completed press to
 * the control it landed on ([TutorialCoachOverlay.PointerUpResult]); the
 * [GestureBridge] forwards press/drag/release to it and emits the action
 * [TutorialCoachActions.actionFor] maps that verdict to. No control is republished
 * as a hit region, so a tap and the action it produces cannot disagree.
 *
 * The panel fills the shell's content zone, which is the one rect published: the
 * pipeline consults the surface for a press inside it, and the widget declines the
 * felt so a board-overlay beat leaves the live table below playable.
 *
 * The scene is the frame's snapshot (`frame.model.tutorial.coach`), applied to the
 * widget in [motion] (with the ticking delta) and again in [draw] (with `dt == 0`,
 * for the post-tick paint scene); [layout] is pure and only publishes the panel.
 * Live-focus geometry is parked from the board's published layout + that snapshot
 * in [draw], then handed to the widget's `render`.
 */
class TutorialCoachComponent(
    route: Route,
    private val overlay: () -> TutorialCoachOverlay,
) : GestureBridge<TutorialCoachOverlay.PointerUpResult>(
    surface = { overlay() },
    actionFor = TutorialCoachActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /** The live lesson board whose published geometry this coach parks on. */
    private val boardRoute = Route.Overlay(RouteIds.TUTORIAL_LESSON).key

    /**
     * Hand the frame's snapshot to the widget: apply the scene, install the parked
     * wall deal, run the leave exit, and report a completed leave or a newly
     * committed wall deal upward.
     */
    override fun motion(frame: Frame) {
        val coach = overlay()
        val values = frame.appModel().tutorial.coach
        if (coach.applyScene(values, frame.constraints.deltaSec)) {
            frame.emit(ComponentAction(TutorialCoachActions.LEAVE_COMPLETE))
        }
        coach.consumeCommittedDeal()?.let { deal ->
            frame.emit(
                ComponentAction(
                    TutorialCoachActions.WALL_DEAL,
                    mapOf(TutorialCoachActions.KEY_DEAL to deal),
                ),
            )
        }
        coach.consumeWallHandoff()?.let { handoff ->
            frame.emit(
                ComponentAction(
                    TutorialCoachActions.WALL_HANDOFF,
                    mapOf(TutorialCoachActions.KEY_HANDOFF to handoff),
                ),
            )
        }
        if (coach.needsFrames()) frame.keepPainting()
    }

    /** Pure: the panel's own bounds, and no live-focus side effects. */
    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val bounds = if (constraints.worldWidth > 1f && constraints.worldHeight > 1f) {
            Rect(0f, 0f, constraints.worldWidth, constraints.worldHeight)
        } else {
            null
        }
        return Layout(panel = bounds, gestureRegions = listOfNotNull(bounds))
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val coach = overlay()
        val values = frame.appModel().tutorial.coach
        // Paint applies the scene it is about to render: motion runs on the
        // pre-tick model (and is skipped for a zero delta), so the post-tick paint
        // scene may not have reached the widget yet. `dt == 0` does not tick.
        coach.applyScene(values, 0f)
        val regions = frame.layouts.of(boardRoute)?.regions
        val handPointer = regions?.containsKey(BoardAnchors.HAND_HOVER) == true
        val c = frame.constraints
        coach.render(
            batch = canvas.batch,
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            liveFocus = tutorialCoachLiveFocus(values, regions),
            handPointerActive = handPointer,
        )
    }
}
