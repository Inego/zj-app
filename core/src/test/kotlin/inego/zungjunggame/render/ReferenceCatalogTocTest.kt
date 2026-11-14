package inego.zungjunggame.render

import inego.zungjunggame.render.reference.ReferenceCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceCatalogTocTest {

    @Test
    fun buildTocItemsIsOneRowPerPageInOrder() {
        val pages = listOf(
            ReferenceCatalog.Page("cat", "Category", "# Cat\n", "", number = "1"),
            ReferenceCatalog.Page("a", "Leaf A", "# A\n", "", number = "1.1"),
            ReferenceCatalog.Page("series", "Series", "# Series\n", "", number = "1.2"),
            ReferenceCatalog.Page("b", "Deep B", "# B\n", "", number = "1.2.1"),
            ReferenceCatalog.Page("c", "Deep C", "# C\n", "", number = "1.2.2"),
        )
        val items = ReferenceCatalog.buildTocItems(pages)
        assertEquals(listOf("cat", "a", "series", "b", "c"), items.map { it.id })
        assertEquals(listOf(0, 1, 1, 2, 2), items.map { it.level })
        assertEquals(listOf("1", "1.1", "1.2", "1.2.1", "1.2.2"), items.map { it.number })
        assertEquals(
            listOf("Category", "Leaf A", "Series", "Deep B", "Deep C"),
            items.map { it.label },
        )
        assertTrue(items.all { it.selectable })
    }

    @Test
    fun explicitLevelOverridesNumberDerivedIndent() {
        val nested = ReferenceCatalog.buildTocItems(
            listOf(ReferenceCatalog.Page("x", "Winds", "# X\n", "", number = "1", level = 2)),
        ).single()
        assertEquals(2, nested.level)

        val plain = ReferenceCatalog.buildTocItems(
            listOf(ReferenceCatalog.Page("y", "Overview", "# Y\n")),
        ).single()
        assertNull(plain.number)
        assertEquals(0, plain.level)
    }

    @Test
    fun aboutLicensePageHasLockedCreditAndThirdParty() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
        val page = assertNotNull(
            ReferenceCatalog.page(
                ReferenceCatalog.Section.Rules,
                ReferenceCatalog.ABOUT_PAGE_ID,
            ),
        )
        // Wording is content; the id, the non-blank page shape and the legal
        // attribution tokens are the contract.
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, page.id)
        assertTrue(page.title.isNotBlank())
        assertNotEquals(page.id, page.title)
        assertTrue(page.markup.isNotBlank())
        assertTrue(
            page.markup.contains("CC BY-NC-SA 3.0 HK"),
            "locked credit missing: ${page.markup}",
        )
        assertTrue(
            page.markup.contains("Alan Kwan"),
            "locked credit missing: ${page.markup}",
        )
        assertTrue(page.markup.contains("Not affiliated with or endorsed by Alan Kwan."))
        assertTrue(page.markup.contains("riichi-mahjong-tiles"))
        assertTrue(page.markup.contains("CC0"))
        assertTrue(page.markup.contains("Lucide"))
        assertTrue(page.markup.contains("Roboto"))
        assertTrue(page.markup.contains("Noto Sans SC"))
        assertTrue(page.markup.contains("https://www.zungjung.org/license.html"))

        val byAlias = ReferenceCatalog.resolveHref("about")
        assertTrue(byAlias is ReferenceCatalog.LinkTarget.Page)
        assertEquals(ReferenceCatalog.Section.Rules, byAlias.section)
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, byAlias.pageId)

        ReferenceCatalog.configure(locale = "ru")
        val ru = assertNotNull(
            ReferenceCatalog.page(
                ReferenceCatalog.Section.Rules,
                ReferenceCatalog.ABOUT_PAGE_ID,
            ),
        )
        assertEquals(ReferenceCatalog.ABOUT_PAGE_ID, ru.id)
        assertTrue(ru.title.isNotBlank())
        assertNotEquals(ru.id, ru.title)
        assertTrue(ru.markup.contains("CC BY-NC-SA 3.0 HK"))
        assertTrue(ru.markup.contains("https://www.zungjung.org/license.html"))
    }

    @Test
    fun resolveHrefPageIdFileAndPatternNumber() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)

        val byId = ReferenceCatalog.resolveHref("pattern-2.1.1")
        assertTrue(byId is ReferenceCatalog.LinkTarget.Page)
        assertEquals(ReferenceCatalog.Section.Scoring, byId.section)
        assertEquals("pattern-2.1.1", byId.pageId)

        val byHash = ReferenceCatalog.resolveHref("#scoring-basics")
        assertTrue(byHash is ReferenceCatalog.LinkTarget.Page)
        assertEquals("scoring-basics", byHash.pageId)

        val byFile = ReferenceCatalog.resolveHref("scoring/patterns/2.1.1.md")
        assertTrue(byFile is ReferenceCatalog.LinkTarget.Page)
        assertEquals("pattern-2.1.1", byFile.pageId)

        val byNumber = ReferenceCatalog.resolveHref("2.1.1")
        assertTrue(byNumber is ReferenceCatalog.LinkTarget.Page)
        assertEquals("pattern-2.1.1", byNumber.pageId)

        val external = ReferenceCatalog.resolveHref("https://example.com/x")
        assertTrue(external is ReferenceCatalog.LinkTarget.External)
        assertEquals(
            "https://example.com/x",
            external.url,
        )

        assertNull(ReferenceCatalog.resolveHref("no-such-page"))
    }

    /**
     * Structural proof that the `ru` pack — strings and bodies — really loaded,
     * rather than the default pack. Wording is content, so this asserts shape,
     * not copy; there is no en↔ru id comparison (ids live in one manifest).
     */
    @Test
    fun russianPackServesItsOwnStringsAndBodies() {
        ReferenceCatalog.configure(locale = "ru")
        assertEquals("ru", ReferenceCatalog.currentLocale())
        assertTrue(ReferenceCatalog.browserTitle().isNotBlank(), "ru browser title missing")
        val ruLabels = ReferenceCatalog.sectionLabels
        assertEquals(ReferenceCatalog.Section.entries.size, ruLabels.size)
        ReferenceCatalog.Section.entries.forEachIndexed { index, section ->
            val label = ruLabels[index]
            assertTrue(label.isNotBlank(), "section ${section.id} has no ru label")
            assertNotEquals(section.id, label, "section ${section.id} echoes its id")
        }

        val mixed = assertNotNull(
            ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, "pattern-2.1.1"),
            "pattern-2.1.1 missing in ru",
        )
        assertTrue(mixed.title.isNotBlank())
        assertNotEquals(mixed.id, mixed.title)
        assertEquals("2.1.1", mixed.number)
        assertTrue(mixed.markup.isNotBlank())
        assertTrue(mixed.markup.contains("2.1.1"), "ru body heading missing its pattern number")
        assertTrue(mixed.markup.contains("40"), "points should remain numeric")

        // Stable link targets still resolve under the ru pack.
        val byId = ReferenceCatalog.resolveHref("pattern-2.1.1")
        assertTrue(byId is ReferenceCatalog.LinkTarget.Page)
        assertEquals("pattern-2.1.1", byId.pageId)
    }
}
