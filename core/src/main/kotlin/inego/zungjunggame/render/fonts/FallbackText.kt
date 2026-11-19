package inego.zungjunggame.render.fonts

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.text.Graphemes

/**
 * Measure and draw text with a **primary** face and optional **fallback** face.
 *
 * Per code unit (BMP [Char]): use primary when it has a real glyph; else fallback.
 * Consecutive runs on the same face are drawn together. Cross-face kerning is
 * not applied (acceptable at script boundaries).
 *
 * A grapheme cluster neither face can draw becomes one substitute glyph (see
 * [substituteUndrawable]) before anything is measured. The layout can only reach
 * BMP glyphs, so an astral code point is a substitute too — otherwise it would
 * paint at zero width and leave an invisible character in a name.
 *
 * Pure helpers [hasUsableGlyph] / [pickFont] / [substituteUndrawable] are
 * exposed for tests.
 */
object FallbackText {

    private val layout = GlyphLayout()

    /** First candidate either face can draw; `?` is guaranteed. */
    private val BOX_CANDIDATES = charArrayOf('\u25A1', '\uFFFD', '?')

    fun width(primary: BitmapFont, fallback: BitmapFont?, text: String): Float {
        if (text.isEmpty()) return 0f
        val painted = paintable(primary, fallback, text)
        if (fallback == null) {
            layout.setText(primary, painted)
            return layout.width
        }
        var total = 0f
        var i = 0
        while (i < painted.length) {
            val font = pickFont(primary, fallback, painted[i])
            val start = i
            i++
            while (i < painted.length && pickFont(primary, fallback, painted[i]) === font) {
                i++
            }
            layout.setText(font, painted.substring(start, i))
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
        val painted = paintable(primary, fallback, text)
        val face =
            if (fallback != null && painted.any { !hasUsableGlyph(primary, it) }) fallback else primary
        layout.setText(face, painted)
        return layout.height
    }

    /**
     * [text] with every grapheme cluster that [canDraw] cannot draw replaced by
     * [box]. One box per cluster, so a flag, an emoji sequence or a base with an
     * undrawable mark is a single glyph — and disappears in one Backspace.
     * Returns [text] itself when everything is drawable.
     */
    fun substituteUndrawable(text: String, canDraw: (Int) -> Boolean, box: Char): String {
        if (text.isEmpty()) return text
        var out: StringBuilder? = null
        var last = 0
        var i = 0
        while (i < text.length) {
            val end = Graphemes.next(text, i)
            var drawable = true
            var j = i
            while (j < end) {
                val cp = text.codePointAt(j)
                if (!canDraw(cp)) {
                    drawable = false
                    break
                }
                j += Character.charCount(cp)
            }
            if (!drawable) {
                if (out == null) out = StringBuilder(text.length)
                out.append(text, last, i).append(box)
                last = end
            }
            i = end
        }
        return out?.append(text, last, text.length)?.toString() ?: text
    }

    /**
     * What [width] and [draw] will actually paint. Pure ASCII is returned as is:
     * every face carries it, and that is the common case by far.
     */
    private fun paintable(primary: BitmapFont, fallback: BitmapFont?, text: String): String {
        if (text.isEmpty()) return text
        var ascii = true
        for (c in text) {
            if (c.code >= 0x80) {
                ascii = false
                break
            }
        }
        if (ascii) return text
        val canDraw: (Int) -> Boolean = { cp ->
            cp < 0x10000 &&
                (
                    hasUsableGlyph(primary, cp.toChar()) ||
                        (fallback != null && hasUsableGlyph(fallback, cp.toChar()))
                    )
        }
        val box = BOX_CANDIDATES.firstOrNull { canDraw(it.code) } ?: return text
        return substituteUndrawable(text, canDraw, box)
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
        val painted = paintable(primary, fallback, text)
        if (fallback == null) {
            drawTinted(primary, batch, painted, x, y, color)
            return
        }
        var cx = x
        var i = 0
        while (i < painted.length) {
            val font = pickFont(primary, fallback, painted[i])
            val start = i
            i++
            while (i < painted.length && pickFont(primary, fallback, painted[i]) === font) {
                i++
            }
            val span = painted.substring(start, i)
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
