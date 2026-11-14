package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.render.TableFelt
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.ListItem
import inego.zungjunggame.render.ui.ListItemStatusTint
import inego.zungjunggame.render.ui.TocBrowserColumn
import inego.zungjunggame.tutorial.LessonPlayStatus
import inego.zungjunggame.tutorial.TutorialNavEntry
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * What a completed shell gesture resolved: the lesson a TOC row selected, or the
 * shell's own state having moved.
 *
 * [TutorialShellActions.actionFor] maps [Select] to the nav action; [Changed] has
 * no feature command, so only the state channel reads it.
 */
sealed interface TutorialShellVerdict {
    val state: TutorialShellState

    /** A TOC row was tapped: start this lesson. */
    data class Select(
        val lessonId: String,
        override val state: TutorialShellState,
    ) : TutorialShellVerdict

    /** The gesture scrolled the list, dragged the pull tab, or toggled the drawer. */
    data class Changed(override val state: TutorialShellState) : TutorialShellVerdict
}

/**
 * Full-screen **tutorial shell**: left lesson TOC (or compact drawer + pull tab),
 * **content zone = the live lesson** (board / coach / quiz).
 *
 * Active for the whole [inego.zungjunggame.ui.stack.SessionKind.Tutorial] session —
 * a peer mode to free-play, not an overlay. The shell paints the context's
 * top-right **menu** after the lesson content, so it floats over every beat;
 * Esc, system back, or the touch empty-felt tap remain accelerators. Reference
 * keeps its × because it sits over a session.
 * No top title band — content uses the full shell height under safe-area padding.
 *
 * Hosts draw lesson content through the shell's published `Layout.space`, so
 * table/coach/quiz lay out in content-local coordinates (0..[contentW] ×
 * 0..[contentH]).
 *
 * **Controlled view.** The TOC list and selection come from the frame's
 * [TutorialShellView]; the player-set scroll / drawer / pull-tab come from the
 * store, handed in through [show]. Every gesture reports the state it resolved
 * on the [TutorialShellVerdict]. The widget keeps only the read view, the
 * transient drag, and the drawer's animation.
 *
 * Its gestures are a [GestureSurface] whose verdict is the lesson a release
 * selected or [TutorialShellVerdict.Changed], which
 * [TutorialShellActions.actionFor] maps to the nav action.
 */

/**
 * GL-free snapshot of the shell's rects after the last layout: the body it
 * owns input over, the content zone its lesson layers are laid out in, and the
 * points inside that zone the shell keeps.
 */
data class TutorialShellGeometry(
    /**
     * The shell's own bounds (safe-area body, chrome included): the rect the shell
     * layer owns input and the wheel over. Null until the shell is active and laid
     * out, because an inactive shell covers nothing.
     */
    val body: Rect?,
    /** The lesson content zone: the local space the lesson layers are laid out in. */
    val content: Rect?,
    /**
     * Points inside [content] where a press belongs to the shell. The lesson
     * viewport stays [content]; these points are withheld from it.
     */
    val reserved: List<Rect> = emptyList(),
    /** The top-right menu control, in screen space; null while the shell is inactive. */
    val menu: Rect? = null,
)

class TutorialShell : Disposable, GestureSurface<TutorialShellVerdict> {
    private val tocColumn = TocBrowserColumn()

    /** The context's game-menu control; painted over the lesson in every beat. */
    private val menuButton = CloseButton(CloseButton.Style.Menu)

    /** True while a tutorial lesson session is hosted. */
    var active: Boolean = false
        private set

    private var entries: List<TutorialNavEntry> = emptyList()
    private var selectedRowId: String = ""
    private var dragOnChrome = false

    /**
     * The frame's TOC offset, installed by [applyState] from the owner and updated
     * by a gesture or rebuild with the value it just resolved. A read view for
     * hit tests and paint — the next owner value overwrites it — never an owner.
     */
    private var frameScrollY: Float = 0f

    var contentLeft = 0f
        private set
    var contentBottom = 0f
        private set
    var contentW = 0f
        private set
    var contentH = 0f
        private set

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private fun whitePixel(): Texture = WhitePixel.get()

    /**
     * Install this frame's model-derived TOC and the store's player-set values.
     * [snap] settles the drawer without easing (first application of a component
     * instance). [reveal] brings the selected row into view; [resetScroll] starts
     * that reveal from zero.
     *
     * @return the TOC state the frame is rendering, so the presenter can emit a
     *   store write when a reveal moved the offset.
     */
    fun show(
        view: TutorialShellView,
        state: TutorialShellState,
        snap: Boolean,
        reveal: Boolean,
        resetScroll: Boolean,
    ): TutorialShellState {
        if (snap) {
            cancelGesture()
        }
        active = view.hosted
        entries = view.entries
        selectedRowId = view.selectedLessonId
        val items = entries.map { it.toListItem() }
        frameScrollY = tocColumn.setItems(
            items = items,
            selectedId = selectedRowId,
            currentScrollY = if (resetScroll) 0f else state.tocScrollY,
            resetScroll = resetScroll,
            revealSelection = reveal,
        )
        tocColumn.pullTabYFrac = state.pullTabYFrac
        tocColumn.setDrawerOpen(state.drawerOpen, snap = snap)
        return frameState()
    }

    /**
     * True if this pointer should be handled by the TOC chrome rather than the
     * lesson (rail, pull tab, open drawer, or compact content-zone scrim that
     * closes the drawer). Drives the desktop cursor only; routing is the
     * pipeline's, and the widget's own [onPointerDown] answer.
     */
    fun hitTestsChrome(worldX: Float, worldY: Float): Boolean {
        return active && (tocColumn.hitsColumn(worldX, worldY) || menuButton.contains(worldX, worldY))
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        if (!active) return false
        val next = tocColumn.onScroll(amountY, x, y) ?: return false
        frameScrollY = next
        return true
    }

    /** The offset a consumed wheel settled at; what lets the bridge report it. */
    override fun scrollOffset(): Float = frameScrollY

    override fun scrollCoasting(): Boolean = tocColumn.list.isCoasting

    /** Advance the TOC list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float {
        frameScrollY = tocColumn.advance(deltaSec)
        return frameScrollY
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        if (!active) return false
        dragOnChrome = tocColumn.onPointerDown(x, y)
        return dragOnChrome
    }

    override fun onPointerDrag(x: Float, y: Float): TutorialShellVerdict? {
        if (!dragOnChrome) return null
        frameScrollY = tocColumn.onPointerDrag(y)
        return TutorialShellVerdict.Changed(frameState())
    }

    /**
     * @return the lesson a release selected, or [TutorialShellVerdict.Changed] when
     *   it only scrolled, dragged the pull tab, or toggled the drawer.
     */
    override fun onPointerUp(x: Float, y: Float): TutorialShellVerdict {
        if (!active || !dragOnChrome) {
            dragOnChrome = false
            return TutorialShellVerdict.Changed(frameState())
        }
        dragOnChrome = false
        val result = tocColumn.onPointerUp(x, y)
        if (result.wasDrag) {
            result.settledScrollOffset?.let { frameScrollY = it }
            return TutorialShellVerdict.Changed(frameState())
        }
        if (result.drawerToggled) return TutorialShellVerdict.Changed(frameState())
        val id = result.selectedId ?: return TutorialShellVerdict.Changed(frameState())
        selectedRowId = id
        val resolved = frameState()
        return when (val entry = entries.firstOrNull { it.rowId == id }) {
            is TutorialNavEntry.Lesson -> TutorialShellVerdict.Select(entry.lessonId, resolved)
            else -> TutorialShellVerdict.Changed(resolved)
        }
    }

    override fun cancelGesture() {
        dragOnChrome = false
        tocColumn.cancelGesture()
    }

    /** Compact TOC drawer is still easing open or closed. */
    fun drawerAnimating(): Boolean = tocColumn.isDrawerAnimating()

    /**
     * Layout shell + content zone for this frame. Call before drawing lesson content.
     */
    fun layout(screenW: Float, screenH: Float) {
        if (!active) {
            contentLeft = 0f
            contentBottom = 0f
            contentW = screenW
            contentH = screenH
            return
        }
        Fonts.ensureCreated()
        val safe = SafeArea.current()
        panelLeft = safe.left
        panelBottom = safe.bottom
        panelW = (screenW - safe.horizontal).coerceAtLeast(1f)
        panelH = (screenH - safe.vertical).coerceAtLeast(1f)

        // Full body under safe-area padding — no top "Lessons" title band and no
        // gutter around the lesson surface. The shell backdrop is the same plate
        // outside this body, so any inset here reads as a dark border around the
        // window's right, bottom and left edges.
        val bodyBottom = panelBottom
        val bodyTop = panelBottom + panelH
        val bodyH = max(Fonts.px(80f), bodyTop - bodyBottom)
        val bodyLeft = panelLeft
        val bodyW = panelW

        tocColumn.layout(
            panelLeft = panelLeft,
            panelW = panelW,
            bodyLeft = bodyLeft,
            bodyBottom = bodyBottom,
            bodyW = bodyW,
            bodyH = bodyH,
            screenW = screenW,
            screenH = screenH,
        )
        contentLeft = tocColumn.contentLeft
        contentBottom = tocColumn.contentBottom
        contentW = tocColumn.contentW
        contentH = tocColumn.contentH

        // One menu control for the whole tutorial context, floating over the
        // lesson's top-right corner like the board menu in free play. It claims
        // no band: the lesson keeps the full content zone behind it.
        menuButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH)
    }

    /** GL-free snapshot of the shell's rects from the last [layout]. */
    fun measureGeometry(): TutorialShellGeometry {
        val body = if (active && panelW > 1f && panelH > 1f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val content = if (contentW > 1f && contentH > 1f) {
            Rect(contentLeft, contentBottom, contentW, contentH)
        } else {
            null
        }
        val menu = if (active && menuButton.side > 1f) {
            Rect(menuButton.leftX, menuButton.bottomY, menuButton.side, menuButton.side)
        } else {
            null
        }
        return TutorialShellGeometry(
            body = body,
            content = content,
            reserved = tocColumn.pressReservedRects(),
            menu = menu,
        )
    }

    /**
     * Draw shell chrome (TOC rail / drawer / pull tab / menu). Call **after**
     * lesson content so the compact drawer and the menu paint above the table.
     */
    fun renderChrome(
        batch: SpriteBatch,
        camera: Camera,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        if (!active) return
        Fonts.ensureCreated()
        val px = whitePixel()
        tocColumn.render(batch, camera, px, frameScrollY, pointerWorldX, pointerWorldY)
        menuButton.render(batch, px, pointerWorldX, pointerWorldY)
        updateDesktopCursor(pointerWorldX, pointerWorldY)
    }

    /**
     * Draw the full-screen shell backdrop (edge-to-edge under cutouts).
     *
     * Left of the content zone: the dark TOC rail and its divider. Inside the
     * content zone: [TableFelt], edge to edge on the safe area's right and
     * bottom. A board-overlay coach paints only its dim bands and relies on this
     * fill for the felt; without it the live table sat on the dark shell color.
     */
    fun renderBackdrop(batch: SpriteBatch, screenW: Float, screenH: Float) {
        if (!active) return
        val px = whitePixel()
        batch.setColor(AppPalette.panel)
        batch.draw(px, 0f, 0f, screenW, screenH)
        // Content zone is always the green table (board, coach, quiz).
        if (contentW > 1f && contentH > 1f) {
            batch.setColor(TableFelt.color)
            batch.draw(px, contentLeft, contentBottom, contentW, contentH)
        }
        batch.setColor(Color.WHITE)
    }

    private fun updateDesktopCursor(pointerWorldX: Float, pointerWorldY: Float) {
        if (!InputProfile.detect().usesHover) return
        if (pointerWorldX.isNaN() || pointerWorldY.isNaN()) {
            UiCursor.arrow()
            return
        }
        if (hitTestsChrome(pointerWorldX, pointerWorldY)) {
            UiCursor.hand()
        }
        // Content zone cursor left to table/coach hosts.
    }

    private fun frameState(): TutorialShellState = TutorialShellState(
        tocScrollY = frameScrollY,
        drawerOpen = tocColumn.drawerOpen,
        pullTabYFrac = tocColumn.pullTabYFrac,
    )

    override fun dispose() {
        tocColumn.dispose()
    }

    private fun TutorialNavEntry.toListItem(): ListItem = when (this) {
        is TutorialNavEntry.ChapterHeader -> ListItem(
            id = rowId,
            label = L10n.get(titleKey),
            level = 0,
            selectable = false,
        )
        is TutorialNavEntry.Lesson -> {
            val (glyph, tint) = statusChrome(status)
            ListItem(
                id = rowId,
                label = L10n.get(titleKey),
                number = glyph,
                level = 1,
                selectable = true,
                statusTint = tint,
            )
        }
    }

    private fun statusChrome(status: LessonPlayStatus): Pair<String, ListItemStatusTint> =
        when (status) {
            LessonPlayStatus.Never -> "○" to ListItemStatusTint.Muted
            LessonPlayStatus.Partial -> "◑" to ListItemStatusTint.Warm
            LessonPlayStatus.Completed -> "✓" to ListItemStatusTint.Success
        }

}
