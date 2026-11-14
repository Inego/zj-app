package inego.zungjunggame.i18n

import com.badlogic.gdx.utils.ObjectMap
import com.badlogic.gdx.utils.PropertiesUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The packing rules against the real authoring files, so coverage never depends
 * on [LanguagePacker.main] or the Gradle task having run.
 *
 * The reader is [LanguagePackTest]'s job; this pins the two decisions the pack
 * format rests on — the bundle-source fall-through (`zh-HK` → `ui_zh`) and the
 * English fill for untranslated keys — plus the deterministic writer.
 */
class LanguagePackerTest {

    private val i18n: File = File(TestBundle.assetsRoot(), "i18n")

    @Test
    fun zhHkFallsThroughToUiZh() {
        val suffixes = LanguagePacker.sourceSuffixes("zh-HK")
        assertEquals(listOf("zh_HK", "zh", ""), suffixes)
        assertFalse(File(i18n, "ui_zh_HK.properties").isFile, "fixture assumption: no ui_zh_HK")

        val source = LanguagePacker.pickLanguageBundle(suffixes) { File(i18n, it).isFile }
        assertEquals("ui_zh.properties", source)

        val zh = TestBundle.declared("zh")
        val base = TestBundle.declared(TestBundle.BASE_TAG)
        val resolved = LanguagePacker.resolveBundle(zh, base)
        assertEquals(zh.getValue("menu.title"), resolved.getValue("menu.title"))
        assertTrue(resolved.getValue("menu.title") != base.getValue("menu.title"), "zh-HK pack served English")
    }

    /**
     * The English fill for a key a locale has not translated yet. No shipped locale
     * omits a base key today — `untranslated-keys.txt` is empty — so the omission the
     * rule needs is made here rather than borrowed from the allowlist.
     */
    @Test
    fun untranslatedKeysResolveToTheEnglishBase() {
        val base = TestBundle.declared(TestBundle.BASE_TAG)
        for (locale in AppLocale.entries) {
            if (locale.tag == AppLocale.DEFAULT_TAG) continue
            val language = TestBundle.declared(locale.javaLocale.language)
            // A key this locale translates away from the base, so the fill is observable.
            val key = assertNotNull(
                language.keys.sorted().firstOrNull { it in base && language.getValue(it) != base.getValue(it) },
                "${locale.tag} translates no key away from the base",
            )
            val resolved = LanguagePacker.resolveBundle(language - key, base)
            assertEquals(base.getValue(key), resolved[key], "${locale.tag} must fall back for '$key'")
        }
    }

    @Test
    fun propertiesSurviveTheDeterministicWriter() {
        val bytes = LanguagePacker.writeProperties(
            linkedMapOf(
                "menu.title" to "中庸麻雀",
                "menu.signed_in_as" to "{0} - {1}",
                "plain" to "value",
            ),
        )

        // ISO-8859-1 bytes that stay ASCII, so no backend encoding can mangle them.
        assertTrue(String(bytes, StandardCharsets.ISO_8859_1).none { it.code > 0x7E })

        val props = ObjectMap<String, String>()
        PropertiesUtils.load(props, InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8))
        assertEquals("中庸麻雀", props["menu.title"])
        assertEquals("{0} - {1}", props["menu.signed_in_as"])
        assertEquals("value", props["plain"])
    }

    @Test
    fun writerOutputIgnoresMapOrder() {
        val a = LanguagePacker.writeProperties(linkedMapOf("b" to "2", "a" to "1"))
        val b = LanguagePacker.writeProperties(linkedMapOf("a" to "1", "b" to "2"))
        assertContentEquals(a, b)
    }

    @Test
    fun pagesBlobIsDeterministicAndRoundTripsThroughTheReader() {
        // A body with newlines and a line shaped like another page's header: only
        // the byte count keeps it intact.
        val tricky = "line one\nabout.md\n999\nnot a frame\n"
        val pages = linkedMapOf(
            "scoring/patterns/2.1.1.md" to "# 2.1.1\n".toByteArray(),
            "about.md" to tricky.toByteArray(),
        )
        val reversed = linkedMapOf(
            "about.md" to tricky.toByteArray(),
            "scoring/patterns/2.1.1.md" to "# 2.1.1\n".toByteArray(),
        )
        assertContentEquals(LanguagePacker.writePages(pages), LanguagePacker.writePages(reversed))

        val bundle = LanguagePacker.writeProperties(mapOf("k" to "v"))
        val pack = LanguagePacker.writePack(bundle, pages)
        LanguagePack.resetForTests()
        try {
            assertTrue(LanguagePack.openBytes("zh-HK", LanguagePacker.writeZip(mapOf("pack" to pack))))
            assertContentEquals(bundle, LanguagePack.bundleBytes())
            assertEquals(tricky, LanguagePack.readText("about.md"))
            assertEquals("# 2.1.1\n", LanguagePack.readText("scoring/patterns/2.1.1.md"))
        } finally {
            LanguagePack.resetForTests()
        }
    }
}
