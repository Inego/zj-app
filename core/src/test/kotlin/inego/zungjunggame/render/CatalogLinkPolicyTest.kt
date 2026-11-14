package inego.zungjunggame.render

import inego.zungjunggame.render.reference.CatalogLinkPolicy
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.render.richtext.Block
import inego.zungjunggame.render.richtext.Inline
import inego.zungjunggame.render.richtext.RichColor
import inego.zungjunggame.render.richtext.RichDoc
import inego.zungjunggame.render.richtext.TextStyle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogLinkPolicyTest {

    @BeforeTest
    fun setUp() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
    }

    @Test
    fun isInternalPageIdentifiesValidCatalogPages() {
        assertTrue(CatalogLinkPolicy.isInternalPage("pattern-9.4.1"))
        assertTrue(CatalogLinkPolicy.isInternalPage("pattern-2.1.1"))
        assertTrue(CatalogLinkPolicy.isInternalPage("#scoring-basics"))
        assertTrue(CatalogLinkPolicy.isInternalPage("2.1.1"))
        assertTrue(CatalogLinkPolicy.isInternalPage("reference"))
        assertTrue(CatalogLinkPolicy.isInternalPage("scores"))
        assertTrue(CatalogLinkPolicy.isInternalPage("scoring"))
        assertTrue(CatalogLinkPolicy.isInternalPage("rules"))
        assertTrue(CatalogLinkPolicy.isInternalPage("about-license"))
        assertTrue(CatalogLinkPolicy.isInternalPage("about"))

        assertFalse(CatalogLinkPolicy.isInternalPage("https://example.com"))
        assertFalse(CatalogLinkPolicy.isInternalPage("mailto:test@example.com"))
        assertFalse(CatalogLinkPolicy.isInternalPage("non-existent-pattern"))
        assertFalse(CatalogLinkPolicy.isInternalPage(""))
    }

    @Test
    fun resolveTutorialHrefOnlyReturnsPageSectionAndId() {
        val valid = CatalogLinkPolicy.resolveTutorialHref("pattern-9.4.1")
        assertNotNull(valid)
        assertEquals(ReferenceCatalog.Section.Scoring, valid.first)
        assertEquals("pattern-9.4.1", valid.second)

        val refSection = CatalogLinkPolicy.resolveTutorialHref("reference")
        assertNotNull(refSection)
        assertEquals(ReferenceCatalog.Section.Scoring, refSection.first)

        val scoresSection = CatalogLinkPolicy.resolveTutorialHref("scores")
        assertNotNull(scoresSection)
        assertEquals(ReferenceCatalog.Section.Scoring, scoresSection.first)

        val refScoringSection = CatalogLinkPolicy.resolveTutorialHref("reference:scoring")
        assertNotNull(refScoringSection)
        assertEquals(ReferenceCatalog.Section.Scoring, refScoringSection.first)

        val rulesSection = CatalogLinkPolicy.resolveTutorialHref("rules")
        assertNotNull(rulesSection)
        assertEquals(ReferenceCatalog.Section.Rules, rulesSection.first)

        assertNull(CatalogLinkPolicy.resolveTutorialHref("https://example.com"))
        assertNull(CatalogLinkPolicy.resolveTutorialHref("non-existent-pattern"))
        assertNull(CatalogLinkPolicy.resolveTutorialHref(""))
    }

    @Test
    fun stripNonCatalogLinksKeepsInternalPagesAndStripsOthers() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text(
                            "Valid Link",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "pattern-9.4.1",
                        ),
                        Inline.Text(" and "),
                        Inline.Text(
                            "External Link",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "https://example.com",
                        ),
                        Inline.Text(" and "),
                        Inline.Text(
                            "Unknown Link",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "invalid-page",
                        ),
                        Inline.Text(" and "),
                        Inline.Text(
                            "Custom Highlight External",
                            style = TextStyle(color = RichColor.Named("hl")),
                            linkHref = "https://example.org",
                        ),
                    ),
                ),
            ),
        )

        val rejected = mutableListOf<Pair<String, CatalogLinkPolicy.RejectReason>>()
        val sanitized = CatalogLinkPolicy.stripNonCatalogLinks(doc) { href, reason ->
            rejected += href to reason
        }

        val spans = (sanitized.blocks.first() as Block.Paragraph).spans.filterIsInstance<Inline.Text>()
        assertEquals(7, spans.size)

        // Valid link: untouched
        assertEquals("Valid Link", spans[0].text)
        assertEquals("pattern-9.4.1", spans[0].linkHref)
        assertEquals(RichColor.Named("link"), spans[0].style.color)

        // Plain text: untouched
        assertEquals(" and ", spans[1].text)
        assertNull(spans[1].linkHref)

        // External link: stripped, color removed (was default "link")
        assertEquals("External Link", spans[2].text)
        assertNull(spans[2].linkHref)
        assertNull(spans[2].style.color)

        // Plain text: untouched
        assertEquals(" and ", spans[3].text)
        assertNull(spans[3].linkHref)

        // Unknown link: stripped, color removed
        assertEquals("Unknown Link", spans[4].text)
        assertNull(spans[4].linkHref)
        assertNull(spans[4].style.color)

        // Plain text: untouched
        assertEquals(" and ", spans[5].text)
        assertNull(spans[5].linkHref)

        // Custom highlight external: stripped href, but explicit highlight color preserved
        assertEquals("Custom Highlight External", spans[6].text)
        assertNull(spans[6].linkHref)
        assertEquals(RichColor.Named("hl"), spans[6].style.color)

        // Assert rejections reported
        assertEquals(3, rejected.size)
        assertEquals("https://example.com" to CatalogLinkPolicy.RejectReason.External, rejected[0])
        assertEquals("invalid-page" to CatalogLinkPolicy.RejectReason.Unresolved, rejected[1])
        assertEquals("https://example.org" to CatalogLinkPolicy.RejectReason.External, rejected[2])
    }
}
