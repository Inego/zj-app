package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.runtime.appModel
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.SpaceChildren
import inego.zungjunggame.ui.SpaceProvider
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.Transform
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.render.ui.AppPalette

/**
 * The tutorial shell's **chrome, navigation, and composition** (left TOC rail /
 * compact drawer / pull tab) as a stack component.
 *
 * This wrapper bridges the shell's gestures to the input pipeline and composes
 * the shell's pixels: it delegates backdrop and chrome to [TutorialShell] and
 * paints the lesson bands the host hands it between them. The shell itself
 * resolves which nav row a release selected.
 *
 * **Two rects, two jobs.** [Layout.panel] is the shell's own bounds — the whole
 * safe-area body, TOC rail included — which is what the pipeline gates the wheel
 * and a drag on; [Layout.space] is the content zone the lesson layers are
 * laid out in. They differ because the rail is chrome the shell owns beside the
 * lesson, not part of it, so publishing one rect for both would either put the
 * wheel out of reach of the TOC or lay the lesson out across the whole screen.
 * On a narrow screen the drawer is drawn over that zone, so the space keeps the
 * full viewport and reserves the points the drawer claims. The top-right menu
 * is shell chrome painted after the lesson content, so it floats over the
 * lesson in every beat and takes no band away from it.
 *
 * **Gesture ownership.** [TutorialShell] owns the press/drag/release state machine
 * (rail and drawer scroll, pull-tab drag, drawer scrim) and reports the lesson a
 * release selected; the [GestureBridge] forwards, so the tap the player makes and
 * the action it produces cannot disagree. No nav row is a hit region: the shell
 * claims every press on its rail, and a drag that ends over a row must not select
 * it — a distinction only the widget's own press machine can draw.
 *
 * **Composition.** As a [SpaceProvider] the shell paints, in order: the backdrop
 * (felt + gutters), band 0 (board / score breakdown / quiz), its own in-space
 * spotlight, band 1 (the coach), then the screen-space chrome. The host owns
 * which entries are in each band and applies [Layout.space] around
 * [SpaceChildren.paint].
 *
 * Geometry arrives through a [measure] seam so [layout] stays GL-free.
 *
 * **Controlled TOC.** The TOC list is [TutorialShellView]; player-set scroll /
 * drawer / pull-tab live in the route UI store. [motion] reconciles a reveal
 * from the view, installs the store value (snapping the drawer on the first
 * application), advances inertia, and emits a write only when the offset moved.
 */
class TutorialShellComponent(
    route: Route,
    private val shell: () -> TutorialShell,
    private val camera: () -> Camera = { OrthographicCamera() },
    private val measure: (Float, Float) -> TutorialShellGeometry = { width, height ->
        val active = shell()
        if (width > 0f && height > 0f) active.layout(width, height)
        active.measureGeometry()
    },
    /** The route's stored TOC state; absent means the player set nothing. */
    private val state: StatePort<TutorialShellState> = StatePort { null },
) : GestureBridge<TutorialShellVerdict>(
    surface = { shell() },
    actionFor = TutorialShellActions::actionFor,
    stateActionFor = { verdict -> shellStateAction(verdict.state, state) },
    scrollActionFor = { offset ->
        val stored = state.read() ?: TutorialShellState()
        shellStateAction(stored.copy(tocScrollY = offset), state)
    },
),
    SpaceProvider {
    override val id: ComponentId = ComponentId(route.key.value)

    private val spotlightGlyph = GlyphLayout()

    /** Last view this instance reconciled; not the view it renders from. */
    private var lastReconcile: ShellReconcileKey? = null

    /** First [motion] of this instance snaps the drawer rather than easing it. */
    private var snapped = false

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)
        val hitRegions = HitRegions<ComponentAction>()
        geometry.menu?.let {
            hitRegions.add(it, ComponentAction(TutorialShellActions.MENU))
        }

        return Layout(
            hitRegions = hitRegions,
            panel = geometry.body,
            scrollable = true,
            space = geometry.content?.let { Transform(it, geometry.reserved) },
        )
    }

    override fun motion(frame: Frame) {
        val view = tutorialShellView(frame.appModel().tutorial)
        val storedAbsent = state.read() == null
        val stored = state.read() ?: TutorialShellState()
        val reconcile = shellReconcile(lastReconcile, view, storedAbsent)
        val shown = shell().show(
            view = view,
            state = stored,
            snap = !snapped,
            reveal = reconcile.reveal,
            resetScroll = reconcile.resetScroll,
        )
        snapped = true
        lastReconcile = ShellReconcileKey(view)
        val after = shell().advance(frame.constraints.deltaSec)
        val next = shown.copy(tocScrollY = after)
        if (next != stored) {
            frame.emit(RouteUiActions.write(TutorialShellCodec, next))
        }
    }

    override fun paintSpace(
        frame: Frame,
        layout: Layout,
        canvas: Canvas,
        children: SpaceChildren,
    ) {
        val view = tutorialShellView(frame.appModel().tutorial)
        val constraints = frame.constraints
        val active = shell()
        active.renderBackdrop(canvas.batch, constraints.worldWidth, constraints.worldHeight)
        children.paint(0)
        canvas.withTransform(layout.space) {
            renderSpotlightHint(
                batch = canvas.batch,
                screenW = layout.space?.rect?.width ?: constraints.worldWidth,
                screenH = layout.space?.rect?.height ?: constraints.worldHeight,
                view = view,
            )
        }
        children.paint(1)
        active.renderChrome(
            batch = canvas.batch,
            camera = camera(),
            pointerWorldX = constraints.pointerX,
            pointerWorldY = constraints.pointerY,
        )
        if (active.drawerAnimating()) frame.keepPainting()
    }

    /**
     * Lightweight spotlight caption while a board lesson exposes its spotlight.
     * Diagram quizzes draw their own highlights inside [TutorialQuizOverlay].
     */
    private fun renderSpotlightHint(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        view: TutorialShellView,
    ) {
        if (!view.hosted) return
        if (!view.showsBoard) return
        if (view.quizVisible || view.coachVisible) return
        val key = view.lastSpotlight ?: return
        if (key.isBlank()) return
        val label = when (key) {
            "river.last" -> L10n.get("tutorial.spotlight.river")
            "hand" -> L10n.get("tutorial.spotlight.hand")
            "wall.dead" -> L10n.get("tutorial.spotlight.wall_dead")
            "wall.live" -> L10n.get("tutorial.spotlight.wall_live")
            else -> L10n.get("tutorial.spotlight.generic")
        }
        Fonts.ensureCreated()
        val font = Fonts.button
        spotlightGlyph.setText(font, label)
        val padX = Fonts.px(14f)
        val padY = Fonts.px(8f)
        val boxW = spotlightGlyph.width + padX * 2f
        val boxH = spotlightGlyph.height + padY * 2f
        val left = (screenW - boxW) * 0.5f
        val bottom = screenH * 0.72f
        // Soft chip (no separate texture — reuse coach colors via font only).
        font.color = AppPalette.tint(AppPalette.accent, 0.95f)
        font.draw(batch, label, left + padX, bottom + boxH - padY)
        font.color = Color.WHITE
    }
}

/**
 * The write for [current], or null when the store already carries it. One value
 * spans scroll, drawer, and pull tab, so a gesture that moved any of them
 * reports the whole state.
 */
private fun shellStateAction(
    current: TutorialShellState,
    state: StatePort<TutorialShellState>,
): ComponentAction? {
    if (current == (state.read() ?: TutorialShellState())) return null
    return RouteUiActions.write(TutorialShellCodec, current)
}
