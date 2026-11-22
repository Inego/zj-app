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
     * The button's square inside [width] x [height], at the shared corner inset
     * from the right and top edges, held clear of [right] / [top] — the space the
     * platform claims in that corner.
     *
     * The insets are the caller's, not this object's: a full-screen board hands
     * in the window's own corner ([windowRect]), while a context that already
     * owns its panel (the tutorial shell's menu, which is laid out on the
     * safe-inset panel) hands in the edge insets the panel was cut by
     * ([panelRect]). The square is the same size either way.
     */
    fun rect(width: Float, height: Float, right: Float, top: Float): Rect {
        val side = buttonSide()
        val inset = CloseButton.cornerInset()
        return Rect(
            left = (width - right - inset - side).coerceAtLeast(0f),
            bottom = (height - top - inset - side).coerceAtLeast(0f),
            width = side,
            height = side,
        )
    }

    /**
     * The board's own button: the window's top-right corner, clear of whatever
     * really occupies *that corner* rather than of the deepest furniture on
     * either edge.
     *
     * A punch-hole in the middle of a right edge makes [SafeArea.right] a full
     * notch deep, and a button placed inside that inset gives up a corner nothing
     * covers. The cutout's actual rects say the corner is free, so the button
     * sits flush and the table's Right seat can keep the full height of its edge
     * beside it. Where a cutout does reach the corner — a corner punch-hole, a
     * notch running into it — [SafeArea.cornerTopRight] reports the depth and the
     * button yields by exactly that.
     *
     * Only a full-screen board asks for this square: a board that paints its own
     * menu is free play or online, both of which span the window. A tutorial
     * board is laid out inside the shell's content zone and the shell places the
     * context's menu ([panelRect]).
     */
    fun windowRect(width: Float, height: Float): Rect {
        val corner = SafeArea.cornerTopRight()
        return rect(width, height, corner.right, corner.top)
    }

    /**
     * The square on a panel that has already been cut by [safe] — the tutorial
     * shell's menu, which floats in the top-right of its safe-inset body. The
     * panel is inside the cutout already, so the edge insets are the whole
     * reservation.
     */
    fun panelRect(width: Float, height: Float, safe: SafeArea.Insets): Rect =
        rect(width, height, safe.right, safe.top)

    /**
     * The insets the table lays out inside, given the corner menu's [menu] square
     * (null where no menu floats over the table).
     *
     * Portrait hands span the board's width, so the corner is not spare felt:
     * when a menu floats over it, reserve its band above the table; the normal
     * seat gutter then keeps both Opposite's tiles and label below the button.
     * Wide boards, and boards whose pane ends below the menu, retain their
     * existing corner space — a landscape hand runs along the *short* edge, so
     * the table answers the same square by moving that column in horizontally
     * instead ([inego.zungjunggame.render.table.PlayTableView]); losing the top of
     * the row there would fit-scale the whole hand down.
     *
     * These insets apply to every table layout pass, not to the button or HUD,
     * which stay inside the platform safe area.
     */
    fun tableInsets(
        width: Float,
        height: Float,
        safe: SafeArea.Insets,
        menu: Rect?,
    ): SafeArea.Insets =
        if (menu != null &&
            height - safe.top - safe.bottom > width - safe.left - safe.right
        ) {
            // The button's own square is the band: never above the table's top
            // rim, which the per-side inset already holds below a notch.
            safe.copy(top = (height - menu.bottom).coerceAtLeast(safe.top))
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
