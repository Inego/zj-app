package inego.zungjunggame.i18n

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hot-switching on the web must wait for the target pack. The staged startup set
 * ships no `i18n/ui`, so applying before [LanguagePack] holds the pack made
 * `I18NBundle` throw `MissingResourceException` and aborted the switch.
 */
class LocaleControllerTest {

    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        LanguagePack.resetForTests()
        LocaleController.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LanguagePack.resetForTests()
        LocaleController.resetForTests()
    }

    @Test
    fun switchingWaitsForTheTargetPackBeforeTouchingL10n() {
        L10n.bootstrap(AppLocale.DEFAULT.javaLocale)
        val english = L10n.get("menu.title")
        assertEquals(english, TestBundle.declared(TestBundle.BASE_TAG).getValue("menu.title"))

        // Selected on the web, but its zip has not been fetched yet.
        LocaleController.setLocale(AppLocale.Russian)

        // Nothing applied: no fallback to the absent `i18n/ui`, no exception.
        assertEquals(AppLocale.DEFAULT, LocaleController.current)
        assertEquals(english, L10n.get("menu.title"))

        // The fetch lands and opens the pack; the switch now completes from it.
        assertTrue(LanguagePack.openBytes(AppLocale.Russian.tag, pack(SENTINEL)))
        assertEquals(AppLocale.Russian, LocaleController.current)
        assertEquals(SENTINEL, L10n.get("menu.title"))
    }

    @Test
    fun aNewerSwitchSupersedesOneStillWaiting() {
        L10n.bootstrap(AppLocale.DEFAULT.javaLocale)
        LocaleController.setLocale(AppLocale.Russian)
        LocaleController.setLocale(AppLocale.German)

        // Only German's pack arrives; the abandoned Russian wait must not apply.
        assertTrue(LanguagePack.openBytes(AppLocale.German.tag, pack(GERMAN)))
        assertEquals(AppLocale.German, LocaleController.current)
        assertEquals(GERMAN, L10n.get("menu.title"))
    }

    private fun pack(title: String): ByteArray {
        val bundle = "menu.title=$title\n".toByteArray(Charsets.UTF_8)
        return LanguagePacker.writeZip(mapOf("pack" to LanguagePacker.writePack(bundle, emptyMap())))
    }

    private companion object {
        const val SENTINEL = "RU_PACK_SENTINEL"
        const val GERMAN = "DE_PACK_SENTINEL"
    }
}
