package inego.zungjunggame.features.auth

import inego.zungjunggame.net.AliasPolicy
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Editor courtesy on top of [AliasPolicy]: a name the column table calls
 * narrow can still paint wider than the identity face can spare.
 *
 * The budget is the wider of 16 `W`s and 8 `中`s in [Fonts.label], the face
 * the menu chip uses. A stored name that is already past that width can be
 * shortened; it cannot grow. When the face is not loaded (a unit test, a
 * frame before the first atlas) the column and grapheme rules stand alone.
 */
internal object AliasPaintLimit {
    fun allows(before: String, after: String): Boolean {
        if (!AliasPolicy.allowsEdit(before, after)) return false
        val budget = budgetOrNull() ?: return true
        val afterW = widthOrNull(after) ?: return true
        if (afterW <= budget) return true
        val beforeW = widthOrNull(before) ?: return true
        return afterW <= beforeW
    }

    private fun budgetOrNull(): Float? = try {
        Fonts.ensureCreated()
        val latin = Fonts.textWidth(Fonts.label, "W".repeat(AliasPolicy.MAX_COLUMNS))
        val cjk = Fonts.textWidth(Fonts.label, "中".repeat(AliasPolicy.MAX_COLUMNS / 2))
        max(latin, cjk)
    } catch (_: Throwable) {
        null
    }

    private fun widthOrNull(text: String): Float? = try {
        Fonts.textWidth(Fonts.label, text)
    } catch (_: Throwable) {
        null
    }
}
