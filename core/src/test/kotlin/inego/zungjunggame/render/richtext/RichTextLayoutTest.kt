package inego.zungjunggame.render.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichTextLayoutTest {

    /** Fixed-width stub: each character is 10px; line height 20. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String, style: TextStyle, role: TextRole): Float =
            text.length * 10f

        override fun lineHeight(role: TextRole): Float = when (role) {
            TextRole.H1 -> 30f
            TextRole.H2 -> 26f
            TextRole.H3 -> 22f
            TextRole.Body -> 20f
            TextRole.Small -> 12f
        }

        override fun baselineFromTop(role: TextRole): Float = lineHeight(role) * 0.8f
    }

    private fun layout(doc: RichDoc, maxWidth: Float = 200f): LaidOutDoc {
        val params = RichTextLayoutParams(
            maxWidth = maxWidth,
            paragraphGap = 10f,
            headingGapAfter = 12f,
            listItemGap = 4f,
            listIndent = 20f,
            tileAspect = 0.75f,
            tileHeightFactor = 1.2f,
            tileGapFraction = 0.1f,
        )
        return RichTextLayout(measurer, params).layout(doc)
    }

    @Test
    fun emptyDocHasZeroHeight() {
        val laid = layout(RichDoc(emptyList()))
        assertEquals(0f, laid.height)
        assertTrue(laid.ops.isEmpty())
    }

    @Test
    fun singleWordProducesTextOp() {
        val doc = RichDoc(listOf(Block.Paragraph(listOf(Inline.Text("Hi")))))
        val laid = layout(doc)
        assertTrue(laid.height > 0f)
        val textOps = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(1, textOps.size)
        assertEquals("Hi", textOps.single().text)
    }

    @Test
    fun linkHrefPropagatesToDrawOps() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text(
                            "Go",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "pattern-1.1",
                        ),
                    ),
                ),
            ),
        )
        val laid = layout(doc)
        val op = laid.ops.filterIsInstance<DrawOp.Text>().single()
        assertEquals("Go", op.text)
        assertEquals("pattern-1.1", op.linkHref)
        assertFalse(op.style.underline)
    }

    @Test
    fun multiWordLinkKeepsHrefOnInterWordSpaces() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text(
                            "Mixed Triple Chow",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "pattern-2.1.1",
                        ),
                    ),
                ),
            ),
        )
        val laid = layout(doc)
        val texts = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(
            listOf("Mixed", " ", "Triple", " ", "Chow"),
            texts.map { it.text },
        )
        assertTrue(texts.all { it.linkHref == "pattern-2.1.1" })
    }

    @Test
    fun wrapsLongLine() {
        // 10 chars * 10px = 100px each word; maxWidth 150 → two lines.
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(Inline.Text("abcdefghij klmnopqrst")),
                ),
            ),
        )
        val laid = layout(doc, maxWidth = 150f)
        val textOps = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(2, textOps.size)
        // Second line should sit lower in content space (smaller y).
        assertTrue(textOps[1].baselineY < textOps[0].baselineY)
    }

    @Test
    fun wrapsCjkContinuousText() {
        // CJK text without spaces: 7 chars (70px) + 7 chars (70px). Max width 75px -> two lines.
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(Inline.Text("與門前清不同，平和允許開吃。")),
                ),
            ),
        )
        val laid = layout(doc, maxWidth = 75f)
        val textOps = laid.ops.filterIsInstance<DrawOp.Text>()
        // 6 tokens on line 1 ("與", "門", "前", "清", "不", "同，") + 6 tokens on line 2
        assertEquals(12, textOps.size)
        val line1 = textOps.take(6)
        val line2 = textOps.drop(6)
        // All line 1 ops share the same top baseline; all line 2 ops share lower baseline
        val y1 = line1.first().baselineY
        val y2 = line2.first().baselineY
        assertTrue(y2 < y1)
        assertTrue(line1.all { it.baselineY == y1 })
        assertTrue(line2.all { it.baselineY == y2 })
        assertEquals("與", line1.first().text)
        assertEquals("同，", line1.last().text)
        assertEquals("平", line2.first().text)
        assertEquals("吃。", line2.last().text)
    }

    @Test
    fun hardLineBreakSplitsWithinParagraph() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text("Hello"),
                        Inline.LineBreak,
                        Inline.Text("World"),
                    ),
                ),
            ),
        )
        // Wide enough that soft-wrap would not split "Hello World".
        val laid = layout(doc, maxWidth = 500f)
        val textOps = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(listOf("Hello", "World"), textOps.map { it.text })
        assertTrue(textOps[1].baselineY < textOps[0].baselineY)
        // Still one paragraph block → no double paragraphGap; height ≈ 2 line boxes.
        assertEquals(2 * 20f * 1.2f, laid.height, 0.01f) // lineBox = max(text, tileH)
    }

    @Test
    fun centerShiftsX() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    spans = listOf(Inline.Text("AB")), // 20px wide
                    align = RichAlign.Center,
                ),
            ),
        )
        val laid = layout(doc, maxWidth = 100f)
        val text = laid.ops.filterIsInstance<DrawOp.Text>().single()
        assertEquals(40f, text.x, 0.01f) // (100-20)/2
    }

    @Test
    fun tilesProduceTileOps() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(Inline.TileRun(intArrayOf(0, 1, 2))),
                ),
            ),
        )
        val laid = layout(doc)
        val tiles = laid.ops.filterIsInstance<DrawOp.Tile>()
        assertEquals(3, tiles.size)
        assertEquals(0, tiles[0].type)
        assertEquals(1, tiles[1].type)
        assertEquals(2, tiles[2].type)
        // Side by side: increasing x centers.
        assertTrue(tiles[1].cx > tiles[0].cx)
        assertTrue(tiles[2].cx > tiles[1].cx)
    }

    @Test
    fun listEmitsMarkerAndItem() {
        val doc = RichDoc(
            listOf(
                Block.BulletList(
                    listOf(listOf(Inline.Text("item"))),
                ),
            ),
        )
        val laid = layout(doc)
        val texts = laid.ops.filterIsInstance<DrawOp.Text>()
        assertTrue(texts.any { it.text == "•" })
        assertTrue(texts.any { it.text == "item" })
        val item = texts.first { it.text == "item" }
        assertTrue(item.x >= 20f) // list indent
    }

    @Test
    fun tableEmitsBoldHeaderBodyAndRules() {
        val doc = RichDoc(
            listOf(
                Block.Table(
                    header = listOf(listOf(Inline.Text("HA")), listOf(Inline.Text("HB"))),
                    rows = listOf(
                        listOf(listOf(Inline.Text("CA")), listOf(Inline.Text("CB"))),
                    ),
                ),
            ),
        )
        val laid = layout(doc, maxWidth = 200f)
        val texts = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(listOf("HA", "HB", "CA", "CB"), texts.map { it.text })
        val header = texts.filter { it.text.startsWith("H") }
        assertTrue(header.all { it.style.bold })

        val rules = laid.ops.filterIsInstance<DrawOp.Fill>()
        assertEquals(3, rules.size) // top, header, bottom
        assertTrue(rules.all { it.x == 0f && it.w == 200f && it.h == 1f })
        // Top rule sits above the header rule, which sits above the bottom rule.
        assertTrue(rules[0].y > rules[1].y)
        assertTrue(rules[1].y > rules[2].y)
        assertEquals(0f, rules.last().y, 0.01f)
    }

    @Test
    fun tableRightAlignsCellWithinColumn() {
        val doc = RichDoc(
            listOf(
                Block.Table(
                    header = listOf(listOf(Inline.Text("HA")), listOf(Inline.Text("HB"))),
                    rows = emptyList(),
                    aligns = listOf(RichAlign.Left, RichAlign.Right),
                ),
            ),
        )
        val laid = layout(doc, maxWidth = 200f)
        val ha = laid.ops.filterIsInstance<DrawOp.Text>().first { it.text == "HA" }
        val hb = laid.ops.filterIsInstance<DrawOp.Text>().first { it.text == "HB" }
        assertEquals(6f, ha.x, 0.01f) // left cell: padX
        // Each column is 88 wide; the right cell hugs its right edge.
        assertEquals(174f, hb.x, 0.01f)
    }

    @Test
    fun tableWrapsLongCellWithinItsColumn() {
        val doc = RichDoc(
            listOf(
                Block.Table(
                    header = listOf(listOf(Inline.Text("H"))),
                    rows = listOf(listOf(listOf(Inline.Text("aaaa bbbb cccc")))),
                ),
            ),
        )
        // contentWidth 40: each 4-char word exactly one line.
        val laid = layout(doc, maxWidth = 52f)
        val words = laid.ops.filterIsInstance<DrawOp.Text>()
            .filter { it.text.length == 4 }
            .map { it.baselineY }
            .distinct()
        assertEquals(3, words.size)
        assertTrue(laid.height > 80f)
    }
}
