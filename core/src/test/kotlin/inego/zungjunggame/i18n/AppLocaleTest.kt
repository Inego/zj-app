package inego.zungjunggame.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLocaleTest {
    @Test
    fun fromTagResolvesEveryDeclaredLocaleAndItsLanguageVariants() {
        // Swept over the registry so adding a locale never means hand-adding cases.
        // Identity for the language variants holds while languages are distinct;
        // a second locale sharing a language would fail here and force the
        // bare-language rule to be decided rather than silently first-match.
        for (locale in AppLocale.entries) {
            val tag = locale.tag
            val language = locale.javaLocale.language

            assertEquals(locale, AppLocale.fromTag(tag), "canonical tag '$tag'")
            assertEquals(
                locale,
                AppLocale.fromTag(tag.replace('-', '_')),
                "underscore spelling '${tag.replace('-', '_')}'",
            )

            for (variant in listOf(language, "$language-ZZ", "${language}_ZZ")) {
                assertEquals(locale, AppLocale.fromTag(variant), "'$variant' for ${locale.name}")
            }
        }
    }

    @Test
    fun fromTagFallsBackToTheDefaultForUnknownOrBlank() {
        for (input in listOf(null, "", "   ", "zz-ZZ")) {
            assertEquals(AppLocale.DEFAULT, AppLocale.fromTag(input), "input '$input'")
        }
    }

    @Test
    fun entriesAreListedForPicker() {
        // Picker binds to AppLocale.entries; keep at least the ship pair.
        assertTrue(AppLocale.entries.size >= 2)
        // Endonyms are display strings a localization pass may reword, so only
        // pin the invariants a picker depends on: non-blank and pairwise distinct.
        assertTrue(
            AppLocale.entries.all { it.endonym.isNotBlank() },
            "blank AppLocale endonym: ${AppLocale.entries.map { it.endonym }}",
        )
        val endonyms = AppLocale.entries.map { it.endonym }
        assertEquals(endonyms.size, endonyms.toSet().size, "duplicate AppLocale endonyms: $endonyms")
    }

    @Test
    fun tagsAreUnique() {
        val tags = AppLocale.entries.map { it.tag.lowercase() }
        assertEquals(tags.size, tags.toSet().size, "duplicate AppLocale tags: $tags")
    }
}
