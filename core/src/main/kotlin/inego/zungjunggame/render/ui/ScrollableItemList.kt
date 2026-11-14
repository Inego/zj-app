package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Optional status glyph color for tutorial nav (and similar lists).
 * When null, [ListItem.number] uses the default number palette.
 */
enum class ListItemStatusTint {
    /** Never played / idle. */
    Muted,
    /** In progress / partial. */
    Warm,
    /** Completed / success. */
    Success,
}

/**
 * One row in a [ScrollableItemList].
 *
 * @param id stable key for selection (not the display string)
 * @param label primary title text (without section number)
 * @param number optional section / pattern number drawn smaller and muted
 *   (e.g. `"2.1.1"`) or a status glyph (`○` / `◑` / `✓`)
 * @param level indent depth (0 = flush left; each step is a small inset)
 * @param selectable when false, the row is a group header (not a navigable page)
 * @param statusTint when set with [number], tints the prefix for play-state chrome
 */
data class ListItem(
    val id: String,
    val label: String,
    val number: String? = null,
    val level: Int = 0,
    val selectable: Boolean = true,
    val statusTint: ListItemStatusTint? = null,
)

/**
 * Scrollable vertical list of selectable rows (nav TOC, settings, etc.).
 *
 * The **offset is the owner's**: [render] is handed the current value each frame,
 * and every interaction method returns the value it produced; the only thing kept
 * across calls is transient drag/inertia state and the frame's read value. [itemAt]
 * and [revealOffset] use the frame geometry, so hit tests and paint agree.
 *
 * Hierarchical rows use [ListItem.level] for left indent and optional
 * [ListItem.number] for a smaller muted prefix before the title.
 * Non-[ListItem.selectable] rows render as section headers and ignore taps.
 *
 * Rows are flat text (no chip fill / rim). Selection is shown with label color
 * and a thin left accent; hover only brightens the text.
 */
class ScrollableItemList : Disposable {
    private val glyphLayout = GlyphLayout()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()
    private val scroll = VerticalScroll()
    private val scrollbar = VerticalScrollbar()


    private var items: List<ListItem> = emptyList()
    var selectedId: String? = null
        private set

    private var viewLeft = 0f
    private var viewBottom = 0f
    private var viewW = 0f
    private var viewH = 0f

    /** The frame's offset, installed by [render] from the owner's value. */
    private var frameScrollY = 0f

    /** The frame's measured range, from the last [render]. */
    private var frameMaxScroll = 0f

    private var pressX: Float = Float.NaN
    private var pressY: Float = Float.NaN

    /** Result of [onPointerUp]: whether the gesture scrolled, and any row tap. */
    data class PointerUpResult(
        val wasDrag: Boolean,
        /** Non-null when the user tapped a row (not a scroll drag). */
        val selectedId: String? = null,
        /** The offset the gesture settled at when it scrolled; else null. */
        val settledScrollOffset: Float? = null,
    )

    /** Set viewport bounds for testing or non-draw layout. */
    fun setViewport(left: Float, bottom: Float, width: Float, height: Float) {
        viewLeft = left
        viewBottom = bottom
        viewW = width
        viewH = height
    }

    /** Replace the rows; the owner keeps the offset it wants to render with. */
    fun setItems(
        items: List<ListItem>,
        selectedId: String? = items.firstOrNull { it.selectable }?.id,
    ) {
        val nextSelected = when {
            selectedId != null &&
                items.any { it.id == selectedId && it.selectable } -> selectedId
            else -> items.firstOrNull { it.selectable }?.id
        }
        // Same rows are not a rebuild; a thumb drag in flight must survive a restatement.
        if (this.items == items && this.selectedId == nextSelected) return
        this.items = items
        this.selectedId = nextSelected
        scrollbar.clearDrag()
    }

    fun select(id: String?) {
        if (id != null && items.none { it.id == id && it.selectable }) return
        selectedId = id
    }

    /**
     * The offset that brings [id] (default: current selection) fully inside the
     * viewport, moving minimally from [current]. A no-op while the row is visible,
     * unknown, or the viewport has not been laid out yet; the caller keeps what it
     * is handed, so this computes and never stores.
     */
    fun revealOffset(id: String? = selectedId, current: Float): Float {
        val target = id ?: return current
        val index = items.indexOfFirst { it.id == target }
        if (index < 0 || viewH <= 1f) return current
        val rowH = rowHeight()
        val stride = rowH + rowGap()
        val contentH = contentHeight()
        val maxScroll = max(0f, contentH - viewH)
        val clamped = current.coerceIn(0f, maxScroll)
        val itemTop = index * stride
        val itemBottom = itemTop + rowH
        val viewBottomY = clamped + viewH
        val next = when {
            // Row is taller than the viewport — pin its top.
            itemBottom - itemTop >= viewH -> itemTop
            itemTop < clamped -> itemTop
            itemBottom > viewBottomY -> itemBottom - viewH
            else -> clamped
        }
        return next.coerceIn(0f, maxScroll)
    }

    /** Current frame offset (pixels from top); a read view of the owner's value. */
    val scrollY: Float
        get() = frameScrollY


    fun contains(worldX: Float, worldY: Float): Boolean =
        worldX >= viewLeft && worldX <= viewLeft + viewW &&
            worldY >= viewBottom && worldY <= viewBottom + viewH

    /**
     * Mouse-wheel / trackpad. Arms a wheel ease; the offset moves through
     * [advance].
     * @return the offset handed back (unchanged — the ease has not run yet)
     */
    fun onScroll(amountY: Float): Float {
        frameScrollY = scroll.wheel(amountY, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /** Drop an in-progress press/drag without selecting. */
    fun cancelGesture() {
        scroll.cancel()
        scrollbar.clearDrag()
        pressX = Float.NaN
        pressY = Float.NaN
    }

    fun onPointerDown(worldX: Float, worldY: Float) {
        cancelGesture()
        if (!contains(worldX, worldY)) return
        pressX = worldX
        pressY = worldY
        // Prefer the shared scrollbar strip over content/row drag.
        val barOffset = scrollbar.beginDrag(worldX, worldY, frameScrollY, frameMaxScroll)
        if (barOffset != null) {
            frameScrollY = barOffset.coerceIn(0f, frameMaxScroll)
            return
        }
        scroll.beginDrag(worldY, frameScrollY)
    }

    /** @return the offset the drag resolved (or the frame's value when idle) */
    fun onPointerDrag(worldY: Float): Float {
        val next = if (scrollbar.isDragging) {
            scrollbar.dragTo(worldY, frameScrollY, frameMaxScroll)
        } else {
            scroll.dragTo(worldY, frameMaxScroll)
        }
        frameScrollY = next.coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /** True while a released content drag is still coasting. */
    val isFlinging: Boolean
        get() = scroll.isFlinging

    /**
     * True while the list is still moving on its own — a fling coast or a wheel
     * ease. What a host asks before painting another frame.
     */
    val isCoasting: Boolean
        get() = scroll.isCoasting

    /** Advance the wheel ease or the fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float {
        frameScrollY = scroll.advance(deltaSec, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /**
     * End gesture.
     * When [PointerUpResult.selectedId] is non-null, selection was applied to [selectedId].
     */
    fun onPointerUp(): PointerUpResult {
        if (scrollbar.isDragging) {
            scrollbar.endDrag()
            scroll.cancel()
            pressX = Float.NaN
            pressY = Float.NaN
            return PointerUpResult(wasDrag = true, settledScrollOffset = frameScrollY)
        }
        val wasDrag = scroll.endDrag()
        val x = pressX
        val y = pressY
        pressX = Float.NaN
        pressY = Float.NaN
        if (wasDrag || x.isNaN() || y.isNaN()) {
            return PointerUpResult(wasDrag = wasDrag, settledScrollOffset = frameScrollY.takeIf { wasDrag })
        }
        val id = itemAt(x, y) ?: return PointerUpResult(wasDrag = false)
        selectedId = id
        return PointerUpResult(wasDrag = false, selectedId = id)
    }

    /**
     * Selectable item under the pointer from current scroll + viewport geometry,
     * or null (including when the hit row is a non-selectable group header, or
     * when the pointer is over the scrollbar gutter).
     * Safe to call during [render] (does not depend on draw order).
     */
    fun itemAt(worldX: Float, worldY: Float): String? {
        if (!contains(worldX, worldY) || items.isEmpty()) return null
        // Rows never use the right gutter; ignore presses there for selection/hover.
        if (worldX >= viewLeft + viewW - VerticalScrollbar.gutterWidth()) return null
        val rowH = rowHeight()
        val rowGap = rowGap()
        val stride = rowH + rowGap
        // Distance from the top of the scrolled content down to the pointer.
        val yFromContentTop = (viewBottom + viewH) - worldY + frameScrollY
        if (yFromContentTop < 0f) return null
        val index = (yFromContentTop / stride).toInt()
        if (index !in items.indices) return null
        val intoRow = yFromContentTop - index * stride
        if (intoRow > rowH) return null // pointer is in the gap under the row
        val item = items[index]
        if (!item.selectable) return null
        return item.id
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        bindFrame(left, bottom, width, height, scrollY)

        if (width <= 1f || height <= 1f) return
        Fonts.ensureCreated()

        val px = whitePixel()
        val titleFont = Fonts.button
        val numberFont = Fonts.small
        val rowH = rowHeight()
        val rowGap = rowGap()
        val contentH = contentHeight()

        batch.flush()
        clipBounds.set(left, bottom, width, height)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val scissorOn = ScissorStack.pushScissors(scissors)

        val gutter = VerticalScrollbar.gutterWidth()
        val padX = Fonts.px(8f)
        val indentStep = Fonts.px(12f)
        val accentW = max(2f, Fonts.px(2.5f))
        // Rows stay clear of the scrollbar track on the right.
        val rowsW = (width - gutter).coerceAtLeast(Fonts.px(24f))
        // First row top aligns with viewport top when scrollY = 0.
        var rowTop = bottom + height + frameScrollY
        val hoverId =
            if (!pointerWorldX.isNaN() && !pointerWorldY.isNaN() &&
                !scroll.isDrag && !scrollbar.isDragging
            ) {
                itemAt(pointerWorldX, pointerWorldY)
            } else {
                null
            }

        for (item in items) {
            val rowBottom = rowTop - rowH

            // Only draw rows that intersect the viewport (scissor still clips).
            if (rowBottom < bottom + height && rowTop > bottom) {
                val selected = item.selectable && item.id == selectedId
                val hovered = item.selectable && item.id == hoverId
                val level = item.level.coerceAtLeast(0)
                val indent = level * indentStep
                val rowLeft = left + padX * 0.35f + indent
                val rowW = (rowsW - padX * 0.7f - indent).coerceAtLeast(Fonts.px(24f))

                if (item.selectable && selected) {
                    // Flat selection: thin left accent only (no chip fill / rim).
                    batch.setColor(ACCENT_SELECTED)
                    val accentPadY = Fonts.px(4f)
                    batch.draw(
                        px,
                        rowLeft,
                        rowBottom + accentPadY,
                        accentW,
                        (rowH - accentPadY * 2f).coerceAtLeast(accentW),
                    )
                    batch.setColor(Color.WHITE)
                } else if (!item.selectable) {
                    // Group header: faint underline.
                    batch.setColor(AppPalette.tint(AppPalette.rim, 0.18f))
                    val underline = max(1f, Fonts.px(1.2f))
                    batch.draw(px, rowLeft, rowBottom + Fonts.px(4f), rowW, underline)
                    batch.setColor(Color.WHITE)
                }

                val textLeft = rowLeft + padX + if (item.selectable) accentW else 0f
                val baseline = rowBottom + rowH * 0.5f
                var cursorX = textLeft

                val number = item.number
                if (!number.isNullOrEmpty()) {
                    glyphLayout.setText(numberFont, number)
                    numberFont.color = numberColor(item, selected, hovered)
                    numberFont.draw(
                        batch,
                        number,
                        cursorX,
                        baseline + glyphLayout.height * 0.5f,
                    )
                    numberFont.color = Color.WHITE
                    cursorX += glyphLayout.width + Fonts.px(6f)
                }

                if (item.label.isNotEmpty()) {
                    glyphLayout.setText(titleFont, item.label)
                    titleFont.color = when {
                        !item.selectable -> HEADER_LABEL
                        selected -> LABEL_SELECTED
                        hovered -> LABEL_HOVER
                        else -> LABEL_IDLE
                    }
                    titleFont.draw(
                        batch,
                        item.label,
                        cursorX,
                        baseline + glyphLayout.height * 0.5f,
                    )
                    titleFont.color = Color.WHITE
                }
            }

            rowTop = rowBottom - rowGap
        }

        batch.flush()
        if (scissorOn) ScissorStack.popScissors()

        // Scrollbar outside scissors so the track is never clipped by row content.
        val geom = scrollbar.layout(
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            contentHeight = contentH,
            scrollY = frameScrollY,
            maxScrollY = frameMaxScroll,
        )
        if (geom != null) {
            scrollbar.draw(batch, px, geom, pointerWorldX, pointerWorldY)
        }
    }

    /**
     * Viewport, offset, and bar geometry for this frame. Paint and a headless
     * press both go through here so [onPointerDown] hits the same strip [render]
     * drew.
     */
    fun bindFrame(
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        scrollY: Float,
    ) {
        setViewport(left, bottom, width, height)
        if (width <= 1f || height <= 1f) {
            scrollbar.clear()
            frameScrollY = 0f
            frameMaxScroll = 0f
            return
        }
        val contentH = contentHeight()
        frameMaxScroll = max(0f, contentH - height)
        frameScrollY = scrollY.coerceIn(0f, frameMaxScroll)
        scrollbar.layout(
            left = left,
            bottom = bottom,
            width = width,
            height = height,
            contentHeight = contentH,
            scrollY = frameScrollY,
            maxScrollY = frameMaxScroll,
        )
    }

    private fun rowHeight(): Float {
        val textH = try {
            Fonts.ensureCreated()
            Fonts.button.lineHeight
        } catch (_: Throwable) {
            Fonts.px(22f)
        }
        // Flat text rows need less vertical chrome than the old chip plates.
        return max(Fonts.px(32f), textH + Fonts.px(10f))
    }

    private fun rowGap(): Float = Fonts.px(2f)

    private fun contentHeight(): Float {
        if (items.isEmpty()) return 0f
        val rowH = rowHeight()
        val gap = rowGap()
        return items.size * rowH + (items.size - 1).coerceAtLeast(0) * gap
    }

    private fun numberColor(
        item: ListItem,
        selected: Boolean,
        hovered: Boolean,
    ): Color {
        val tint = item.statusTint
        if (tint != null) {
            return when (tint) {
                ListItemStatusTint.Muted -> if (hovered || selected) STATUS_MUTED_HOT else STATUS_MUTED
                ListItemStatusTint.Warm -> if (hovered || selected) STATUS_WARM_HOT else STATUS_WARM
                ListItemStatusTint.Success -> if (hovered || selected) STATUS_SUCCESS_HOT else STATUS_SUCCESS
            }
        }
        return when {
            !item.selectable -> NUMBER_HEADER
            selected -> NUMBER_SELECTED
            hovered -> NUMBER_HOVER
            else -> NUMBER_IDLE
        }
    }

    private fun whitePixel(): Texture = WhitePixel.get()

    override fun dispose() {
        scrollbar.clear()
    }

    companion object {
        /** Muted teal-gray for pattern / section numbers. */
        private val NUMBER_IDLE = AppPalette.indexInk

        private val NUMBER_HOVER = AppPalette.indexInkHover

        /** Slightly brighter number when the row is selected. */
        private val NUMBER_SELECTED = AppPalette.indexInkSelected

        /** Group-header number (category / series). */
        private val NUMBER_HEADER = AppPalette.indexInkHeader

        private val STATUS_MUTED = AppPalette.statusInk
        private val STATUS_MUTED_HOT = AppPalette.statusInkHover
        private val STATUS_WARM = AppPalette.accentSoft
        private val STATUS_WARM_HOT = AppPalette.accentBright
        private val STATUS_SUCCESS = AppPalette.statusSuccess
        private val STATUS_SUCCESS_HOT = AppPalette.success

        private val LABEL_IDLE = AppPalette.labelInk

        private val LABEL_HOVER = AppPalette.labelInkHover

        private val LABEL_SELECTED = AppPalette.inkStrong

        /** Group-header title. */
        private val HEADER_LABEL = AppPalette.groupLabelInk

        /** Selected-row left accent (replaces the old chip rim). */
        private val ACCENT_SELECTED = AppPalette.tint(AppPalette.accent, 0.85f)
    }
}
