package inego.zungjunggame.features.local

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.engine.LocalGameConfig
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
 * New Game setup as a stack component.
 *
 * Geometry comes from the wrapped [NewGameSetupOverlay] through a [measure] seam
 * (GL-free); painting delegates to [NewGameSetupOverlay.render]. The draft is the
 * route's own state ([SetupCardState]): [draw] hands the stored draft and offset
 * to the widget every frame, and every value the widget reports — an edited draft,
 * a drag step, or a consumed wheel — is written through the store when it
 * differs. Absence in the store means the entry kept nothing, so the fallback
 * [config] (the host's process draft) is what the card opens with.
 *
 * The card is gesture-owned: the overlay runs its own press/drag/release state
 * machine (rounds radio, bot radio, chicken checkbox, Start/Cancel, body scroll), and the
 * [GestureBridge] forwards the pointer and emits the control the release resolved,
 * rather than re-deriving hit regions that would duplicate that logic.
 */
class NewGameSetupComponent(
    route: Route,
    private val overlay: NewGameSetupOverlay = NewGameSetupOverlay(),
    private val config: () -> LocalGameConfig = { LocalGameConfig() },
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> NewGameSetupGeometry = { _, _ -> overlay.measureGeometry() },
    /**
     * The feature's verdict→action mapping. The local setup starts a game. Defaulted
     * so existing callers are unchanged.
     */
    private val actions: (NewGameSetupVerdict) -> ComponentAction? = NewGameSetupActions::actionFor,
    /** The route's stored draft and scroll; absent means this entry kept none. */
    private val state: StatePort<SetupCardState> = StatePort { null },
) : GestureBridge<NewGameSetupVerdict>(
    surface = { overlay },
    actionFor = { verdict -> cardActionFor(state, config, actions, verdict) },
    scrollActionFor = { offset -> cardWrite(state, config, draft = null, scroll = offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        cardWrite(state, config, draft = null, scroll = after)?.let(frame.emit)
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        // The whole card is one gesture region; the overlay resolves the control.
        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions, scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val card = state.read()
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = card?.scroll ?: 0f,
            draft = card?.draft ?: config(),
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
        )
    }
}

/**
 * The write for the value the verdict reports, or null when it resolves only a
 * feature action or changes nothing. A draft edit keeps the stored offset; a drag
 * keeps the stored draft.
 */
private fun cardActionFor(
    state: StatePort<SetupCardState>,
    config: () -> LocalGameConfig,
    actions: (NewGameSetupVerdict) -> ComponentAction?,
    verdict: NewGameSetupVerdict,
): ComponentAction? = when {
    verdict.draft != null -> cardWrite(state, config, draft = verdict.draft, scroll = null)
    verdict.dragScrollOffset != null ->
        cardWrite(state, config, draft = null, scroll = verdict.dragScrollOffset)
    verdict.settledScrollOffset != null ->
        cardWrite(state, config, draft = null, scroll = verdict.settledScrollOffset)
    else -> actions(verdict)
}

private fun cardWrite(
    state: StatePort<SetupCardState>,
    config: () -> LocalGameConfig,
    draft: LocalGameConfig?,
    scroll: Float?,
): ComponentAction? {
    val stored = state.read()
    val next = SetupCardState(
        draft = draft ?: stored?.draft ?: config(),
        scroll = scroll ?: stored?.scroll ?: 0f,
    )
    if (next == stored) return null
    return RouteUiActions.write(SetupCardCodec, next)
}
