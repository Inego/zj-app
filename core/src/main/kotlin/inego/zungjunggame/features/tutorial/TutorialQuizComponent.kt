package inego.zungjunggame.features.tutorial

import inego.zungjunggame.runtime.appModel
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route

/**
 * The orientation quiz as a stack overlay above the tutorial lesson.
 *
 * The [TutorialQuizOverlay] owns the press machine and resolves a completed press
 * to the control it landed on ([TutorialQuizOverlay.PointerUpResult]) — including
 * the attempt edit or answer it produced; the [GestureBridge] forwards
 * press/drag/release to it and emits the action [TutorialQuizActions.actionFor]
 * maps that verdict to. No control is republished as a hit region, and the widget
 * keeps no authoritative attempt: the attempt rides the action to the host, whose
 * snapshot hands it back.
 *
 * **One claimed rect, two jobs.** The surface fills its layer, so [Layout.panel]
 * and the single gesture region are the layer's own bounds: the pipeline consults
 * the surface for every press inside it, and the widget itself declines a press on
 * the empty felt (which is not a control), so the free-space menu and the layers
 * below still see a miss.
 *
 * The scene is the frame's snapshot (`frame.model.tutorial.quiz`), applied in
 * [motion] (enter tick, first-apply) and again in [draw] (`dt == 0`) so the
 * post-tick paint scene is on the widget this frame. Painting delegates to
 * [TutorialQuizOverlay.render] so the pixels are unchanged.
 */
class TutorialQuizComponent(
    route: Route,
    private val overlay: () -> TutorialQuizOverlay,
) : GestureBridge<TutorialQuizOverlay.PointerUpResult>(
    surface = { overlay() },
    actionFor = TutorialQuizActions::actionFor,
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        val overlay = overlay()
        overlay.apply(frame.appModel().tutorial.quiz, frame.constraints.deltaSec)
        if (overlay.needsFrames()) frame.keepPainting()
    }

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
        val overlay = overlay()
        overlay.apply(frame.appModel().tutorial.quiz, 0f)
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }
}
