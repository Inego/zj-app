package inego.zungjunggame.render

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LanguagePack
import inego.zungjunggame.i18n.LanguagePacker
import inego.zungjunggame.render.reference.ReferenceAssetSource
import inego.zungjunggame.render.reference.ReferenceAssetSources
import inego.zungjunggame.render.reference.ReferenceCatalog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reference bodies come from the open [LanguagePack]; the asset source is only
 * the authoring/dev fallback. Drives a real pack built from the authoring files
 * and a source that serves **only** `structure.json`, so a page body that loads
 * can only have come from the pack.
 */
class ReferenceCatalogPackTest {

    private lateinit var outRoot: File

    @BeforeTest
    fun setUp() {
        LanguagePack.resetForTests()
        L10n.resetForTests()
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
        outRoot = Files.createTempDirectory("langpacks").toFile()
    }

    @AfterTest
    fun tearDown() {
        LanguagePack.resetForTests()
        L10n.resetForTests()
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
        outRoot.deleteRecursively()
    }

    @Test
    fun pageBodiesComeFromTheOpenPackNotTheAssetSource() {
        val assets = assertNotNull(ReferenceAssetSources.findAssetsRoot(), "assets/ not found")
        LanguagePacker.pack(assets, outRoot)
        val zip = File(outRoot, "lang/ru.zip")
        assertTrue(zip.isFile, "packer did not write ${zip.absolutePath}")
        assertTrue(LanguagePack.openBytes("ru", zip.readBytes()))

        val structureOnly = ReferenceAssetSource { path ->
            if (path == "reference/structure.json") {
                ReferenceAssetSources.default().readText(path)
            } else {
                null
            }
        }
        ReferenceCatalog.configure(source = structureOnly, locale = "ru")

        for (section in ReferenceCatalog.Section.entries) {
            val pages = ReferenceCatalog.pages(section)
            assertTrue(pages.isNotEmpty(), "section ${section.id} is empty under the pack")
            for ((id, _, markup) in pages) {
                assertTrue(markup.isNotBlank(), "page $id has no body")
            }
        }

        val mixed = assertNotNull(
            ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, "pattern-2.1.1"),
        )
        assertTrue(mixed.markup.contains("2.1.1"), "pack body lost its pattern number")
    }
}
