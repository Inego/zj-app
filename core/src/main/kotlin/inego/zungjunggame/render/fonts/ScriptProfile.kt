package inego.zungjunggame.render.fonts

import inego.zungjunggame.i18n.AppLocale

/**
 * Which primary writing system the UI and reference should use.
 *
 * Maps from [inego.zungjunggame.i18n.AppLocale] (and future tags). Latin/Cyrillic locales keep Roboto as
 * primary for tight Western metrics; CJK locales use a matching CJK primary.
 * Missing glyphs (e.g. Han in English pattern names) are filled by a secondary
 * face via [Fonts] + [FallbackText] — never left as tofu when a face exists.
 */
enum class ScriptProfile {
    /** English, French, Greek, Russian, … — bundled Roboto (Latin, Greek, Cyrillic). */
    LatinCyrillic,

    /** Simplified Chinese (`zh-CN`). */
    Hans,

    /** Traditional Chinese (`zh-HK`, `zh-TW`). */
    Hant,

    /** Japanese. */
    Jpan,
    ;

    /** True when primary is expected to be a CJK-capable face. */
    val primaryIsCjk: Boolean
        get() = this != LatinCyrillic

    companion object {
        fun forLocale(locale: AppLocale): ScriptProfile = forTag(locale.tag)

        /**
         * Resolve profile from a BCP 47-style tag even if not yet in [AppLocale]
         * (font layer ready before picker entries ship).
         */
        fun forTag(tag: String): ScriptProfile {
            val t = tag.trim()
            val lang = t.substringBefore('-').lowercase()
            val region = t.substringAfter('-', missingDelimiterValue = "").lowercase()
            return when (lang) {
                "zh" -> when (region) {
                    "hk", "tw", "mo", "hant" -> Hant
                    else -> Hans // zh, zh-CN, zh-SG, bare zh
                }
                "ja" -> Jpan
                else -> LatinCyrillic // en, fr, ru, …
            }
        }
    }
}
