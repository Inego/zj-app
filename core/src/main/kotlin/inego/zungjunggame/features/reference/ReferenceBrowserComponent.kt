package inego.zungjunggame.features.reference

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.persist.ReferenceNavCodec
import inego.zungjunggame.persist.ReferenceNavState
import inego.zungjunggame.render.reference.ReferenceCatalog
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
 * The full-screen Zung Jung reference browser as a stack component.
 *
 * The browser **replaces** the table, so [Layout.panel] is the whole viewport
 * and [Layout.scrollable] is true: the input pipeline routes both the wheel and
 * the drag to the wrapped [ReferenceBrowserOverlay], whose widgets own the TOC /
 * article / scrollbar offsets.
 *
 * The overlay is a controlled view: [draw] hands the route's stored navigation in
 * every frame, and the browser reports what it resolved — a control on a release
 * verdict, or the offsets a drag, settle, or wheel left behind, read back through
 * [ReferenceBrowserOverlay.navigation] — through the same frame's channel. The
 * component writes any value that differs from the store and maps release verdicts
 * to feature actions, so navigation stays on the `ComponentAction → Intent`
 * channel and the store is the only owner of where the browser is. Discrete taps
 * are not re-derived from measured geometry, so a tap and its action cannot
 * disagree.
 *
 * The browser is host-owned and long-lived, so the component neither constructs
 * nor disposes it.
 */
class ReferenceBrowserComponent(
    route: Route,
    private val overlay: () -> ReferenceBrowserOverlay,
    private val camera: () -> Camera = { OrthographicCamera() },
    /** The route's stored navigation; absent means this entry kept none. */
    private val state: StatePort<ReferenceNavState> = StatePort { null },
    private val canGoBack: () -> Boolean = { false },
) : GestureBridge<ReferenceVerdict>(
    surface = { overlay() },
    // A release that resolves a control carries a feature action; the offsets the
    // same gesture left behind are read back off the surface and ride the state
    // channel beside it.
    actionFor = ReferenceActions::actionFor,
    stateActionFor = { referenceNavAction(overlay(), state) },
    scrollActionFor = { referenceNavAction(overlay(), state) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    override fun motion(frame: Frame) {
        overlay().advance(frame.constraints.deltaSec)
        referenceNavAction(overlay(), state)?.let(frame.emit)
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val panel =
            if (constraints.worldWidth > 1f && constraints.worldHeight > 1f) {
                Rect(0f, 0f, constraints.worldWidth, constraints.worldHeight)
            } else {
                null
            }
        return Layout(panel = panel, scrollable = true)
    }

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay().render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            nav = state.read() ?: defaultNavigation(),
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            canGoBack = canGoBack(),
        )
        if (overlay().chromeAnimating()) frame.keepPainting()
    }

    /**
     * The navigation the browser opens at when the entry kept none: Rules at its
     * default page, top of both panes, drawer closed.
     */
    private fun defaultNavigation(): ReferenceNavState = ReferenceNavState(
        section = ReferenceCatalog.Section.Rules,
        pageId = ReferenceCatalog.defaultPageId(ReferenceCatalog.Section.Rules),
    )
}

/**
 * The navigation the surface is currently showing as a write, or null when the
 * store already carries it.
 */
private fun referenceNavAction(
    overlay: ReferenceBrowserOverlay,
    state: StatePort<ReferenceNavState>,
): ComponentAction? {
    // Before the first paint the overlay has not applied the owner's value, so its
    // readback is a construction default and writing it would overwrite a restored
    // navigation (motion runs before draw on a cold start).
    if (!overlay.hasAppliedNavigation()) return null
    val current = overlay.navigation()
    if (current == state.read()) return null
    return RouteUiActions.write(ReferenceNavCodec, current)
}
