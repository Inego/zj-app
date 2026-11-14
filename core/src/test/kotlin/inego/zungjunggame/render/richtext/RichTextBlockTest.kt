package inego.zungjunggame.render.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure layout/parse/hit-test behavior of [RichTextBlock] helpers — no libGDX graphics.
 * (Full measure/draw needs FreeType fonts; covered by integration when running the game.)
 */
class RichTextBlockTest {

    /** Fixed-width stub: each character is 10px; line height 20; baseline 16. */
    private val stubMeasurer = object : TextMeasurer {
        override fun width(text: String, style: TextStyle, role: TextRole): Float =
            text.length * 10f

        override fun lineHeight(role: TextRole): Float = 20f

        override fun baselineFromTop(role: TextRole): Float = 16f
    }

    @Test
    fun applyDefaultAlignCentersLeftParagraphs() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(listOf(Inline.Text("hi")), RichAlign.Left),
                Block.Heading(2, listOf(Inline.Text("H")), RichAlign.Left),
                Block.BulletList(listOf(listOf(Inline.Text("item")))),
            ),
        )
        val centered = RichTextBlock.applyDefaultAlign(doc, RichAlign.Center)
        val p = centered.blocks[0] as Block.Paragraph
        val h = centered.blocks[1] as Block.Heading
        assertEquals(RichAlign.Center, p.align)
        assertEquals(RichAlign.Center, h.align)
        // Lists stay left.
        assertTrue(centered.blocks[2] is Block.BulletList)
    }

    @Test
    fun applyDefaultAlignLeavesExplicitCenter() {
        val doc = RichDoc(
            listOf(Block.Paragraph(listOf(Inline.Text("x")), RichAlign.Center)),
        )
        val out = RichTextBlock.applyDefaultAlign(doc, RichAlign.Center)
        assertEquals(RichAlign.Center, (out.blocks[0] as Block.Paragraph).align)
    }

    @Test
    fun applyDefaultAlignLeftIsNoOp() {
        val doc = RichDoc(
            listOf(Block.Paragraph(listOf(Inline.Text("x")), RichAlign.Left)),
        )
        val out = RichTextBlock.applyDefaultAlign(doc, RichAlign.Left)
        assertEquals(doc, out)
    }

    @Test
    fun markupWithBoldAndTilesParsesForTutorialBodies() {
        val doc = RichMarkupParser.parse(
            "A Chow is **exactly three** tiles, e.g. {234m}.",
        )
        val centered = RichTextBlock.applyDefaultAlign(doc, RichAlign.Center)
        val p = centered.blocks.single() as Block.Paragraph
        assertEquals(RichAlign.Center, p.align)
        assertTrue(p.spans.any { it is Inline.Text && it.style.bold })
        assertTrue(p.spans.any { it is Inline.TileRun })
    }

    @Test
    fun colorMarkupSurvivesAlignRewrite() {
        val doc = RichMarkupParser.parse("[hl]East[/] is the dealer.")
        val centered = RichTextBlock.applyDefaultAlign(doc, RichAlign.Center)
        val spans = (centered.blocks.single() as Block.Paragraph).spans
        val highlighted = spans.filterIsInstance<Inline.Text>().first { it.style.color != null }
        assertEquals(RichColor.Named("hl"), highlighted.style.color)
    }

    @Test
    fun linkAtHitsLinkedWordAndSpacesWithOriginOffset() {
        val block = RichTextBlock(measurer = stubMeasurer)
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text("See "),
                        Inline.Text(
                            "Mixed Triple",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "pattern-2.1.1",
                        ),
                        Inline.Text(" now"),
                    ),
                    align = RichAlign.Left,
                ),
            ),
        )
        block.setDoc(doc)

        val left = 50f
        val top = 200f
        val maxWidth = 500f
        block.bindHitOrigin(left = left, top = top, maxWidth = maxWidth)

        // "See " is 4 chars = 40px width.
        // "Mixed" starts at left + 40 = 90.
        // "Mixed" length 5 = 50px (90..140).
        // Space is at 140..150 (+ spaceExtra).
        // "Triple" length 6 = 60px (150..210).

        // Top is 200. Content height is 20. Baseline is at 16 from top => originY = 200 - 20 = 180.
        // Cap top / line top is around 200.
        val clickY = 190f // inside the line

        // Click on "See" (plain text) -> null
        assertNull(block.linkAt(left + 20f, clickY))

        // Click on "Mixed" -> "pattern-2.1.1"
        assertEquals("pattern-2.1.1", block.linkAt(left + 60f, clickY))

        // Click on space between "Mixed" and "Triple" -> "pattern-2.1.1"
        assertEquals("pattern-2.1.1", block.linkAt(left + 95f, clickY))

        // Click on "Triple" -> "pattern-2.1.1"
        assertEquals("pattern-2.1.1", block.linkAt(left + 120f, clickY))

        // Click on " now" -> null
        assertNull(block.linkAt(left + 170f, clickY))

        // Click outside line vertically -> null
        assertNull(block.linkAt(left + 60f, top + 50f))
        assertNull(block.linkAt(left + 60f, top - 100f))
    }

    @Test
    fun linkAtReturnsNullAfterSetDocUntilLaidOut() {
        val block = RichTextBlock(measurer = stubMeasurer)
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text(
                            "Link",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "pattern-1",
                        ),
                    ),
                    align = RichAlign.Left,
                ),
            ),
        )
        block.setDoc(doc)
        // Before bindHitOrigin or draw, laidOut is null.
        assertNull(block.linkAt(10f, 10f))

        block.bindHitOrigin(0f, 100f, 200f)
        assertEquals("pattern-1", block.linkAt(10f, 90f))

        // After updating doc, linkAt immediately returns null until next layout.
        block.setDoc(doc)
        assertNull(block.linkAt(10f, 90f))
    }

    @Test
    fun setMarkupAppliesRewriteCallback() {
        val block = RichTextBlock(measurer = stubMeasurer)
        var rewriteCalled = false

        // Left-aligned: "Hello " = 60px (0..60); "Link" = 40px (60..100); " World" = 60px (100..160)
        block.setMarkup("Hello [Link](test-href) World", defaultAlign = RichAlign.Left) { doc ->
            rewriteCalled = true
            // Strip href in rewrite
            val blocks = doc.blocks.map { b ->
                if (b is Block.Paragraph) {
                    val spans = b.spans.map { inln ->
                        if (inln is Inline.Text && inln.linkHref != null) {
                            inln.copy(linkHref = null)
                        } else inln
                    }
                    b.copy(spans = spans)
                } else b
            }
            RichDoc(blocks)
        }

        assertTrue(rewriteCalled)
        block.bindHitOrigin(0f, 100f, 400f)
        // Target x = 75 is inside "Link" (60..100). With href stripped by rewrite, linkAt returns null.
        assertNull(block.linkAt(75f, 90f))

        // Without rewrite, "Link" would be hit at x = 75
        val unrewrittenBlock = RichTextBlock(measurer = stubMeasurer)
        unrewrittenBlock.setMarkup("Hello [Link](test-href) World", defaultAlign = RichAlign.Left)
        unrewrittenBlock.bindHitOrigin(0f, 100f, 400f)
        assertEquals("test-href", unrewrittenBlock.linkAt(75f, 90f))
    }
}
