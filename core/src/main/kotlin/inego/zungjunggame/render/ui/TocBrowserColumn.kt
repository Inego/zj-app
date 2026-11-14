package inego.zungjunggame.render.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileEasing
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * Shared **left TOC** chrome used by full-screen browsers (Reference, Tutorial).
 *
 * - **Side-by-side** (desktop landscape): fixed left column with [list], content
 *   region on the right ([contentLeft]…[contentH]).
 * - **Compact** (Android always; desktop portrait): full-width content; TOC is a
 *   sliding left drawer with an edge **pull tab** (same interaction as Reference).
 *
 * Owns layout, drawer animation, pull-tab drag, [ScrollableItemList] hit
 * routing, and the rebuild scroll policy ([setItems]). Hosts draw the content
 * pane and any optional column header (via [headerHeight] + the [render]
 * `drawHeader` callback) themselves.
 */
class TocBrowserColumn : Disposable {
    val list = ScrollableItemList()

    /** True after last [layout] when compact drawer mode is active. */
    var compactMode: Boolean = false
        private set

    /** Desired drawer open state (compact only). */
    var drawerOpen: Boolean = false

    /**
     * Vertical placement of the edge pull tab within the body: 0 = bottom, 1 = top.
     * Persisted as a ratio so it survives resize / restore.
     */
    var pullTabYFrac: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var drawerProgress = 0f

    // Body rect (under title band) provided by host.
    private var bodyLeft = 0f
    private var bodyBottom = 0f
    private var bodyW = 0f
    private var bodyH = 0f
    private var bodyTop = 0f

    // Safe panel (for pull clamp).
    private var panelLeft = 0f
    private var panelW = 0f

    // Side-by-side TOC column / compact drawer panel.
    private var colLeft = 0f
    private var colBottom = 0f
    private var colW = 0f
    private var colH = 0f

    // Content pane for the host (article / lesson detail).
    var contentLeft = 0f
        private set
    var contentBottom = 0f
        private set
    var contentW = 0f
        private set
    var contentH = 0f
        private set

    /**
     * Optional host-drawn header band at the top of the column (Reference's
     * section tabs). Reserved from the list area; the host draws into
     * [headerLeft]…[headerH] from the [render] `drawHeader` callback.
     */
    var headerHeight: Float = 0f

    var headerLeft = 0f
        private set
    var headerBottom = 0f
        private set
    var headerW = 0f
        private set
    var headerH = 0f
        private set

    // Pull tab (compact).
    private var pullLeft = 0f
    private var pullBottom = 0f
    private var pullW = 0f
    private var pullH = 0f
    private var pullDragLastY = Float.NaN
    private var pullDragMoved = false

    private var pullTabShapeTex: Texture? = null
    private var drag: Drag = Drag.None

    private enum class Drag {
        None,
        Toc,
        PullTab,
        Scrim,
    }

    /**
     * Compute column + content rects for this frame.
     * Call before [render] and before host draws content into [contentLeft]….
     */
    fun layout(
        panelLeft: Float,
        panelW: Float,
        bodyLeft: Float,
        bodyBottom: Float,
        bodyW: Float,
        bodyH: Float,
        screenW: Float,
        screenH: Float,
    ) {
        this.panelLeft = panelLeft
        this.panelW = panelW
        this.bodyLeft = bodyLeft
        this.bodyBottom = bodyBottom
        this.bodyW = bodyW
        this.bodyH = bodyH
        this.bodyTop = bodyBottom + bodyH

        compactMode = isCompactMode(screenW, screenH)
        if (compactMode) {
            updateDrawerAnim()
            val eased = TileEasing.easeOutCubic(drawerProgress)
            colBottom = bodyBottom
            colH = bodyH
            colW = compactDrawerWidth()
            colLeft = bodyLeft - colW * (1f - eased)
            // Content is always full body in compact (drawer overlays).
            contentLeft = bodyLeft
            contentBottom = bodyBottom
            contentW = bodyW
            contentH = bodyH
            layoutEdgePullTab()
        } else {
            drawerProgress = if (drawerOpen) 1f else 0f
            pullW = 0f
            pullH = 0f
            val gap = Fonts.px(12f)
            colW = (bodyW * 0.32f).coerceIn(Fonts.px(168f), Fonts.px(320f))
                .coerceAtMost(bodyW * 0.45f)
            colLeft = bodyLeft
            colBottom = bodyBottom
            colH = bodyH
            contentLeft = bodyLeft + colW + gap
            contentBottom = bodyBottom
            contentW = (bodyW - colW - gap).coerceAtLeast(Fonts.px(120f))
            contentH = bodyH
        }

        // Header band sits directly above the list; list height shrinks by it.
        val pad = Fonts.px(8f)
        val colTop = colBottom + colH
        headerLeft = colLeft + pad
        headerW = (colW - pad * 2f).coerceAtLeast(1f)
        headerH = headerHeight
        headerBottom = colTop - pad * 0.5f - headerHeight
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        whitePixel: Texture,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        drawHeader: ((SpriteBatch, Texture) -> Unit)? = null,
    ) {
        if (compactMode) {
            renderCompact(batch, camera, whitePixel, scrollY, pointerWorldX, pointerWorldY, drawHeader)
        } else {
            renderSideBySide(batch, camera, whitePixel, scrollY, pointerWorldX, pointerWorldY, drawHeader)
        }
    }

    private fun renderSideBySide(
        batch: SpriteBatch,
        camera: Camera,
        whitePixel: Texture,
        scrollY: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
        drawHeader: ((SpriteBatch, Texture) -> Unit)?,
    ) {
        // Column plate so the TOC reads as a real nav rail (not empty void).
        batch.setColor(AppPalette.plateDeep)
        batch.draw(whitePixel, colLeft, colBottom, colW, colH)
        batch.setColor(Color.WHITE)

        val border = max(1.5f, Fonts.px(2f))
        batch.setColor(AppPalette.tint(AppPalette.rim, 0.28f))
        batch.draw(whitePixel, contentLeft - Fonts.px(6f) - border * 0.5f, bodyBottom, border, bodyH)
        batch.setColor(Color.WHITE)

        drawHeader?.invoke(batch, whitePixel)
        renderList(batch, camera, scrollY, pointerWorldX, pointerWorldY)
    }

    private fun renderCompact(
        batch: SpriteBatch,
        camera: Camera,
        whitePixel: Texture,
        scrollY: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
        drawHeader: ((SpriteBatch, Texture) -> Unit)?,
    ) {
        val eased = TileEasing.easeOutCubic(drawerProgress)
        if (eased > 0.001f) {
            batch.setColor(AppPalette.tint(AppPalette.shadow, 0.38f * eased))
            batch.draw(whitePixel, bodyLeft, bodyBottom, bodyW, bodyH)
            batch.setColor(Color.WHITE)

            batch.setColor(AppPalette.plateDeep)
            batch.draw(whitePixel, colLeft, colBottom, colW, colH)
            batch.setColor(Color.WHITE)

            drawDrawerRightEdgeGapped(batch, whitePixel, edgeAlpha = 0.40f + 0.15f * eased)

            if (eased > 0.08f) {
                drawHeader?.invoke(batch, whitePixel)
                renderList(batch, camera, scrollY, pointerWorldX, pointerWorldY)
            }
        }
        renderPullTab(batch, whitePixel, pointerWorldX, pointerWorldY)
    }

    /** Draw [list] between the column bottom and the reserved header band. */
    private fun renderList(
        batch: SpriteBatch,
        camera: Camera,
        scrollY: Float,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        val pad = Fonts.px(8f)
        list.render(
            batch,
            camera,
            colLeft + pad,
            colBottom + pad * 0.5f,
            (colW - pad * 2f).coerceAtLeast(1f),
            (headerBottom - (colBottom + pad * 0.5f)).coerceAtLeast(1f),
            scrollY,
            pointerWorldX,
            pointerWorldY,
        )
    }

    /**
     * World rects inside the content viewport where a press belongs to this
     * column. The lesson space reserves them so a layer laid out in that
     * viewport never receives the point. Same condition as [onPointerDown]:
     * the pull tab, and the whole viewport once the drawer claims the body.
     */
    fun pressReservedRects(): List<Rect> {
        if (!compactMode || contentW <= 1f || contentH <= 1f) return emptyList()
        val content = Rect(contentLeft, contentBottom, contentW, contentH)
        if (compactClaimsBody()) return listOf(content)
        if (pullW > 0f && pullH > 0f) return listOf(Rect(pullLeft, pullBottom, pullW, pullH))
        return emptyList()
    }

    /** Open, or still more than half visible while closing. The body is then a scrim. */
    private fun compactClaimsBody(): Boolean = drawerOpen || drawerProgress > 0.5f

    fun onPointerDown(worldX: Float, worldY: Float): Boolean {
        drag = Drag.None
        if (compactMode) {
            if (containsPullTab(worldX, worldY)) {
                drag = Drag.PullTab
                beginPullTabDrag(worldY)
                return true
            }
            if (compactClaimsBody()) {
                if (isInColumn(worldX, worldY)) {
                    if (list.contains(worldX, worldY)) {
                        drag = Drag.Toc
                        list.onPointerDown(worldX, worldY)
                    } else {
                        drag = Drag.Toc
                    }
                    return true
                }
                // Scrim: close drawer (must match [hitsColumn] so the hit test claims it).
                if (isInBody(worldX, worldY)) {
                    drag = Drag.Scrim
                    return true
                }
            }
            return false
        }
        // Side-by-side: TOC column only.
        if (list.contains(worldX, worldY) || isInColumn(worldX, worldY)) {
            drag = Drag.Toc
            if (list.contains(worldX, worldY)) list.onPointerDown(worldX, worldY)
            return true
        }
        return false
    }

    /** @return the list's offset after the drag (the frame's value when idle) */
    fun onPointerDrag(worldY: Float): Float = when (drag) {
        Drag.Toc -> list.onPointerDrag(worldY)
        Drag.PullTab -> {
            dragPullTab(worldY)
            list.scrollY
        }
        else -> list.scrollY
    }

    /**
     * @return selected TOC row id, or null; [wasDrag] if scroll/drag; [drawerToggled]
     *   if pull/scrim changed drawer open state; [settledScrollOffset] when the
     *   gesture scrolled the list.
     */
    fun onPointerUp(worldX: Float, worldY: Float): PointerResult {
        val d = drag
        drag = Drag.None
        return when (d) {
            Drag.PullTab -> {
                val moved = pullDragMoved
                val hit = containsPullTab(worldX, worldY)
                endPullTabDrag()
                if (!moved && hit) {
                    drawerOpen = !drawerOpen
                    PointerResult(drawerToggled = true)
                } else {
                    PointerResult(wasDrag = moved)
                }
            }
            Drag.Scrim -> {
                if (!list.contains(worldX, worldY) && !isInColumn(worldX, worldY)) {
                    drawerOpen = false
                    PointerResult(drawerToggled = true)
                } else {
                    PointerResult()
                }
            }
            Drag.Toc -> {
                val result = list.onPointerUp()
                if (result.wasDrag) {
                    return PointerResult(
                        wasDrag = true,
                        settledScrollOffset = result.settledScrollOffset,
                    )
                }
                val id = result.selectedId
                if (id != null) {
                    list.select(id)
                    if (compactMode) drawerOpen = false
                    return PointerResult(selectedId = id)
                }
                PointerResult()
            }
            Drag.None -> PointerResult()
        }
    }

    /**
     * Wheel / trackpad over the column.
     * @return the list's new offset, or null when this column does not own the pointer
     */
    fun onScroll(amountY: Float, pointerWorldX: Float, pointerWorldY: Float): Float? {
        if (compactMode) {
            if (drawerProgress > 0.15f && isInColumn(pointerWorldX, pointerWorldY)) {
                return list.onScroll(amountY)
            }
            return null
        }
        if (list.contains(pointerWorldX, pointerWorldY) || isInColumn(pointerWorldX, pointerWorldY)) {
            return list.onScroll(amountY)
        }
        return null
    }

    /** Advance the list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = list.advance(deltaSec)

    /**
     * Replace the TOC rows. The owner keeps the offset: [currentScrollY] is the
     * value to build on (ignored when [resetScroll]), and the returned value is
     * the offset with the selection revealed.
     *
     * @param resetScroll true when this is a different list (section switch)
     * @param revealSelection when true, scroll the selection into view from [currentScrollY]
     * @return the offset to render with next frame
     */
    fun setItems(
        items: List<ListItem>,
        selectedId: String? = null,
        currentScrollY: Float = 0f,
        resetScroll: Boolean = false,
        revealSelection: Boolean = true,
    ): Float {
        list.setItems(items, selectedId = selectedId)
        val base = if (resetScroll) 0f else currentScrollY
        if (!revealSelection) return base.coerceAtLeast(0f)
        return list.revealOffset(selectedId, base)
    }

    fun cancelGesture() {
        list.cancelGesture()
        drag = Drag.None
        endPullTabDrag()
    }

    /**
     * Set compact drawer open/closed.
     * @param snap when true, skip the slide animation (restore / first show).
     */
    fun setDrawerOpen(open: Boolean, snap: Boolean = false) {
        drawerOpen = open
        if (snap) drawerProgress = if (open) 1f else 0f
    }

    /**
     * True if [worldX]/[worldY] should be claimed by TOC chrome (not lesson content
     * / free-space menu).
     *
     * Compact open drawer: the content body is a **scrim** — tap closes the drawer
     * (same target [onPointerDown] already uses). Without this, the hit test would
     * miss the scrim and open the main menu instead.
     */
    fun hitsColumn(worldX: Float, worldY: Float): Boolean {
        if (!compactMode) return isInColumn(worldX, worldY)
        if (containsPullTab(worldX, worldY)) return true
        // Closed (or almost): only the pull tab is chrome.
        if (drawerProgress < 0.15f) return false
        if (isInColumn(worldX, worldY)) return true
        // Content zone = dismiss scrim while the drawer is open enough to act on.
        return (drawerOpen || drawerProgress > 0.5f) && isInBody(worldX, worldY)
    }

    private fun isInBody(worldX: Float, worldY: Float): Boolean =
        bodyW > 0f &&
            worldX >= bodyLeft && worldX <= bodyLeft + bodyW &&
            worldY >= bodyBottom && worldY <= bodyBottom + bodyH

    data class PointerResult(
        val wasDrag: Boolean = false,
        val selectedId: String? = null,
        val drawerToggled: Boolean = false,
        /** The list offset a TOC drag settled at; null when it did not scroll. */
        val settledScrollOffset: Float? = null,
    )

    private fun isInColumn(worldX: Float, worldY: Float): Boolean =
        colW > 0f &&
            worldX >= colLeft && worldX <= colLeft + colW &&
            worldY >= colBottom && worldY <= colBottom + colH

    private fun compactDrawerWidth(): Float =
        (bodyW * 0.78f)
            .coerceIn(Fonts.px(200f), Fonts.px(320f))
            .coerceAtMost(bodyW * 0.92f)

    /** The compact drawer has not reached its open or closed target. */
    fun isDrawerAnimating(): Boolean {
        val target = if (drawerOpen) 1f else 0f
        return drawerProgress != target
    }

    private fun updateDrawerAnim() {
        val target = if (drawerOpen) 1f else 0f
        if (drawerProgress == target) return
        val step = Gdx.graphics.deltaTime / DRAWER_ANIM_SEC
        drawerProgress =
            if (drawerProgress < target) {
                (drawerProgress + step).coerceAtMost(target)
            } else {
                (drawerProgress - step).coerceAtLeast(target)
            }
    }

    private fun layoutEdgePullTab() {
        pullW = Fonts.px(26f)
        pullH = Fonts.px(64f)
        pullLeft = colLeft + colW
        if (pullLeft < panelLeft) pullLeft = panelLeft
        if (pullLeft + pullW > panelLeft + panelW) {
            pullLeft = panelLeft + panelW - pullW
        }
        val (minBottom, maxBottom) = pullTabBottomRange()
        val range = (maxBottom - minBottom).coerceAtLeast(0f)
        pullTabYFrac = pullTabYFrac.coerceIn(0f, 1f)
        pullBottom = minBottom + pullTabYFrac * range
    }

    private fun pullTabBottomRange(): Pair<Float, Float> {
        val minBottom = bodyBottom
        val maxBottom = (bodyBottom + bodyH - pullH).coerceAtLeast(minBottom)
        return minBottom to maxBottom
    }

    private fun beginPullTabDrag(worldY: Float) {
        pullDragLastY = worldY
        pullDragMoved = false
    }

    private fun dragPullTab(worldY: Float) {
        if (pullDragLastY.isNaN() || pullH <= 1f) return
        val dy = worldY - pullDragLastY
        if (abs(dy) >= Fonts.px(PULL_TAB_DRAG_SLOP_DP)) pullDragMoved = true
        if (!pullDragMoved) return
        val (minBottom, maxBottom) = pullTabBottomRange()
        pullBottom = (pullBottom + dy).coerceIn(minBottom, maxBottom)
        val range = (maxBottom - minBottom).coerceAtLeast(0f)
        pullTabYFrac = if (range > 0.5f) (pullBottom - minBottom) / range else 0.5f
        pullDragLastY = worldY
    }

    private fun endPullTabDrag() {
        pullDragLastY = Float.NaN
        pullDragMoved = false
    }

    private fun containsPullTab(worldX: Float, worldY: Float): Boolean =
        pullW > 0f &&
            pullH > 0f &&
            worldX >= pullLeft &&
            worldX <= pullLeft + pullW &&
            worldY >= pullBottom &&
            worldY <= pullBottom + pullH

    private fun drawDrawerRightEdgeGapped(
        batch: SpriteBatch,
        whitePixel: Texture,
        edgeAlpha: Float,
    ) {
        val edge = max(1.5f, Fonts.px(2f))
        val edgeX = colLeft + colW - edge
        batch.setColor(AppPalette.tint(AppPalette.rim, edgeAlpha))
        val tabBottom = pullBottom
        val tabTop = pullBottom + pullH
        val belowH = (tabBottom - colBottom).coerceAtLeast(0f)
        if (belowH > 0.5f) {
            batch.draw(whitePixel, edgeX, colBottom, edge, belowH)
        }
        val aboveH = (colBottom + colH - tabTop).coerceAtLeast(0f)
        if (aboveH > 0.5f) {
            batch.draw(whitePixel, edgeX, tabTop, edge, aboveH)
        }
        batch.setColor(Color.WHITE)
    }

    private fun renderPullTab(
        batch: SpriteBatch,
        whitePixel: Texture,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        if (pullW <= 1f || pullH <= 1f) return
        val hovered =
            !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                containsPullTab(pointerWorldX, pointerWorldY)
        val dragging = drag == Drag.PullTab && pullDragMoved
        val open = drawerOpen || drawerProgress > 0.5f
        val hoverPlate = OverlayChrome.rowFill(hovered = true)
        val bg = when {
            dragging || hovered -> Color(hoverPlate.r, hoverPlate.g, hoverPlate.b, 0.52f)
            else -> AppPalette.tint(AppPalette.plateDarker, 0.32f)
        }
        val rim = when {
            dragging || hovered -> AppPalette.tint(AppPalette.rim, 0.70f)
            else -> AppPalette.tint(AppPalette.rim, 0.28f)
        }
        val ink = when {
            dragging || hovered -> AppPalette.tint(AppPalette.inkStrong, 0.95f)
            else -> AppPalette.tint(AppPalette.inkSoft, 0.78f)
        }
        val shape = pullTabShape()
        val rimPad = max(1.2f, Fonts.px(1.5f))
        batch.setColor(rim)
        batch.draw(shape, pullLeft, pullBottom - rimPad, pullW + rimPad, pullH + rimPad * 2f)
        batch.setColor(bg)
        batch.draw(shape, pullLeft, pullBottom, pullW, pullH)
        batch.setColor(Color.WHITE)
        val icon = if (open) UiIcons.chevronLeft else UiIcons.chevronRight
        val iconSize = (pullW * 0.72f).coerceAtMost(pullH * 0.50f).coerceAtLeast(1f)
        batch.setColor(ink)
        batch.draw(
            icon,
            pullLeft + (pullW - iconSize) * 0.5f,
            pullBottom + (pullH - iconSize) * 0.5f,
            iconSize,
            iconSize,
        )
        batch.setColor(Color.WHITE)
        // whitePixel unused but keeps signature parity with hosts that only pass it.
        @Suppress("UNUSED_EXPRESSION")
        whitePixel
    }

    private fun pullTabShape(): Texture {
        pullTabShapeTex?.let { return it }
        val w = PULL_TAB_TEX_W
        val h = PULL_TAB_TEX_H
        val r = PULL_TAB_CORNER_R
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pm.setBlending(Pixmap.Blending.None)
        pm.setColor(Color.CLEAR)
        pm.fill()
        val white = Color.rgba8888(1f, 1f, 1f, 1f)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val x = px + 0.5f
                val y = py + 0.5f
                if (isInPullTabShape(x, y, w.toFloat(), h.toFloat(), r)) {
                    pm.drawPixel(px, py, white)
                }
            }
        }
        val tex = Texture(pm)
        pm.dispose()
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        pullTabShapeTex = tex
        return tex
    }

    private fun isInPullTabShape(x: Float, y: Float, w: Float, h: Float, r: Float): Boolean {
        if (x !in 0f..w || y < 0f || y > h) return false
        if (x <= w - r) return true
        if (y >= r && y <= h - r) return true
        if (y < r) {
            val dx = x - (w - r)
            val dy = y - r
            return dx * dx + dy * dy <= r * r
        }
        val dx = x - (w - r)
        val dy = y - (h - r)
        return dx * dx + dy * dy <= r * r
    }

    private fun isCompactMode(screenW: Float, screenH: Float): Boolean {
        return Gdx.app.type == Application.ApplicationType.Android || screenH > screenW
    }

    override fun dispose() {
        list.dispose()
        pullTabShapeTex?.dispose()
        pullTabShapeTex = null
    }

    companion object {
        private const val DRAWER_ANIM_SEC = 0.16f
        private const val PULL_TAB_DRAG_SLOP_DP = 8f
        private const val PULL_TAB_TEX_W = 64
        private const val PULL_TAB_TEX_H = 128
        private const val PULL_TAB_CORNER_R = 22f
    }
}
