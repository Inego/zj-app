package inego.zungjunggame.platform

/**
 * Platform safe insets in **screen pixels**: the display cutout, the navigation bar
 * and the window caption bar, per side.
 *
 * With [com.badlogic.gdx.utils.viewport.ScreenViewport], these match world units
 * (origin bottom-left for layout: [top] is measured from the top of the screen).
 *
 * Only insets that **cannot change without a window or configuration change**
 * belong here. The Android backend keeps the navigation bar visible — a three-button
 * user must never lose their navigation — so the bar and the cutout are both
 * permanent and both are published. The caption bar is the desktop-windowing title
 * bar: zero on a phone, the system's occupied strip at the top of a freeform window,
 * and published for the same reason. The status bar is the transient one: it is
 * hidden by policy and a swipe reveals it as an overlay, so it is *not* published;
 * publishing it made the layout jump mid-transition.
 * Anything read per-frame from here is assumed stable across a frame.
 *
 * **Per-side insets are conservative, and that is right for a row.** They say how
 * deep the furniture reaches somewhere along an edge, not where along it. A row
 * that runs the length of an edge (a player's hand, a toolbar, a table) genuinely
 * crosses a cutout's position and must keep the whole edge inset.
 *
 * **A corner control needs to know the corner, not the edge.** On a phone whose
 * punch-hole sits in the middle of a long side, [right] is a full notch deep even
 * though the top-right corner is open, and a button placed inside [right] abandons
 * space nothing occupies. [cornerTopRight] answers the narrower question for the
 * one corner that holds window chrome, so a button can sit flush when the corner
 * is free and still yield when something really occupies it.
 *
 * Desktop and other backends leave zeros. Android updates via [setPixels] and
 * [setTopRightCorner] from the cutout, navigation-bar and caption-bar insets.
 *
 * Threading: writers are typically the Android UI thread; readers are the GL
 * thread. Fields are volatile so a torn multi-field read is possible but only
 * for one frame — prefer [current] and [cornerTopRight] for a consistent snapshot.
 */
object SafeArea {
    @Volatile
    var left: Float = 0f
        private set

    @Volatile
    var top: Float = 0f
        private set

    @Volatile
    var right: Float = 0f
        private set

    @Volatile
    var bottom: Float = 0f
        private set

    @Volatile
    private var cornerRight = 0f

    @Volatile
    private var cornerTop = 0f

    data class Insets(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    ) {
        val horizontal: Float get() = left + right
        val vertical: Float get() = top + bottom
    }

    /**
     * How far the window's **top-right** corner is occupied: [right] in from the
     * right edge, [top] down from the top edge. Zero on both means the corner is
     * free to the window's edges.
     *
     * A cutout contributes only when it actually reaches this corner. One in the
     * middle of an edge — a punch-hole on a long side after rotation, a top-centre
     * notch — contributes nothing, because a square placed in the corner is clear
     * of it. A permanent system strip along an edge does contribute: a landscape
     * three-button navigation bar on the right blocks the whole edge, corner
     * included, wherever the cutout sits.
     *
     * A square placed where the corner reports zero relies on the shared corner
     * inset ([inego.zungjunggame.render.ui.CloseButton.cornerInset], 14 design px)
     * to stay off the display's rounded corner: a corner clears the arc once it is
     * `radius * (1 - 1/√2)` in from both edges, which is 12 dp at a 40 dp radius.
     * A display with a larger radius can shave the plate's corner on a
     * high-density screen, where the inset stops growing with density; the glyph
     * is padded 20% inside the plate and never reaches it.
     */
    data class Corner(val right: Float = 0f, val top: Float = 0f)

    /** Consistent four-side snapshot for layout. */
    fun current(): Insets = Insets(left, top, right, bottom)

    /** Consistent snapshot of what occupies the window's top-right corner. */
    fun cornerTopRight(): Corner = Corner(cornerRight, cornerTop)

    /**
     * Called by the Android backend when window insets change (rotation,
     * cutout side, multi-window, gesture nav). Values are raw screen pixels.
     */
    @JvmStatic
    fun setPixels(leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int) {
        left = leftPx.coerceAtLeast(0).toFloat()
        top = topPx.coerceAtLeast(0).toFloat()
        right = rightPx.coerceAtLeast(0).toFloat()
        bottom = bottomPx.coerceAtLeast(0).toFloat()
    }

    /**
     * How deeply the window's top-right corner is occupied, in raw screen pixels.
     * Called with zeros when nothing holds that corner. Kept out of [setPixels]
     * because it describes *where* the platform's furniture sits rather than how
     * deep the edge is, and an edge inset that included a mid-edge hole would be
     * the very over-reservation this separates from.
     */
    @JvmStatic
    fun setTopRightCorner(rightPx: Int, topPx: Int) {
        cornerRight = rightPx.coerceAtLeast(0).toFloat()
        cornerTop = topPx.coerceAtLeast(0).toFloat()
    }

    /** Reset (e.g. tests). */
    @JvmStatic
    fun clear() {
        left = 0f
        top = 0f
        right = 0f
        bottom = 0f
        cornerRight = 0f
        cornerTop = 0f
    }
}
