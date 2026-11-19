package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts

/**
 * One line of label text, shrunk uniformly to fit the slot it is given — the
 * painting half of the fit contract [Fonts.fitScale] declares.
 *
 * Every fitted label goes through here: a plate button's caption, an option
 * row's name after its control. The face is measured at its natural scale,
 * fitted to the slot, then painted at that same scale (through
 * [Fonts.withFaceScale]), so the ink and the slot cannot disagree and no caller
 * re-derives the centering. A translation longer than its slot gets a smaller
 * face down to [Fonts.MIN_FIT_SCALE]; past the floor it overflows on purpose
 * rather than shrinking into illegibility.
 */
object FittedLabel {

    /** Where the ink sits inside its slot. */
    enum class Align {
        /** Ink starts at the slot's left edge — a label after its control. */
        Left,

        /** Ink is centred in the slot — a plate caption. */
        Center,

        /** Ink ends at the slot's right edge — a numeric column's header. */
        Right,
    }

    /**
     * Paints [text] on one line inside its slot.
     *
     * @param left the slot's left edge.
     * @param centerY the slot's vertical centre; the ink's line box is centred
     *   on it.
     * @param maxWidth the slot's width. The face shrinks so the ink fits it,
     *   down to the readable floor of [Fonts.fitScale].
     * @param color the ink.
     * @param align where the ink sits in the slot.
     */
    fun draw(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        left: Float,
        centerY: Float,
        maxWidth: Float,
        color: Color,
        align: Align = Align.Left,
    ) {
        if (text.isEmpty()) return
        val natural = Fonts.textWidth(font, text)
        val scale = Fonts.fitScale(natural, maxWidth)
        Fonts.withFaceScale(font, scale) {
            // At the floor the run is left overflowing: [Fonts.fitScale] has
            // already said it cannot fit at a readable size.
            val inkW = if (scale == 1f) natural else Fonts.textWidth(font, text)
            val inkH = Fonts.textHeight(font, text)
            val x = when (align) {
                Align.Left -> left
                Align.Center -> left + (maxWidth - inkW) * 0.5f
                Align.Right -> left + (maxWidth - inkW)
            }
            Fonts.drawText(batch, font, text, x, centerY + inkH * 0.5f, color)
        }
    }
}
