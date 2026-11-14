package inego.zungjunggame.persist

import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.state.RecordWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `Reference` route's component-state codec: round trip plus the gates that
 * keep unreadable state from breaking a cold start.
 */
class ReferenceNavCodecTest {

    @Test
    fun negativeScrollsAreClampedToZero() {
        val state = ReferenceNavState(
            section = ReferenceCatalog.Section.Rules,
            pageId = "rule-intro",
            tocScrollY = -15f,
            contentScrollY = -50f,
        )

        val loaded = ReferenceNavCodec.decode(ReferenceNavCodec.encode(state))
        assertEquals(0f, loaded?.tocScrollY)
        assertEquals(0f, loaded?.contentScrollY)
    }

    @Test
    fun v1PayloadWithoutDrawerKeepsTheNavigation() {
        val loaded = ReferenceNavCodec.decode(
            encode(version = 1, pageId = "rule-intro", tocScrollY = 12f, contentScrollY = 34f),
        )

        // The upgrade must not throw an existing navigation away: the fields v1
        // knew survive, and the drawer the layout did not carry defaults closed.
        assertEquals(ReferenceCatalog.Section.Rules, loaded?.section)
        assertEquals("rule-intro", loaded?.pageId)
        assertEquals(12f, loaded?.tocScrollY)
        assertEquals(34f, loaded?.contentScrollY)
        assertEquals(false, loaded?.drawerOpen)
    }

    @Test
    fun v2RoundTripsTheDrawer() {
        val state = ReferenceNavState(
            section = ReferenceCatalog.Section.Scoring,
            pageId = "rule-intro",
            tocScrollY = 12f,
            contentScrollY = 34f,
            drawerOpen = true,
        )

        val loaded = ReferenceNavCodec.decode(ReferenceNavCodec.encode(state))
        assertEquals(true, loaded?.drawerOpen)
        assertEquals(ReferenceCatalog.Section.Scoring, loaded?.section)
        assertEquals(12f, loaded?.tocScrollY)
        assertEquals(34f, loaded?.contentScrollY)
    }

    @Test
    fun unknownSectionDecodesAsNull() {
        val text = encode(section = "UnknownSection", pageId = "some-page")
        assertNull(ReferenceNavCodec.decode(text))
    }

    @Test
    fun blankPageIdDecodesAsNull() {
        val text = encode(section = ReferenceCatalog.Section.Rules.name, pageId = "   ")
        assertNull(ReferenceNavCodec.decode(text))
    }

    @Test
    fun unknownOrMissingVersionDecodesAsNull() {
        assertNull(ReferenceNavCodec.decode(encode(version = ReferenceNavCodec.VERSION + 1)))
        assertNull(ReferenceNavCodec.decode(""))
        assertNull(ReferenceNavCodec.decode("junk"))
    }

    @Test
    fun truncatedAndTrailingFieldsDecodeAsNull() {
        val text = encode(pageId = "rule-intro")
        assertNull(ReferenceNavCodec.decode(text + "1:x"))
        assertNull(ReferenceNavCodec.decode(text.substringBeforeLast(':')))
    }

    private fun encode(
        version: Int = ReferenceNavCodec.VERSION,
        section: String = ReferenceCatalog.Section.Rules.name,
        pageId: String = "rule-intro",
        tocScrollY: Float = 0f,
        contentScrollY: Float = 0f,
        drawerOpen: Boolean = false,
    ): String {
        val writer = RecordWriter()
            .int(version)
            .field(section)
            .field(pageId)
            .float(tocScrollY)
            .float(contentScrollY)
        if (version >= 2) writer.boolean(drawerOpen)
        return writer.build().toString(Charsets.UTF_8)
    }
}
