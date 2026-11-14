package inego.zungjunggame.i18n

import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pack reader against a real zip built here, so coverage never depends on
 * the Gradle packer having run. Stage 3 consumes [LanguagePack.bundleReader] and
 * [LanguagePack.readText]; those two are what this class pins.
 */
class LanguagePackTest {

    private val tag = "zh-HK"
    private val bundleKey = "menu.title"
    private val bundleValue = "麻將"
    private val pagePath = "scoring/patterns/2.1.1.md"
    private val pageText = "# 二點一 一色三同順\n\n這是規則內容。\n"

    @BeforeTest
    fun setUp() {
        LanguagePack.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        LanguagePack.resetForTests()
    }

    @Test
    fun opensPackAndServesBundleAndPages() {
        val opened = mutableListOf<String>()
        val listener: (String) -> Unit = { opened += it }
        LanguagePack.addOpenedListener(listener)
        try {
            assertTrue(LanguagePack.openBytes(tag, packZip()))
            assertEquals(tag, LanguagePack.openTag)
            assertTrue(LanguagePack.isOpen(tag))
            assertEquals(listOf(tag), opened)
            assertEquals(pageText, LanguagePack.readText(pagePath))

            val props = Properties()
            LanguagePack.bundleReader()!!.use { props.load(it) }
            assertEquals(bundleValue, props.getProperty(bundleKey))
        } finally {
            LanguagePack.removeOpenedListener(listener)
        }
    }

    @Test
    fun parseIndexIgnoresBlankAndCommentLines() {
        val text = "en-US\n\n# a comment\nru\n   \n#zh-HK\nja\n"
        assertEquals(listOf("en-US", "ru", "ja"), LanguagePack.parseIndex(text))
    }

    @Test
    fun ensureMissingTagDoesNotOpenOrThrow() {
        var asked: String? = null
        LanguagePack.fetcher = LanguagePack.Fetcher { t, _ -> asked = t }

        LanguagePack.ensure("no-such-tag")

        assertEquals("no-such-tag", asked)
        assertNull(LanguagePack.openTag)
        assertFalse(LanguagePack.isOpen("no-such-tag"))
        assertNull(LanguagePack.bundleReader())
        assertNull(LanguagePack.readText(pagePath))
    }

    @Test
    fun corruptZipClosesTheOpenPack() {
        assertTrue(LanguagePack.openBytes(tag, packZip()))

        assertFalse(LanguagePack.openBytes(tag, "definitely not a zip".toByteArray()))

        assertNull(LanguagePack.openTag)
        assertFalse(LanguagePack.isOpen(tag))
        assertNull(LanguagePack.readText(pagePath))
    }

    private fun packZip(): ByteArray {
        val bundle = "$bundleKey=$bundleValue\n".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack"))
            zip.write(packBlob(bundle, pagesBlob(pagePath to pageText)))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** The single-entry framing: `<bundle-len>\n<bundle-bytes><pages>`. */
    private fun packBlob(bundle: ByteArray, pages: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bundle.size.toString().toByteArray(Charsets.US_ASCII))
        out.write('\n'.code)
        out.write(bundle)
        out.write(pages)
        return out.toByteArray()
    }

    /** The pages framing: `<path>\n<utf8-byte-count>\n<bytes>`, repeated. */
    private fun pagesBlob(vararg pages: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((path, body) in pages) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            out.write(path.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.write(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            out.write('\n'.code)
            out.write(bytes)
        }
        return out.toByteArray()
    }
}
