package inego.zungjunggame.platform

/**
 * Platform safe insets in **screen pixels**: the display cutout and the navigation
 * bar, per side.
 *
 * With [com.badlogic.gdx.utils.viewport.ScreenViewport], these match world units
 * (origin bottom-left for layout: [top] is measured from the top of the screen).
 *
 * Only insets that **cannot change while the app is running** belong here. The
 * Android backend keeps the navigation bar visible — a three-button user must never
 * lose their navigation — so the bar and the cutout are both permanent and both are
 * published. The status bar is the transient one: it is hidden by policy and a swipe
 * reveals it as an overlay, so it is *not* published; publishing it made the layout
 * jump mid-transition.
 * Anything read per-frame from here is assumed stable across a frame.
 *
 * Desktop and other backends leave zeros. Android updates via [setPixels] from the
 * cutout and navigation-bar insets.
 *
 * Threading: writers are typically the Android UI thread; readers are the GL
 * thread. Fields are volatile so a torn multi-field read is possible but only
 * for one frame — prefer [current] for a consistent snapshot.
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

    data class Insets(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    ) {
        val horizontal: Float get() = left + right
        val vertical: Float get() = top + bottom
    }

    /** Consistent four-side snapshot for layout. */
    fun current(): Insets = Insets(left, top, right, bottom)

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

    /** Reset (e.g. tests). */
    @JvmStatic
    fun clear() {
        left = 0f
        top = 0f
        right = 0f
        bottom = 0f
    }
}
