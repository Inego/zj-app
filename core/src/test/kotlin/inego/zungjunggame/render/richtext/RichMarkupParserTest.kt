package inego.zungjunggame.render.richtext

import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.reference.ReferenceCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RichMarkupParserTest {

    @Test
    fun headingsParagraphsAndEmphasis() {
        val doc = RichMarkupParser.parse(
            """
            # Title
            ## Sub
            ### Nested
            Hello **bold** and *italic* and __under__.
            """.trimIndent(),
        )
        assertEquals(4, doc.blocks.size)
        val h1 = assertIs<Block.Heading>(doc.blocks[0])
        assertEquals(1, h1.level)
        assertEquals("Title", (h1.spans.single() as Inline.Text).text)

        val h2 = assertIs<Block.Heading>(doc.blocks[1])
        assertEquals(2, h2.level)

        val h3 = assertIs<Block.Heading>(doc.blocks[2])
        assertEquals(3, h3.level)
        assertEquals("Nested", (h3.spans.single() as Inline.Text).text)

        val p = assertIs<Block.Paragraph>(doc.blocks[3])
        val texts = p.spans.filterIsInstance<Inline.Text>()
        assertTrue(texts.any { it.text == "bold" && it.style.bold })
        assertTrue(texts.any { it.text == "italic" && it.style.italic })
        assertTrue(texts.any { it.text == "under" && it.style.underline })
    }

    @Test
    fun brProducesLineBreak() {
        val spans = RichMarkupParser.parseInlines(
            "purposes<br>(*but it gives you additional scoring points!*)",
        )
        assertTrue(spans.any { it is Inline.LineBreak })
        val texts = spans.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertTrue(texts.contains("purposes"))
        assertTrue(texts.contains("("))
        assertTrue(spans.filterIsInstance<Inline.Text>().any { it.style.italic && it.text.contains("but") })
        // Variants
        assertTrue(RichMarkupParser.parseInlines("a<br/>b").any { it is Inline.LineBreak })
        assertTrue(RichMarkupParser.parseInlines("a<br />b").any { it is Inline.LineBreak })
        assertTrue(RichMarkupParser.parseInlines("a<BR>b").any { it is Inline.LineBreak })
        // Must not treat <br> as an autolink page id.
        assertTrue(
            RichMarkupParser.parseInlines("<br>").none {
                it is Inline.Text && it.linkHref != null
            },
        )
    }

    @Test
    fun tilesInline() {
        val spans = RichMarkupParser.parseInlines("chow {1m}{2m}{3m} done")
        assertIs<Inline.Text>(spans[0])
        val t1 = assertIs<Inline.TileRun>(spans[1])
        assertEquals(Tiles.CHAR_1, t1.tiles.single())
        val t2 = assertIs<Inline.TileRun>(spans[2])
        assertEquals(Tiles.CHAR_2, t2.tiles.single())
        val t3 = assertIs<Inline.TileRun>(spans[3])
        assertEquals(Tiles.CHAR_3, t3.tiles.single())
    }

    @Test
    fun tileGroupToken() {
        val spans = RichMarkupParser.parseInlines("hand {123m EE}")
        val run = spans.filterIsInstance<Inline.TileRun>().single()
        assertEquals(
            listOf(Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3, Tiles.EAST, Tiles.EAST),
            run.tiles.toList(),
        )
    }

    @Test
    fun invalidTileStaysLiteral() {
        val spans = RichMarkupParser.parseInlines("x {notile} y")
        val joined = spans.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertTrue(joined.contains("{notile}"))
    }

    @Test
    fun centerBlock() {
        val doc = RichMarkupParser.parse(
            """
            intro

            :::center
            **centered** {1p}
            :::

            after
            """.trimIndent(),
        )
        assertEquals(3, doc.blocks.size)
        assertIs<Block.Paragraph>(doc.blocks[0]).also {
            assertEquals(RichAlign.Left, it.align)
        }
        val mid = assertIs<Block.Paragraph>(doc.blocks[1])
        assertEquals(RichAlign.Center, mid.align)
        assertTrue(mid.spans.any { it is Inline.Text && it.style.bold })
        assertTrue(mid.spans.any { it is Inline.TileRun })
        assertIs<Block.Paragraph>(doc.blocks[2]).also {
            assertEquals(RichAlign.Left, it.align)
        }
    }

    @Test
    fun bulletAndNumberedLists() {
        val doc = RichMarkupParser.parse(
            """
            - first **item**
            - second

            1. alpha
            2. beta
            """.trimIndent(),
        )
        val bullets = assertIs<Block.BulletList>(doc.blocks[0])
        assertEquals(2, bullets.items.size)
        assertTrue(bullets.items[0].any { it is Inline.Text && it.style.bold })

        val nums = assertIs<Block.NumberedList>(doc.blocks[1])
        assertEquals(1, nums.start)
        assertEquals(2, nums.items.size)
    }

    @Test
    fun softLineBreakJoinsParagraph() {
        val doc = RichMarkupParser.parse(
            """
            line one
            line two
            """.trimIndent(),
        )
        val p = assertIs<Block.Paragraph>(doc.blocks.single())
        val text = p.spans.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertEquals("line one line two", text)
    }

    @Test
    fun softLineBreakJoinsListItem() {
        val doc = RichMarkupParser.parse(
            """
            - Within the same series, count **only one** (usually the highest
              that fits).
            - Next item
            """.trimIndent(),
        )
        val bullets = assertIs<Block.BulletList>(doc.blocks.single())
        assertEquals(2, bullets.items.size)
        val first = bullets.items[0]
            .filterIsInstance<Inline.Text>()
            .joinToString("") { it.text }
        assertEquals(
            "Within the same series, count only one (usually the highest that fits).",
            first,
        )
        val second = bullets.items[1]
            .filterIsInstance<Inline.Text>()
            .joinToString("") { it.text }
        assertEquals("Next item", second)
    }

    @Test
    fun softLineBreakJoinsNumberedListItem() {
        val doc = RichMarkupParser.parse(
            """
            1. First line of the item
               continues here.
            2. Second
            """.trimIndent(),
        )
        val nums = assertIs<Block.NumberedList>(doc.blocks.single())
        assertEquals(2, nums.items.size)
        val first = nums.items[0]
            .filterIsInstance<Inline.Text>()
            .joinToString("") { it.text }
        assertEquals("First line of the item continues here.", first)
    }

    @Test
    fun referenceCatalogMarkupParses() {
        // Smoke: every Reference browser page must parse (patterns + parents + basics/rules).
        // Content is loaded from assets/reference/<locale>/ (filesystem in unit tests).
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
        // Leaf pattern pages use: **id** · 中文 · [hl]**N points**[/]
        // Category/series parents use only: **id** · 中文  (no points on that line).
        val leafHeader = Regex(
            """(?m)^\*\*\d+(?:\.\d+)+\*\*\s*·\s*.+?\s*·\s*\[hl\]\*\*.*points""",
            RegexOption.IGNORE_CASE,
        )
        var leafPatternCount = 0
        for (section in ReferenceCatalog.Section.entries) {
            val pages = ReferenceCatalog.pages(section)
            assertTrue(pages.isNotEmpty(), "section ${section.id} has no pages")
            for ((id, _, markup) in pages) {
                val doc = RichMarkupParser.parse(markup)
                assertTrue(
                    doc.blocks.isNotEmpty(),
                    "empty parse for ${section.id}/$id",
                )
                assertTrue(
                    doc.blocks.any { it is Block.Heading },
                    "missing heading for ${section.id}/$id",
                )
                if (leafHeader.containsMatchIn(markup)) leafPatternCount++
            }
        }
        assertEquals(44, leafPatternCount, "expected 44 official v3.3 leaf pattern pages")
        // Soft-wrap regression: list items in scoring basics must join.
        val basics = assertNotNull(
            ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, "scoring-basics"),
        )
        val basicsDoc = RichMarkupParser.parse(basics.markup)
        val allBulletText = basicsDoc.blocks
            .filterIsInstance<Block.BulletList>()
            .flatMap { it.items }
            .joinToString("\n") { item ->
                item.filterIsInstance<Inline.Text>().joinToString("") { it.text }
            }
        assertTrue(
            allBulletText.contains("usually the highest that fits"),
            "list soft-join failed in scoring basics: $allBulletText",
        )
    }

    @Test
    fun namedColorShortClose() {
        val spans = RichMarkupParser.parseInlines("A [hl]Hu[/] B")
        val texts = spans.filterIsInstance<Inline.Text>()
        assertEquals("A ", texts[0].text)
        assertEquals(null, texts[0].style.color)
        assertEquals("Hu", texts[1].text)
        assertEquals(RichColor.Named("hl"), texts[1].style.color)
        assertEquals(" B", texts[2].text)
        assertEquals(null, texts[2].style.color)
    }

    @Test
    fun namedColorLongClose() {
        val spans = RichMarkupParser.parseInlines("[danger]trap[/danger]")
        val t = spans.filterIsInstance<Inline.Text>().single()
        assertEquals("trap", t.text)
        assertEquals(RichColor.Named("danger"), t.style.color)
    }

    @Test
    fun nestedColors() {
        val spans = RichMarkupParser.parseInlines("[hl]a [good]b[/] c[/]")
        val texts = spans.filterIsInstance<Inline.Text>()
        assertEquals("a ", texts[0].text)
        assertEquals(RichColor.Named("hl"), texts[0].style.color)
        assertEquals("b", texts[1].text)
        assertEquals(RichColor.Named("good"), texts[1].style.color)
        assertEquals(" c", texts[2].text)
        assertEquals(RichColor.Named("hl"), texts[2].style.color)
    }

    @Test
    fun hexColor() {
        val spans = RichMarkupParser.parseInlines("[#88AADD]x[/]")
        val t = spans.filterIsInstance<Inline.Text>().single()
        assertEquals("x", t.text)
        val hex = assertIs<RichColor.Hex>(t.style.color)
        // #88AADD → ARGB 0xFF88AADD
        assertEquals(0xFF88AADD.toInt(), hex.argb)
    }

    @Test
    fun hexWithAlpha() {
        val spans = RichMarkupParser.parseInlines("[#11223380]x[/]")
        val hex = assertIs<RichColor.Hex>(
            spans.filterIsInstance<Inline.Text>().single().style.color,
        )
        // RRGGBBAA 11 22 33 80 → ARGB 0x80112233
        assertEquals(0x80112233.toInt(), hex.argb)
    }

    @Test
    fun unknownColorTagStaysLiteral() {
        val spans = RichMarkupParser.parseInlines("x [notacolor]y[/] z")
        val joined = spans.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertTrue(joined.contains("[notacolor]"))
        assertTrue(joined.contains("[/]"))
        assertTrue(spans.none { it is Inline.Text && it.style.color != null })
    }

    @Test
    fun colorComposesWithBold() {
        val spans = RichMarkupParser.parseInlines("**[hl]X[/]**")
        val t = spans.filterIsInstance<Inline.Text>().single()
        assertEquals("X", t.text)
        assertTrue(t.style.bold)
        assertEquals(RichColor.Named("hl"), t.style.color)
    }

    @Test
    fun differentColorsDoNotMerge() {
        val spans = RichMarkupParser.parseInlines("[hl]a[/][good]b[/]")
        val texts = spans.filterIsInstance<Inline.Text>()
        assertEquals(2, texts.size)
        assertEquals(RichColor.Named("hl"), texts[0].style.color)
        assertEquals(RichColor.Named("good"), texts[1].style.color)
    }

    @Test
    fun markdownLinkWithBoldLabel() {
        val spans = RichMarkupParser.parseInlines("see [**2.1.1** Mixed](pattern-2.1.1) now")
        val texts = spans.filterIsInstance<Inline.Text>()
        assertEquals("see ", texts[0].text)
        assertEquals(null, texts[0].linkHref)

        val linked = texts.filter { it.linkHref == "pattern-2.1.1" }
        assertTrue(linked.isNotEmpty())
        assertTrue(linked.any { it.style.bold && it.text.contains("2.1.1") })
        assertTrue(linked.none { it.style.underline })
        assertTrue(linked.all { it.style.color == RichColor.Named("link") || it.style.bold })

        assertEquals(" now", texts.last().text)
        assertEquals(null, texts.last().linkHref)
    }

    @Test
    fun markdownLinkPreservesExplicitColor() {
        val spans = RichMarkupParser.parseInlines("[[hl]highlighted link[/]](pattern-1.1)")
        val linked = spans.filterIsInstance<Inline.Text>().filter { it.linkHref == "pattern-1.1" }
        assertTrue(linked.isNotEmpty())
        assertTrue(linked.any { it.style.color == RichColor.Named("hl") })
        assertTrue(linked.none { it.style.underline })
    }

    @Test
    fun colorTagStillWorksBesideLinks() {
        val spans = RichMarkupParser.parseInlines("[hl]pts[/] and [Mixed](pattern-2.1.1)")
        val highlighted = spans.filterIsInstance<Inline.Text>().first { it.text == "pts" }
        assertEquals(RichColor.Named("hl"), highlighted.style.color)
        assertEquals(null, highlighted.linkHref)
        val link = spans.filterIsInstance<Inline.Text>().first { it.linkHref == "pattern-2.1.1" }
        assertEquals("Mixed", link.text)
    }

    @Test
    fun autolinkHttp() {
        val spans = RichMarkupParser.parseInlines("go <https://example.com/zj> end")
        val link = spans.filterIsInstance<Inline.Text>().first { it.linkHref != null }
        assertEquals("https://example.com/zj", link.linkHref)
        assertEquals("https://example.com/zj", link.text)
        assertFalse(link.style.underline)
        assertEquals(RichColor.Named("link"), link.style.color)
    }

    @Test
    fun parentSummaryLinksParse() {
        ReferenceCatalog.configure(locale = ReferenceCatalog.DEFAULT_LOCALE)
        val page = assertNotNull(
            ReferenceCatalog.page(ReferenceCatalog.Section.Scoring, "pattern-2.1"),
        )
        val doc = RichMarkupParser.parse(page.markup)
        val hrefs = doc.blocks
            .flatMap { block ->
                when (block) {
                    is Block.BulletList -> block.items.flatten()
                    is Block.Paragraph -> block.spans
                    is Block.Heading -> block.spans
                    is Block.NumberedList -> block.items.flatten()
                    is Block.Table -> block.header.flatten() +
                        block.rows.flatten().flatten()
                }
            }
            .filterIsInstance<Inline.Text>()
            .mapNotNull { it.linkHref }
            .toSet()
        assertTrue("pattern-2.1.1" in hrefs)
        assertTrue("pattern-2.1.2" in hrefs)
    }

    @Test
    fun pipeTableParsesHeaderBodyAndAlignment() {
        val doc = RichMarkupParser.parse(
            """
            before

            | Name | Score |
            |:-----|------:|
            | **Maria** | [hl]+80[/] |
            | Ken | +12 |

            after
            """.trimIndent(),
        )
        assertEquals(3, doc.blocks.size)
        assertIs<Block.Paragraph>(doc.blocks[0])
        val table = assertIs<Block.Table>(doc.blocks[1])
        assertEquals(2, table.header.size)
        assertEquals(listOf(RichAlign.Left, RichAlign.Right), table.aligns)
        assertEquals("Name", (table.header[0].single() as Inline.Text).text)
        assertEquals(2, table.rows.size)

        val maria = table.rows[0][0].filterIsInstance<Inline.Text>()
        assertTrue(maria.any { it.text == "Maria" && it.style.bold })
        val highlighted = table.rows[0][1].filterIsInstance<Inline.Text>()
        assertTrue(highlighted.any { it.style.color == RichColor.Named("hl") })
        assertIs<Block.Paragraph>(doc.blocks[2])
    }

    @Test
    fun tableEscapedPipeAndMissingCellsNormalize() {
        val table = assertIs<Block.Table>(
            RichMarkupParser.parse(
                """
                | a \| b | c |
                |---|---|
                | only |
                """.trimIndent(),
            ).blocks.single(),
        )
        assertEquals(2, table.header.size)
        val first = table.header[0].filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertEquals("a | b", first)
        // A short body row is padded with an empty cell to the header width.
        assertEquals(2, table.rows.single().size)
        assertTrue(table.rows.single()[1].isEmpty())
    }

    @Test
    fun pipeWithoutDelimiterStaysParagraph() {
        val doc = RichMarkupParser.parse(
            """
            a | b
            plain text
            """.trimIndent(),
        )
        assertTrue(doc.blocks.none { it is Block.Table })
        val text = assertIs<Block.Paragraph>(doc.blocks.single())
            .spans.filterIsInstance<Inline.Text>().joinToString("") { it.text }
        assertEquals("a | b plain text", text)
    }

    @Test
    fun tableEndsAtNonRowLine() {
        val doc = RichMarkupParser.parse(
            """
            | a | b |
            |---|---|
            | c | d |
            trailing text
            """.trimIndent(),
        )
        assertEquals(2, doc.blocks.size)
        assertIs<Block.Table>(doc.blocks[0])
        assertIs<Block.Paragraph>(doc.blocks[1])
    }

    @Test
    fun tableInsideCenterBlockParses() {
        val doc = RichMarkupParser.parse(
            """
            :::center
            | a | b |
            |---|---|
            | c | d |
            :::
            """.trimIndent(),
        )
        assertIs<Block.Table>(doc.blocks.single())
    }
}
