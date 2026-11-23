package inego.zungjunggame.render.richtext

import com.badlogic.gdx.graphics.g2d.BitmapFont
import inego.zungjunggame.render.fonts.Fonts

/**
 * [TextMeasurer] backed by the shared primary (+ fallback) FreeType fonts.
 *
 * Chrome and Reference use the same [Fonts] family; missing glyphs (e.g. Han in
 * English articles) are measured via [Fonts.textWidth] / fallback faces.
 */
class GdxTextMeasurer : TextMeasurer {

    override fun width(text: String, style: TextStyle, role: TextRole): Float {
        val font = fontFor(style, role)
        return Fonts.textWidth(font, text)
    }

    override fun lineHeight(role: TextRole): Float = fontFor(TextStyle.Plain, role).lineHeight

    override fun baselineFromTop(role: TextRole): Float {
        val font = fontFor(TextStyle.Plain, role)
        // BitmapFont.draw’s Y is the *cap-height top* (top of typical capitals),
        // not the typographic baseline. FreeType stores:
        //   ascent  = distance from that draw Y up to the tallest glyph
        //   capHeight = draw Y down to the true baseline
        // So the draw Y sits [ascent] below the top of the line box.
        return font.data.ascent.coerceAtLeast(0f)
    }

    /** Cap height for [role] (draw Y → true baseline in y-up). */
    fun capHeight(role: TextRole): Float = fontFor(TextStyle.Plain, role).data.capHeight

    fun fontFor(style: TextStyle, role: TextRole): BitmapFont {
        if (role == TextRole.Small) {
            Fonts.ensureCreated()
            // Small print has one weight: no small italic face exists, so an
            // italic span there draws at the small bold face rather than silently
            // losing its size.
            return when {
                style.bold || style.italic -> Fonts.smallBold
                else -> Fonts.small
            }
        }
        Fonts.ensureDocFonts()
        return when (role) {
            TextRole.H1 -> Fonts.docHeading1
            TextRole.H2 -> Fonts.docHeading2
            // H3 reuses body size but always bold so ### is not body-looking.
            TextRole.H3 -> when {
                style.italic -> Fonts.docBodyBoldItalic
                else -> Fonts.docBodyBold
            }
            TextRole.Body -> when {
                style.bold && style.italic -> Fonts.docBodyBoldItalic
                style.bold -> Fonts.docBodyBold
                style.italic -> Fonts.docBodyItalic
                else -> Fonts.docBody
            }
            TextRole.Small -> Fonts.small
        }
    }
}
