package inego.zungjunggame.render.ui

import inego.zungjunggame.render.fonts.Fonts

/**
 * World-px travel past which a press on a card control is a drag, not a tap.
 *
 * 12 design px scaled by [Fonts.px]. A raw 12 against screen pixels is about
 * 4 dp on a dense phone, under the platform's own tap slop, so a fingertip
 * roll never completed the press.
 */
fun tapSlopPx(): Float = Fonts.px(12f)
