package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.g2d.BitmapFont
import inego.zungjunggame.render.fonts.Fonts

/** Cuts a single line with an ellipsis so it paints within [maxWidth]. */
object TextFit {
    private const val ELLIPSIS = "…"

    fun ellipsize(font: BitmapFont, text: String, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (Fonts.textWidth(font, text) <= maxWidth) return text
        if (Fonts.textWidth(font, ELLIPSIS) > maxWidth) return ""
        var low = 0
        var high = text.length
        var best = ELLIPSIS
        while (low <= high) {
            val mid = (low + high) ushr 1
            val end = codePointBoundary(text, mid)
            val candidate = text.substring(0, end).trimEnd() + ELLIPSIS
            if (Fonts.textWidth(font, candidate) <= maxWidth) {
                best = candidate
                low = end + 1
            } else {
                high = end - 1
            }
        }
        return best
    }

    /** [index] snapped back off a low surrogate so the prefix keeps whole code points. */
    private fun codePointBoundary(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        return if (text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()) {
            index - 1
        } else {
            index
        }
    }
}
