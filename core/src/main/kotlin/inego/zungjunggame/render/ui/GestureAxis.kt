package inego.zungjunggame.render.ui

import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Which axis an in-flight press belongs to when a sideways-only control sits
 * inside a scrolling body.
 *
 * A slider and a scroll body are both drag targets fed by the same finger press.
 * Without arbitration the control claims the press on touch-down, so a pan that
 * happens to begin on it does nothing — the body never sees a drag sample, and
 * the control is moved instead. The rule is directional and stated here once, so
 * every surface with this shape (the Settings card, the hub's Live tab) answers
 * a pan the same way:
 *
 *  - a **scrollbar grab** is vertical by construction and owns the gesture from
 *    the first sample;
 *  - a **sideways** drag takes the control only when the press began on one,
 *    travelled [SIDE_INTENT_DP] on X, and travelled further on X than on Y;
 *  - **any other** press past [tapSlopPx] scrolls the body, even with a control
 *    under the finger;
 *  - a press that passes neither threshold is still a tap on whatever it began
 *    on.
 *
 * The thresholds differ on purpose: a slider has to answer before the finger
 * feels stuck, so [SIDE_INTENT_DP] is shorter than the platform tap slop; a tap
 * is disqualified only once it has travelled the platform's own slop.
 *
 * The object holds the press origin and what it settled on, nothing else. The
 * caller owns the body, the controls and every verdict: this decides *which* of
 * them sees the gesture, never what the gesture means.
 */
class GestureAxis(
    private val tapSlop: Float = tapSlopPx(),
    private val sideIntent: Float = Fonts.px(SIDE_INTENT_DP),
) {
    /** What the press has committed to; sticky once chosen. */
    enum class Owner {
        /** Travel is below both thresholds, so a release is still a tap. */
        Undecided,

        /** The sideways control under the press owns the gesture. */
        Side,

        /** The scrolling body owns the gesture; the control under it does not. */
        Scroll,
    }

    var owner: Owner = Owner.Undecided
        private set

    /** True once the press travelled past the tap slop; a release is no longer a tap. */
    var moved: Boolean = false
        private set

    /** The last sample's travel from the press point, for diagnostics. */
    var dx: Float = 0f
        private set
    var dy: Float = 0f
        private set

    private var downX = Float.NaN
    private var downY = Float.NaN

    /** True between [begin] and [clear]. */
    val started: Boolean
        get() = !downX.isNaN()

    fun begin(x: Float, y: Float) {
        downX = x
        downY = y
        owner = Owner.Undecided
        moved = false
        dx = 0f
        dy = 0f
    }

    /**
     * Classify one drag sample.
     *
     * [sideAvailable] — the press began on a sideways-only control.
     * [scrollAvailable] — the press began in the scrolling body, which is armed
     *   for it; a vertical move must reach the body even when a control sits
     *   under the finger.
     * [scrollGrabbed] — the press already grabbed the scrollbar, which outranks
     *   the sideways rule.
     *
     * Reversing direction mid-drag cannot hand the gesture to the other target:
     * once the owner is chosen it stays chosen for the rest of the press.
     */
    fun resolve(
        x: Float,
        y: Float,
        sideAvailable: Boolean,
        scrollAvailable: Boolean,
        scrollGrabbed: Boolean,
    ): Owner {
        if (!started) return Owner.Undecided
        dx = x - downX
        dy = y - downY
        if (!moved && hypot(dx, dy) > tapSlop) moved = true
        if (owner == Owner.Undecided) {
            owner = when {
                scrollGrabbed -> Owner.Scroll
                sideAvailable && abs(dx) > sideIntent && abs(dx) > abs(dy) -> Owner.Side
                moved && scrollAvailable -> Owner.Scroll
                else -> Owner.Undecided
            }
        }
        return owner
    }

    fun clear() {
        downX = Float.NaN
        downY = Float.NaN
        owner = Owner.Undecided
        moved = false
        dx = 0f
        dy = 0f
    }

    companion object {
        /**
         * Horizontal travel that reads as a deliberate slider move (8 design dp):
         * long enough to be a direction, short enough that the slider answers
         * before the finger feels stuck.
         */
        const val SIDE_INTENT_DP = 8f
    }
}
