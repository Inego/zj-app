package inego.zungjunggame.render.richtext

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.render.ui.AppPalette

/**
 * Maps semantic role names (and hex refs) to draw-time [Color]s.
 *
 * Content markup uses role names like `[hl]…[/]` and `[muted]…[/]`; the `hl`
 * role is the colour-neutral emphasis tag and paints the app accent. The RGB
 * values live only in [AppPalette]; this resolver just picks among its roles so
 * authors never write a hue into an article.
 */
class RichColorPalette(
    named: Map<String, Color>,
    val default: Color = AppPalette.ink,
) {
    private val named: Map<String, Color> =
        named.mapKeys { it.key.lowercase() }

    fun resolve(ref: RichColor?): Color = when (ref) {
        null -> default
        is RichColor.Named -> named[ref.name.lowercase()] ?: default
        is RichColor.Hex -> argbToColor(ref.argb)
    }

    companion object {
        /**
         * Canonical role names accepted by the markup parser (including aliases).
         * Keep in sync with [Default]'s map keys.
         */
        val KNOWN_NAMES: Set<String> = setOf(
            "muted", "dim",
            "accent", "hl",
            "good", "win",
            "bad", "danger",
            "warn",
            "info", "cool",
            "link",
            "wind",
        )

        /** Dark felt / ivory body — matches the current UI chrome. */
        val Default: RichColorPalette = RichColorPalette(
            named = mapOf(
                "muted" to AppPalette.inkMuted,
                "dim" to AppPalette.inkMuted,
                "accent" to AppPalette.accent,
                // Colour-neutral emphasis: the canonical score/value highlight.
                "hl" to AppPalette.accent,
                "good" to AppPalette.success,
                "win" to AppPalette.success,
                "bad" to AppPalette.danger,
                "danger" to AppPalette.danger,
                "warn" to AppPalette.warning,
                "info" to AppPalette.info,
                "cool" to AppPalette.info,
                // Default markdown hyperlink ink (soft sky blue on dark panels).
                "link" to AppPalette.info,
                "wind" to AppPalette.info,
            ),
        )

        /** Parse `#RRGGBB` or `#RRGGBBAA` (CSS-style alpha last) → packed ARGB. */
        fun parseHexTag(body: String): RichColor.Hex? {
            val s = body.trim().removePrefix("#")
            if (s.length != 6 && s.length != 8) return null
            if (s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            val value = s.toLong(16)
            val argb = when (s.length) {
                6 -> {
                    val rgb = value.toInt() and 0xFFFFFF
                    (0xFF shl 24) or rgb
                }
                else -> {
                    // RRGGBBAA → AARRGGBB
                    val rgb = ((value ushr 8) and 0xFFFFFF).toInt()
                    val a = (value and 0xFF).toInt()
                    (a shl 24) or rgb
                }
            }
            return RichColor.Hex(argb)
        }

        fun argbToColor(argb: Int): Color {
            val a = ((argb ushr 24) and 0xFF) / 255f
            val r = ((argb ushr 16) and 0xFF) / 255f
            val g = ((argb ushr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return Color(r, g, b, a)
        }
    }
}
