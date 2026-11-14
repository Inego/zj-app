package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.tutorial.QuizOptionMark
import inego.zungjunggame.render.ui.AppPalette

/**
 * The tutorial's one chip on the felt: a soft plate with a fitted label.
 *
 * The coach's Back / Next row and every quiz control — dock, options, bins — draw
 * through this object, so the same control family cannot drift into two fills,
 * two rims, two label faces or two sizes. Geometry is
 * [inego.zungjunggame.tutorial.slide.layout.SlideChromeLayout]'s; this only
 * paints a rect it is given.
 */
internal object TableChip {

    /** What the chip is saying. Hover is resolved from the pointer, not the tone. */
    sealed interface Tone {
        /** An ordinary control: felt-green plate, quiet rim. */
        data object Idle : Tone

        /** The beat's primary action, or a selected option: accent rim, bright label. */
        data object Emphasized : Tone

        /** A review mark: the plate takes the mark's own colour. */
        data class Marked(val mark: QuizOptionMark) : Tone
    }

    private val glyphLayout = GlyphLayout()

    fun draw(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        pointerX: Float,
        pointerY: Float,
        tone: Tone = Tone.Idle,
        alpha: Float = 1f,
    ) {
        val hovered =
            !pointerX.isNaN() && !pointerY.isNaN() &&
                pointerX >= left && pointerX <= left + w &&
                pointerY >= bottom && pointerY <= bottom + h
        // An idle review mark says nothing a plain chip does not; treat it as one.
        val mark = (tone as? Tone.Marked)?.mark?.takeIf { it != QuizOptionMark.Idle }
        val emphasized = tone == Tone.Emphasized
        val bg = when (mark) {
            QuizOptionMark.Hit -> AppPalette.tint(AppPalette.chipSuccessFill, 0.96f * alpha)
            QuizOptionMark.FalsePositive -> AppPalette.tint(AppPalette.chipDangerFill, 0.94f * alpha)
            QuizOptionMark.Miss -> AppPalette.tint(AppPalette.chipInfoFill, 0.94f * alpha)
            QuizOptionMark.Idle, null -> when {
                emphasized && hovered -> AppPalette.tint(AppPalette.chipFillEmphasizedHover, 0.94f * alpha)
                emphasized -> AppPalette.tint(AppPalette.chipFillEmphasized, 0.90f * alpha)
                hovered -> AppPalette.tint(AppPalette.chipFillHover, 0.88f * alpha)
                else -> AppPalette.tint(AppPalette.chipFill, 0.72f * alpha)
            }
        }
        val rim = when (mark) {
            QuizOptionMark.Hit -> tint(AppPalette.success, alpha)
            QuizOptionMark.FalsePositive -> tint(AppPalette.danger, alpha)
            QuizOptionMark.Miss -> tint(AppPalette.info, alpha)
            QuizOptionMark.Idle, null -> when {
                emphasized || hovered -> tint(AppPalette.accent, alpha)
                else -> AppPalette.tint(AppPalette.chipRim, 0.40f * alpha)
            }
        }
        UiPlate.drawPlate(batch, px, left, bottom, w, h, bg, rim, OverlayChrome.buttonRimPx())

        glyphLayout.setText(Fonts.button, label, Color.WHITE, w - Fonts.px(16f), Align.center, true)
        val font = Fonts.button
        font.color = when {
            mark != null || emphasized || hovered -> AppPalette.tint(AppPalette.inkStrong, alpha)
            else -> tint(AppPalette.ink, alpha)
        }
        font.draw(
            batch, label,
            left + Fonts.px(8f),
            bottom + h * 0.5f + glyphLayout.height * 0.5f,
            w - Fonts.px(16f),
            Align.center,
            true,
        )
        font.color = Color.WHITE
    }

    private fun tint(color: Color, alpha: Float): Color =
        Color(color.r, color.g, color.b, color.a * alpha)
}
