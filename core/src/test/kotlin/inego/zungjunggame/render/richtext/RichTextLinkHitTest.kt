package inego.zungjunggame.render.richtext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextLinkHitTest {

    /** Fixed-width stub: each character is 10px; line height 20. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String, style: TextStyle, role: TextRole): Float =
            text.length * 10f

        override fun lineHeight(role: TextRole): Float = 20f

        override fun baselineFromTop(role: TextRole): Float = 16f
    }

    private fun layoutMultiWordLink(
        label: String = "Mixed Triple",
        href: String = "pattern-2.1.1",
        maxWidth: Float = 400f,
        wordSpaceExtra: Float = 5f,
    ): Pair<LaidOutDoc, Float> {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text(
                            label,
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = href,
                        ),
                    ),
                ),
            ),
        )
        val params = RichTextLayoutParams(
            maxWidth = maxWidth,
            paragraphGap = 10f,
            headingGapAfter = 12f,
            listItemGap = 4f,
            listIndent = 20f,
            wordSpaceExtra = wordSpaceExtra,
        )
        return RichTextLayout(measurer, params).layout(doc) to wordSpaceExtra
    }

    @Test
    fun multiWordLinkEmitsSpaceOpsWithHref() {
        val (laid, _) = layoutMultiWordLink()
        val texts = laid.ops.filterIsInstance<DrawOp.Text>()
        assertEquals(listOf("Mixed", " ", "Triple"), texts.map { it.text })
        assertTrue(texts.all { it.linkHref == "pattern-2.1.1" })
    }

    @Test
    fun hitBetweenWordsIncludesSpaceAndWordSpaceExtra() {
        val (laid, spaceExtra) = layoutMultiWordLink(wordSpaceExtra = 5f)
        val texts = laid.ops.filterIsInstance<DrawOp.Text>()
        val space = texts.first { it.text.isBlank() }
        // Mid-space glyph (width 10) should hit.
        val midSpaceX = space.x + 5f
        val cy = space.baselineY
        assertEquals(
            "pattern-2.1.1",
            RichTextLinkHit.hrefAt(laid.ops, midSpaceX, cy, measurer, spaceExtra),
        )
        // Past the space glyph, still inside wordSpaceExtra advance.
        val inExtraX = space.x + 10f + spaceExtra * 0.5f
        assertEquals(
            "pattern-2.1.1",
            RichTextLinkHit.hrefAt(laid.ops, inExtraX, cy, measurer, spaceExtra),
        )
    }

    @Test
    fun hitOnEachWordOfMultiWordLink() {
        val (laid, spaceExtra) = layoutMultiWordLink()
        val texts = laid.ops.filterIsInstance<DrawOp.Text>().filter { it.text.isNotBlank() }
        for ((x, cy, text) in texts) {
            val cx = x + 1f
            assertEquals(
                "pattern-2.1.1",
                RichTextLinkHit.hrefAt(laid.ops, cx, cy, measurer, spaceExtra),
                "expected hit on word '$text'",
            )
        }
    }

    @Test
    fun missOutsideLink() {
        val (laid, spaceExtra) = layoutMultiWordLink()
        assertNull(RichTextLinkHit.hrefAt(laid.ops, -10f, 0f, measurer, spaceExtra))
        // Far to the right of the line.
        assertNull(RichTextLinkHit.hrefAt(laid.ops, 999f, 0f, measurer, spaceExtra))
    }

    @Test
    fun nonLinkedTextIsNotHit() {
        val doc = RichDoc(
            listOf(
                Block.Paragraph(
                    listOf(
                        Inline.Text("plain "),
                        Inline.Text(
                            "link",
                            style = TextStyle(color = RichColor.Named("link")),
                            linkHref = "x",
                        ),
                    ),
                ),
            ),
        )
        val params = RichTextLayoutParams(
            maxWidth = 400f,
            paragraphGap = 10f,
            headingGapAfter = 12f,
            listItemGap = 4f,
            listIndent = 20f,
        )
        val laid = RichTextLayout(measurer, params).layout(doc)
        val plain = laid.ops.filterIsInstance<DrawOp.Text>().first { it.text == "plain" }
        assertNull(
            RichTextLinkHit.hrefAt(laid.ops, plain.x + 1f, plain.baselineY, measurer),
        )
        val link = laid.ops.filterIsInstance<DrawOp.Text>().first { it.text == "link" }
        assertNotNull(
            RichTextLinkHit.hrefAt(laid.ops, link.x + 1f, link.baselineY, measurer),
        )
    }
}
