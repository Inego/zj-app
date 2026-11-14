package inego.zungjunggame.render.ui

import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect

/**
 * Where a *parked block* ends up: the block rests at a known pose and the player
 * may drag it around from there (see [ParkDrag]).
 *
 * One clamp, used by both sides of the feature: the layout that measures the
 * block and the drag that moves it must agree on how far the block can go, or
 * the block visibly stops at an edge while the accumulated park keeps growing and
 * the finger has to walk all the way back before it moves again.
 */
internal object ParkGeometry {

    /**
     * A block pose. [parkX]/[parkY] are the offsets **actually applied**: feed
     * them back into the park so the block stops accumulating motion once it is
     * resting against an edge.
     */
    data class Placement(
        val left: Float,
        val bottom: Float,
        val parkX: Float,
        val parkY: Float,
    )

    /**
     * Offset a [blockW]×[blockH] block from its rest pose and clamp it into
     * [bounds].
     *
     * [restLeft]/[restBottom] is the block's lower-left corner with **no** park
     * applied (its unparked, centered pose), which is what makes this callable
     * from the layout and from the drag alike.
     *
     * A block bigger than [bounds] cannot be made fully visible. It may then
     * slide all the way between "left/top edge flush" and "right/bottom edge
     * flush", so the player can drag the far side into view. A claim row on a
     * narrow phone is wider than the screen — the alternative, pinning one edge,
     * would leave the first or last button permanently cut off and unreachable.
     */
    fun place(
        restLeft: Float,
        restBottom: Float,
        blockW: Float,
        blockH: Float,
        parkX: Float,
        parkY: Float,
        bounds: Rect,
    ): Placement {
        val left = clampAxis(restLeft + parkX, bounds.left, bounds.right - blockW)
        val bottom = clampAxis(restBottom + parkY, bounds.bottom, bounds.top - blockH)
        return Placement(left, bottom, left - restLeft, bottom - restBottom)
    }

    /** Clamp into a range that is inverted when the block overflows [bounds]. */
    private fun clampAxis(value: Float, lo: Float, hi: Float): Float =
        if (lo <= hi) value.coerceIn(lo, hi) else value.coerceIn(hi, lo)
}

/**
 * Press → drag → park state machine for one movable block of chrome.
 *
 * Owned by the block (see [ParkableChrome]); the
 * pointer coordinator only calls [begin] / [drag] / [release] / [cancel]. The
 * owner refreshes [setGeometry] every render — a block is measured while it is
 * drawn — and stores the applied offsets back with [setPark], so the park is
 * clamped by the same [ParkGeometry.place] the layout used.
 *
 * `parkX`/`parkY` are an offset from the block's rest pose, not a position: the
 * block keeps its place in the board layout and only moves by what the player
 * dragged it. The park survives the block hiding (a player-chosen place is not
 * re-learned every hand) and is seeded through [setPark] when the block comes
 * back: a block whose place is the player's arrangement keeps it in the model, so
 * it returns to that place after a restart instead of to its rest pose.
 */
internal class ParkDrag(
    /**
     * Drag slop in design px, scaled by [Fonts.px]. 8 matches the pull tab the
     * hand-end card and the TOC drawer use, so a fingertip that rolls while
     * tapping a button inside the block does not turn the tap into a park.
     */
    private val slopDesignPx: Float = DEFAULT_SLOP_DP,
) {
    /** What a release turned out to be. [pressX]/[pressY] are the press point. */
    data class Release(
        val moved: Boolean,
        val pressX: Float,
        val pressY: Float,
    )

    var parkX: Float = 0f
        private set
    var parkY: Float = 0f
        private set

    /** A press belongs to this block (before or after the slop). */
    var active: Boolean = false
        private set

    /** The press travelled past the slop: a drag of the block, not a tap on it. */
    var moved: Boolean = false
        private set

    private var movesBlock: Boolean = true
    private var pressX: Float = Float.NaN
    private var pressY: Float = Float.NaN
    private var lastX: Float = Float.NaN
    private var lastY: Float = Float.NaN

    private var restLeft: Float = Float.NaN
    private var restBottom: Float = Float.NaN
    private var blockW: Float = 0f
    private var blockH: Float = 0f
    private var bounds: Rect? = null

    val slopPx: Float get() = Fonts.px(slopDesignPx)

    /**
     * Remember where the block rests and the area it may be parked in. Called
     * every render, before any press can be served this frame.
     */
    fun setGeometry(
        restLeft: Float,
        restBottom: Float,
        blockW: Float,
        blockH: Float,
        bounds: Rect,
    ) {
        this.restLeft = restLeft
        this.restBottom = restBottom
        this.blockW = blockW
        this.blockH = blockH
        this.bounds = bounds
    }

    /**
     * Start a press.
     *
     * @param movesBlock false for a press inside the block that must not move it
     *   — the hand-end card's fold chevron, or a press its scrolling body took.
     *   The press still belongs to the block, but it never parks and never counts
     *   as a drag.
     */
    fun begin(wx: Float, wy: Float, movesBlock: Boolean = true) {
        cancel()
        active = true
        this.movesBlock = movesBlock
        pressX = wx
        pressY = wy
        lastX = wx
        lastY = wy
        moved = false
    }

    fun drag(wx: Float, wy: Float) {
        if (!active || wx.isNaN() || wy.isNaN()) return
        if (movesBlock) {
            if (moved) {
                parkX += wx - lastX
                parkY += wy - lastY
            } else {
                val dx = wx - pressX
                val dy = wy - pressY
                if (dx * dx + dy * dy >= slopPx * slopPx) {
                    // First move past the slop takes the whole travel: the block
                    // catches up with the finger instead of lagging by the slop.
                    moved = true
                    parkX += dx
                    parkY += dy
                }
            }
            clamp()
        }
        lastX = wx
        lastY = wy
    }

    /**
     * End the press and report what it was. Clears the press, keeps the park.
     */
    fun release(): Release {
        val release = Release(moved = moved, pressX = pressX, pressY = pressY)
        clearPress()
        return release
    }

    /** Drop an in-flight press without changing the park. */
    fun cancel() = clearPress()

    /**
     * Park at the offsets a [ParkGeometry.place] call applied, so the block does
     * not keep accumulating travel it cannot use.
     */
    fun setPark(x: Float, y: Float) {
        parkX = x
        parkY = y
    }

    private fun clearPress() {
        active = false
        moved = false
        movesBlock = true
        pressX = Float.NaN
        pressY = Float.NaN
        lastX = Float.NaN
        lastY = Float.NaN
    }

    private fun clamp() {
        val bounds = bounds ?: return
        if (blockW <= 0f || blockH <= 0f || restLeft.isNaN() || restBottom.isNaN()) return
        val placed = ParkGeometry.place(restLeft, restBottom, blockW, blockH, parkX, parkY, bounds)
        parkX = placed.parkX
        parkY = placed.parkY
    }

    companion object {
        /** Same 8 dp slop as the TOC drawer / Reference pull tab drag. */
        const val DEFAULT_SLOP_DP = 8f
    }
}
