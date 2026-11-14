package inego.zungjunggame.features.notices

import com.badlogic.gdx.files.FileHandle
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.NoticeArg
import inego.zungjunggame.net.NoticeEntry
import inego.zungjunggame.net.NoticeKind
import inego.zungjunggame.net.UtcDate
import inego.zungjunggame.render.richtext.Block
import inego.zungjunggame.render.richtext.DrawOp
import inego.zungjunggame.render.richtext.Inline
import inego.zungjunggame.render.richtext.RichMarkupParser
import inego.zungjunggame.render.richtext.RichTextLayout
import inego.zungjunggame.render.richtext.RichTextLayoutParams
import inego.zungjunggame.render.richtext.TextMeasurer
import inego.zungjunggame.render.richtext.TextRole
import inego.zungjunggame.render.richtext.TextStyle
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notice engine's template fill: scalars into `{0}`, `{1}`, …
 * and a batch into a run of inline-table rows, with no placeholder left behind.
 */
class NoticeMarkupTest {
    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        L10n.bootstrap(Locale.US, requireUiBase())
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
    }

    private val placeholder = Regex("""\{(\d+)\}""")

    private fun laterPlay() = NoticeEntry(
        id = 1,
        kind = NoticeKind.LaterPlay,
        templateKey = "notice.later_play",
        args = listOf(
            NoticeArg.Text("Maria"),
            NoticeArg.Text("1204"),
            NoticeArg.Text("7K3H-M9PQ"),
            NoticeArg.Text("+48"),
            NoticeArg.Text("+80"),
            NoticeArg.Text("-2.67"),
            NoticeArg.Text("1214"),
            NoticeArg.Text("1211.33"),
        ),
        createdAt = "",
        seen = false,
        emailed = false,
    )

    @Test
    fun scalarArgumentsFillEveryPlaceholder() {
        val markup = NoticeMarkup.notice(laterPlay())
        assertFalse(placeholder.containsMatchIn(markup), "left a placeholder: '$markup'")
        for (value in listOf("Maria", "7K3H-M9PQ", "+48", "1211.33")) {
            assertTrue(markup.contains(value), "dropped argument '$value': '$markup'")
        }
    }

    @Test
    fun aBatchArgumentDrawsAnInlineTable() {
        val notice = NoticeEntry(
            id = 2,
            kind = NoticeKind.Digest,
            templateKey = "notice.digest",
            args = listOf(
                NoticeArg.Count(14L, "notice.digest.comparisons"),
                NoticeArg.Count(6L, "notice.digest.challenges"),
                NoticeArg.Text("+18.67"),
                NoticeArg.Text("1214"),
                NoticeArg.Text("1232.67"),
                NoticeArg.Batch(
                    rowTemplateKey = "notice.digest_row",
                    rows = listOf(
                        listOf(
                            NoticeArg.Text("7K3H-M9PQ"),
                            NoticeArg.Text("+48"),
                            NoticeArg.Text("Maria (1204) +80"),
                            NoticeArg.Text("-2.67"),
                        ),
                        listOf(
                            NoticeArg.Text("4R8N-2WCV"),
                            NoticeArg.Text("+12"),
                            NoticeArg.Count(3L, "notice.digest.players"),
                            NoticeArg.Text("+4.10"),
                        ),
                    ),
                ),
            ),
            createdAt = "",
            seen = false,
            emailed = false,
        )
        val markup = NoticeMarkup.notice(notice)
        assertFalse(placeholder.containsMatchIn(markup), "left a placeholder: '$markup'")
        assertTrue(markup.contains("7K3H-M9PQ") && markup.contains("4R8N-2WCV"))
        val doc = RichMarkupParser.parse(markup)
        assertTrue(doc.blocks.any { it is Block.Table }, "a batch must render as an inline table")
    }

    @Test
    fun aNoticeLeadsWithTheDayAndClockItWasSent() {
        val now = UtcDate.epochMillisFromIso("2026-10-01T20:00:00Z")!!
        val entry = laterPlay().copy(createdAt = "2026-10-01T18:27:03Z")
        val markup = NoticeMarkup.notice(entry, now)
        assertTrue(markup.contains(L10n.format("history.day_at", L10n.get("history.today"), "18:27")), "no send time: '$markup'")
        // The stamp leads its own line, so a wrapped body never buries it.
        assertTrue(markup.contains("<br/>"), "the stamp must be its own line: '$markup'")
        val doc = RichMarkupParser.parse(markup)
        assertEquals(1, doc.blocks.size, "the stamp must not become its own paragraph: ${doc.blocks.size}")
    }

    @Test
    fun anUnstampedNoticeStillRendersItsBody() {
        val entry = laterPlay().copy(createdAt = "")
        val markup = NoticeMarkup.notice(entry, UtcDate.epochMillisFromIso("2026-10-01T20:00:00Z")!!)
        assertFalse(markup.contains("·"), "a blank stamp must not leave a separator: '$markup'")
        assertTrue(markup.contains("Maria"), "body lost: '$markup'")
    }

    @Test
    fun aStampedDigestKeepsItsTable() {
        val now = UtcDate.epochMillisFromIso("2026-10-01T20:00:00Z")!!
        val notice = NoticeEntry(
            id = 3,
            kind = NoticeKind.Digest,
            templateKey = "notice.digest",
            args = listOf(
                NoticeArg.Count(14L, "notice.digest.comparisons"),
                NoticeArg.Count(6L, "notice.digest.challenges"),
                NoticeArg.Text("+18.67"),
                NoticeArg.Text("1214"),
                NoticeArg.Text("1232.67"),
                NoticeArg.Batch(
                    rowTemplateKey = "notice.digest_row",
                    rows = listOf(
                        listOf(
                            NoticeArg.Text("7K3H-M9PQ"),
                            NoticeArg.Text("+48"),
                            NoticeArg.Text("Maria (1204) +80"),
                            NoticeArg.Text("-2.67"),
                        ),
                    ),
                ),
            ),
            createdAt = "2026-10-01T18:27:03Z",
            seen = false,
            emailed = false,
        )
        val markup = NoticeMarkup.notice(notice, now)
        val doc = RichMarkupParser.parse(markup)
        assertTrue(doc.blocks.any { it is Block.Table }, "the digest's table was lost: '$markup'")
        // The stamp must land on the summary paragraph, not inside the table.
        val paragraph = doc.blocks.filterIsInstance<Block.Paragraph>().first()
        assertTrue(paragraph.spans.any { (it as? Inline.Text)?.text?.contains("18:27") == true })
    }

    /**
     * The stamp must fit the panel at every layout: a desktop-wide card and a
 * phone-narrow one. Nothing is allowed to overflow [maxWidth], which is what a
 * too-long stamp line would do.
     */
    @Test
    fun aStampedNoticeStaysInsideThePanelOnDesktopAndPhone() {
        val now = UtcDate.epochMillisFromIso("2026-10-01T20:00:00Z")!!
        val markup = NoticeMarkup.notice(laterPlay().copy(createdAt = "2026-10-01T18:27:03Z"), now)
        val doc = RichMarkupParser.parse(markup)
        for (width in listOf(2000f, 600f, 300f, 180f)) {
            val laid = RichTextLayout(fixedMeasurer, params(width)).layout(doc)
            val texts = laid.ops.filterIsInstance<DrawOp.Text>()
            assertTrue(texts.isNotEmpty(), "nothing laid out at width $width")
            val overflow = texts.filter { it.x + it.text.length * 10f > width + 0.01f }
            assertTrue(overflow.isEmpty(), "overflow at width $width: ${overflow.map { it.text }}")

            // Lines are the runs of ops sharing a baseline. The laid-out box is
            // y-up with the first line at the top, so reading order is
            // descending baseline; the stamp must be that first line, alone.
            val lines = texts.groupBy { it.baselineY }.toSortedMap(compareByDescending { it })
            val firstLine = lines.values.first().joinToString("") { it.text }.collapseSpaces()
            assertEquals(
                "Today · 18:27",
                firstLine,
                "the stamp must lead its own line at width $width",
            )
            val secondLine = lines.values.drop(1).firstOrNull()
                .orEmpty().joinToString("") { it.text }.collapseSpaces()
            assertTrue(
                secondLine.startsWith("Maria"),
                "the body must start after the stamp at width $width: '$secondLine'",
            )
        }
    }

    /** Laid-out runs carry the extra inter-word advance as spaces; drop them for comparison. */
    private fun String.collapseSpaces(): String = replace(Regex("\\s+"), " ").trim()

    /** Fixed 10px-per-character stub, so wrapping is exact and font-independent. */
    private val fixedMeasurer = object : TextMeasurer {
        override fun width(text: String, style: TextStyle, role: TextRole): Float = text.length * 10f
        override fun lineHeight(role: TextRole): Float = 20f
        override fun baselineFromTop(role: TextRole): Float = 16f
    }

    private fun params(maxWidth: Float) = RichTextLayoutParams(
        maxWidth = maxWidth,
        paragraphGap = 10f,
        headingGapAfter = 12f,
        listItemGap = 4f,
        listIndent = 20f,
    )

    @Test
    fun emptyListShowsTheStatusLineInstead() {
        assertEquals(L10n.get("notice.empty"), NoticeMarkup.list(emptyList()))
        assertEquals(L10n.get("history.loading"), NoticeMarkup.list(emptyList(), loading = true))
        assertEquals("boom", NoticeMarkup.list(emptyList(), error = "boom"))
    }

    private fun requireUiBase(): FileHandle {
        val cwd = File(".").canonicalFile
        val root = listOf(File(cwd, "assets"), File(cwd.parentFile, "assets"), File(cwd, "../assets"))
            .firstOrNull { File(it, "i18n/ui.properties").isFile }
            ?: error("assets/ with i18n/ not found from ${cwd.absolutePath}")
        return FileHandle(File(root, "i18n/ui"))
    }
}
