package inego.zungjunggame.render

import com.badlogic.gdx.utils.JsonReader
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.TestBundle
import inego.zungjunggame.render.reference.ReferenceAssetSources
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.render.richtext.Block
import inego.zungjunggame.render.richtext.RichMarkupParser
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeps the reference-content tree, its one taxonomy manifest, the navigation
 * strings in the UI bundles, and the [AppLocale] registry in step.
 *
 * There is exactly one source of truth for navigation: `reference/structure.json`
 * owns section/page ids, file paths, order, indent and numbers. Each locale owns
 * only its bundle strings and its bodies, so there is no second copy of the
 * taxonomy to compare — [structureIdsAndReferenceStringKeysAgree] pins the
 * structure ↔ bundle contract instead of a per-locale id list.
 *
 * Adding a language is therefore: create the body folder, add its `reference.*`
 * keys to the UI bundle, register it in [AppLocale]. Forget any step and a test
 * below fails.
 */
class ReferenceLocaleCoverageTest {

    private val assetsRoot: File = assertNotNull(
        ReferenceAssetSources.findAssetsRoot(),
        "assets/ not found from ${File(".").absolutePath}",
    )

    private val referenceRoot = File(assetsRoot, "reference")

    /** Locale pack directories: `reference/<tag>/`, ignoring README/structure.json. */
    private val localeDirs: List<File> =
        referenceRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()

    /** The taxonomy manifest, reduced to the ids a string set must match. */
    private data class Structure(
        val sectionIds: List<String>,
        val pageIds: List<String>,
    )

    private fun structure(): Structure {
        val text = assertNotNull(
            ReferenceAssetSources.default().readText("reference/structure.json"),
            "missing reference/structure.json",
        )
        val root = JsonReader().parse(text)
        val sectionIds = ArrayList<String>()
        val pageIds = ArrayList<String>()
        for (section in root.get("sections")) {
            sectionIds += section.getString("id")
            for (page in section.get("pages")) pageIds += page.getString("id")
        }
        return Structure(sectionIds, pageIds)
    }

    @Test
    fun thereIsAtLeastOneLocale() {
        assertTrue(localeDirs.isNotEmpty(), "no locale directories under $referenceRoot")
    }

    @Test
    fun everyLocaleDirIsRegisteredInAppLocale() {
        val registered = AppLocale.entries.map { it.tag.lowercase() }.toSet()
        val orphans = localeDirs.map { it.name }.filter { it.lowercase() !in registered }
        assertTrue(
            orphans.isEmpty(),
            "reference content exists for unregistered locales $orphans. " +
                "Register them in AppLocale (see NOTICE and the folder README) or remove them.",
        )
    }

    @Test
    fun everyNonDefaultLocaleHasAUiBundle() {
        for (locale in AppLocale.entries) {
            val expected = if (locale.tag == AppLocale.DEFAULT_TAG) {
                "ui.properties"
            } else {
                "ui_${locale.javaLocale.language}.properties"
            }
            val bundle = File(File(assetsRoot, "i18n"), expected)
            assertTrue(
                bundle.isFile,
                "AppLocale.${locale.name} (${locale.tag}) needs $expected",
            )
        }
    }

    @Test
    fun allShippedReferenceCatalogsLoadAndParse() {
        for (dir in localeDirs) {
            ReferenceCatalog.configure(locale = dir.name)
            for (section in ReferenceCatalog.Section.entries) {
                val pages = ReferenceCatalog.pages(section)
                assertTrue(pages.isNotEmpty(), "section ${section.id} has no pages in ${dir.name}")
                for ((id, _, markup) in pages) {
                    val doc = RichMarkupParser.parse(markup)
                    assertTrue(
                        doc.blocks.isNotEmpty(),
                        "empty parse for ${dir.name}/${section.id}/$id",
                    )
                    assertTrue(
                        doc.blocks.any { it is Block.Heading },
                        "missing heading in ${dir.name}/${section.id}/$id",
                    )
                }
            }
        }
    }

    /**
     * The base bundle's taxonomy keys (`reference.title`, section labels, page
     * titles) are exactly the structure: every id has its string, and no stale
     * key survives a removal. Keys outside these namespaces (e.g.
     * `reference.fallback_title`) are chrome and not part of the contract.
     * Cross-language parity is [BundleCoverageTest]'s job.
     */
    @Test
    fun structureIdsAndReferenceStringKeysAgree() {
        val structure = structure()
        val declared = TestBundle.declared(TestBundle.BASE_TAG)
        val expected = buildSet {
            add("reference.title")
            for (id in structure.sectionIds) add("reference.section.$id.label")
            for (id in structure.pageIds) add("reference.page.$id.title")
        }
        val actual = declared.keys.filter { isTaxonomyKey(it) }.toSet()
        assertEquals(expected, actual, "base bundle reference.* keys must match structure.json ids")
    }

    private fun isTaxonomyKey(key: String): Boolean =
        key == "reference.title" ||
            (key.startsWith("reference.section.") && key.endsWith(".label")) ||
            (key.startsWith("reference.page.") && key.endsWith(".title"))

    /**
     * The Reference reads as one language: a pack's strings come from the bundle
     * for that pack's language, not from whatever UI locale happens to be active.
     */
    @Test
    fun referenceStringsFollowThePackLanguage() {
        for (dir in localeDirs) {
            val packTag = dir.name
            ReferenceCatalog.configure(locale = packTag)
            val language = AppLocale.fromTag(packTag).javaLocale.language
            val bundleTag = if (language == Locale.US.language) TestBundle.BASE_TAG else language
            val declared = TestBundle.declared(bundleTag)

            assertEquals(
                ReferenceCatalog.browserTitle(),
                declared["reference.title"],
                "pack '$packTag' must serve its own language's browser title",
            )
            val page = assertNotNull(
                ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, "pattern-2.1.1"),
                "pattern-2.1.1 missing in $packTag",
            )
            assertEquals(
                declared["reference.page.pattern-2.1.1.title"],
                page.title,
                "pack '$packTag' page title must come from $bundleTag",
            )
        }

        // A tag with no pack (and no language pack) resolves to the default bundle.
        assertEquals(Locale.US, ReferenceCatalog.stringsLocale("zz-ZZ"))
        assertEquals(Locale.US, ReferenceCatalog.stringsLocale(ReferenceCatalog.DEFAULT_LOCALE))
        assertEquals(ReferenceCatalog.stringsLocale("zh-HK"), AppLocale.fromTag("zh-HK").javaLocale)
    }
}
