package inego.zungjunggame.features.board

import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.math.max

/**
 * The board's menu control: the persistent top-right button on a local
 * (free-play) or online board. A tutorial board lesson draws no button of its
 * own — the tutorial shell owns one menu control for the whole context.
 *
 * Esc, Android back and the touch empty-felt tap remain accelerators; where the
 * button paints, it is the visible route. It is board chrome rather than a route
 * because the board is the input authority for free play and online. The region
 * [DefaultBoardMeasure] publishes raises the same [BoardActions.MENU] intent the
 * system-back path reaches.
 *
 * Geometry lives here so the hit region and the painted square cannot disagree,
 * and so the table can reserve the same square without measuring the button
 * itself.
 */
internal object BoardMenuChrome {

    /**
     * Whether the menu button paints and answers taps on [kind]'s board.
     * Tutorial lessons leave the corner to the lesson chrome.
     */
    fun presentIn(kind: SessionKind): Boolean = when (kind) {
        SessionKind.FreePlay, SessionKind.Online -> true
        SessionKind.Tutorial, SessionKind.Menu -> false
    }

    /**
     * The button's square inside [safe], at the shared corner inset.
     *
     * [width] and [height] are the board's own pane — the whole viewport in free
     * play and online, the board pane beside the coach's copy column in a tutorial
     * lesson. Insets are zeros inside a provider's space, where the space itself
     * already sits inside the window cutout.
     */
    fun rect(width: Float, height: Float, safe: SafeArea.Insets): Rect {
        val side = buttonSide()
        val inset = CloseButton.cornerInset()
        return Rect(
            left = (width - safe.right - inset - side).coerceAtLeast(safe.left),
            bottom = (height - safe.top - inset - side).coerceAtLeast(safe.bottom),
            width = side,
            height = side,
        )
    }

    /**
     * The square the button covers, measured from the safe corner: its inset plus
     * its side. The table compares this with the seat gutter and reserves the
     * corner for it where the gutter is too narrow to hold it. It does not include
     * a breathing gap, so a button that merely sits close to the tiles — as it
     * does at 1080p on desktop — does not shrink the Right hand.
     */
    fun footprint(): Float {
        Fonts.ensureCreated()
        return CloseButton.cornerInset() + buttonSide()
    }

    /**
     * Portrait hands span the board's width, so the corner is not spare felt.
     * When a menu floats over the corner ([reserveCorner]), reserve its band
     * above the table; the normal seat gutter then keeps both Opposite's tiles
     * and label below the button. Wide boards, and boards whose pane ends below
     * the menu, retain their existing corner space. The tutorial board reserves
     * here even though the shell paints the button. These insets apply to every
     * table layout pass, not to the button or HUD, which stay inside the
     * platform safe area.
     */
    fun tableInsets(
        width: Float,
        height: Float,
        safe: SafeArea.Insets,
        reserveCorner: Boolean = true,
    ): SafeArea.Insets =
        if (reserveCorner && height - safe.top - safe.bottom > width - safe.left - safe.right) {
            safe.copy(top = height - rect(width, height, safe).bottom)
        } else {
            safe
        }

    /**
     * Side length: the shared corner-chrome square, with a fingertip floor on a
     * touch profile. Matches the HINT button's touch sizing policy.
     */
    fun buttonSide(): Float {
        Fonts.ensureCreated()
        val base = CloseButton.defaultSide()
        return if (InputProfile.detect() == InputProfile.Touch) {
            max(base, Fonts.px(44f))
        } else {
            base
        }
    }
}
