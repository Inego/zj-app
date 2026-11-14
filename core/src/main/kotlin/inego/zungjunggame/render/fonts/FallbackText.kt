package inego.zungjunggame.render.fonts

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch

/**
 * Measure and draw text with a **primary** face and optional **fallback** face.
 *
 * Per code unit (BMP [Char]): use primary when it has a real glyph; else fallback.
 * Consecutive runs on the same face are drawn together. Cross-face kerning is
 * not applied (acceptable at script boundaries).
 *
 * Pure helpers [hasUsableGlyph] / [pickFont] are package-visible for tests.
 */
object FallbackText {

    private val layout = GlyphLayout()

    fun width(primary: BitmapFont, fallback: BitmapFont?, text: String): Float {
        if (text.isEmpty()) return 0f
        if (fallback == null) {
            layout.setText(primary, text)
            return layout.width
        }
        var total = 0f
        var i = 0
        while (i < text.length) {
            val font = pickFont(primary, fallback, text[i])
            val start = i
            i++
            while (i < text.length && pickFont(primary, fallback, text[i]) === font) {
                i++
            }
            layout.setText(font, text.substring(start, i))
            total += layout.width
        }
        return total
    }

    /**
     * Line height of [text] on the face [draw] will actually paint it with —
     * [primary] when it has the glyphs, else [fallback]. The two faces differ in
     * metrics, so this is the box to centre or stack a mixed-script run by.
     */
    fun height(primary: BitmapFont, fallback: BitmapFont?, text: String): Float {
        if (text.isEmpty()) return 0f
        val face =
            if (fallback != null && text.any { !hasUsableGlyph(primary, it) }) fallback else primary
        layout.setText(face, text)
        return layout.height
    }

    fun draw(
        batch: SpriteBatch,
        primary: BitmapFont,
        fallback: BitmapFont?,
        text: String,
        x: Float,
        y: Float,
        color: Color,
    ) {
        if (text.isEmpty()) return
        if (fallback == null) {
            drawTinted(primary, batch, text, x, y, color)
            return
        }
        var cx = x
        var i = 0
        while (i < text.length) {
            val font = pickFont(primary, fallback, text[i])
            val start = i
            i++
            while (i < text.length && pickFont(primary, fallback, text[i]) === font) {
                i++
            }
            val span = text.substring(start, i)
            drawTinted(font, batch, span, cx, y, color)
            layout.setText(font, span)
            cx += layout.width
        }
    }

    /**
     * [BitmapFont.setColor] mutates the live [BitmapFont.color] object.
     * Saving that reference and writing it back does not restore the prior tint
     * — coach body fades would leak onto the next [Fonts.body] draw (turn banner).
     */
    private fun drawTinted(
        font: BitmapFont,
        batch: SpriteBatch,
        text: String,
        x: Float,
        y: Float,
        color: Color,
    ) {
        FontTint.withTint(font.color, color) {
            font.draw(batch, text, x, y)
        }
    }

    /** True when [font] has a non-missing glyph for [c] (triggers incremental load). */
    fun hasUsableGlyph(font: BitmapFont, c: Char): Boolean {
        if (c.code == 0) return false
        val g = try {
            font.data.getGlyph(c)
        } catch (_: Throwable) {
            return false
        } ?: return false
        // FreeType missing glyph is often mapped to '\0' or id 0 with zero size.
        return !(g.id == 0 && c.code != 0) && (g.xadvance != 0 || g.width != 0 || g.height != 0)
    }

    fun pickFont(primary: BitmapFont, fallback: BitmapFont?, c: Char): BitmapFont {
        if (fallback == null) return primary
        if (hasUsableGlyph(primary, c)) return primary
        if (hasUsableGlyph(fallback, c)) return fallback
        return primary
    }
}
