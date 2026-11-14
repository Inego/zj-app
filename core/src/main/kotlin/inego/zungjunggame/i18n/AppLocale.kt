package inego.zungjunggame.i18n

import inego.zungjunggame.render.fonts.ScriptProfile
import java.util.Locale

/**
 * Locales the app exposes in the language picker.
 *
 * [tag] is a BCP 47-style id stored in prefs and used for reference folders
 * when content exists. [javaLocale] drives [com.badlogic.gdx.utils.I18NBundle]
 * property file resolution (`ui_ru.properties`, …).
 *
 * [endonym] is the language’s self-name (always shown in the picker so users
 * can find their language regardless of the current UI locale).
 *
 * Future ship locales (e.g. `zh-HK`, `fr`, `ja`) are added here only; the
 * picker lists [entries] automatically. Font faces follow [scriptProfile].
 */
enum class AppLocale(
    /** Stable prefs / catalog id (e.g. `en-US`, `ru`). */
    val tag: String,
    /** I18NBundle language (+ optional country). */
    val javaLocale: Locale,
    /** Native self-name for the language list (not translated). */
    val endonym: String,
) {
    English(
        tag = "en-US",
        javaLocale = Locale.US,
        endonym = "English",
    ),
    @Suppress("DEPRECATION")
    German(
        tag = "de",
        javaLocale = Locale("de"),
        endonym = "Deutsch",
    ),
    @Suppress("DEPRECATION")
    Spanish(
        tag = "es",
        javaLocale = Locale("es"),
        endonym = "Español",
    ),
    @Suppress("DEPRECATION")
    French(
        tag = "fr",
        javaLocale = Locale("fr"),
        endonym = "Français",
    ),
    @Suppress("DEPRECATION")
    Italian(
        tag = "it",
        javaLocale = Locale("it"),
        endonym = "Italiano",
    ),
    @Suppress("DEPRECATION")
    Hungarian(
        tag = "hu",
        javaLocale = Locale("hu"),
        endonym = "Magyar",
    ),
    @Suppress("DEPRECATION")
    Dutch(
        tag = "nl",
        javaLocale = Locale("nl"),
        endonym = "Nederlands",
    ),
    @Suppress("DEPRECATION")
    Polish(
        tag = "pl",
        javaLocale = Locale("pl"),
        endonym = "Polski",
    ),
    @Suppress("DEPRECATION")
    Portuguese(
        tag = "pt",
        javaLocale = Locale("pt"),
        endonym = "Português",
    ),
    @Suppress("DEPRECATION")
    Serbian(
        tag = "sr",
        javaLocale = Locale("sr"),
        endonym = "Srpski",
    ),
    @Suppress("DEPRECATION")
    Finnish(
        tag = "fi",
        javaLocale = Locale("fi"),
        endonym = "Suomi",
    ),
    @Suppress("DEPRECATION")
    Turkish(
        tag = "tr",
        javaLocale = Locale("tr"),
        endonym = "Türkçe",
    ),
    @Suppress("DEPRECATION")
    Greek(
        tag = "el",
        javaLocale = Locale("el"),
        endonym = "Ελληνικά",
    ),
    @Suppress("DEPRECATION") // TeaVM's Locale has this constructor, not forLanguageTag.
    Russian(
        tag = "ru",
        javaLocale = Locale("ru"),
        endonym = "Русский",
    ),
    @Suppress("DEPRECATION")
    Ukrainian(
        tag = "uk",
        javaLocale = Locale("uk"),
        endonym = "Українська",
    ),
    @Suppress("DEPRECATION")
    Japanese(
        tag = "ja",
        javaLocale = Locale("ja"),
        endonym = "日本語",
    ),
    @Suppress("DEPRECATION")
    ChineseTraditionalHK(
        tag = "zh-HK",
        javaLocale = Locale("zh", "HK"),
        endonym = "繁體中文（香港）",
    ),
    ;

    /** Primary writing system for [Fonts] / [ScriptProfile]. */
    val scriptProfile: ScriptProfile
        get() = ScriptProfile.forLocale(this)

    companion object {
        const val DEFAULT_TAG: String = "en-US"

        val DEFAULT: AppLocale = English

        /**
         * First shipped locale matched by [tags], in order, or [DEFAULT].
         *
         * A tag matches an entry's [AppLocale.tag] (hyphen or underscore),
         * otherwise the language subtag (`ru-RU` → `ru`, `en-GB` → `en-US`,
         * every `zh*` → `zh-HK`). An unknown tag is skipped so a later
         * preference can still hit. [DEFAULT] is the result only when every
         * tag misses.
         */
        fun match(tags: List<String>): AppLocale =
            tags.firstNotNullOfOrNull { matchOne(it) } ?: DEFAULT

        /** [match] for one tag. Blank or unknown becomes [DEFAULT]. */
        fun fromTag(tag: String?): AppLocale = matchOne(tag) ?: DEFAULT

        /**
         * `language` or `language-COUNTRY` for [match]. Empty when [locale]
         * has no language. TeaVM's [Locale] has no `toLanguageTag`.
         */
        fun localeTag(locale: Locale?): String {
            if (locale == null) return ""
            val lang = locale.language.trim().lowercase()
            if (lang.isEmpty() || lang == "und") return ""
            val country = locale.country.trim()
            return if (country.isEmpty()) lang else "$lang-${country.uppercase()}"
        }

        private fun matchOne(tag: String?): AppLocale? {
            if (tag.isNullOrBlank()) return null
            val t = tag.trim()
            entries.firstOrNull {
                it.tag.equals(t, ignoreCase = true) || it.tag.replace('-', '_').equals(t, ignoreCase = true)
            }?.let { return it }
            // Accept bare language (`en`, `ru`, `zh`) or longer tags (`ru-RU`, `zh_HK`).
            val lang = t.substringBefore('-').substringBefore('_').lowercase()
            if (lang.isEmpty()) return null
            return entries.firstOrNull {
                it.javaLocale.language.equals(lang, ignoreCase = true)
            }
        }

        /**
         * The picker entry whose [javaLocale] is exactly [locale], or null for a
         * locale the app does not ship. Used to find the language pack the app
         * should read for a bundle request.
         */
        fun fromJavaLocale(locale: Locale?): AppLocale? {
            if (locale == null) return null
            return entries.firstOrNull { it.javaLocale == locale }
        }
    }
}
