package inego.zungjunggame.app

import inego.zungjunggame.render.reference.ReferenceCatalog
import kotlin.test.Test
import kotlin.test.assertNull

class ReferenceSectionBookmarksTest {

    @Test
    fun firstVisitHasNoBookmark() {
        val bookmarks = ReferenceSectionBookmarks()
        assertNull(bookmarks.restore(ReferenceCatalog.Section.Scoring) { true })
    }

    @Test
    fun missingPageIsIgnoredSoCallerCanOpenDefault() {
        val bookmarks = ReferenceSectionBookmarks()
        bookmarks.snapshot(ReferenceCatalog.Section.Rules, "gone", 50f, 0f)
        assertNull(bookmarks.restore(ReferenceCatalog.Section.Rules) { false })
    }

    @Test
    fun emptyPageIdDoesNotRecordABookmark() {
        val bookmarks = ReferenceSectionBookmarks()
        bookmarks.snapshot(ReferenceCatalog.Section.Rules, pageId = "", tocScrollY = 9f, contentScrollY = 1f)
        assertNull(bookmarks.restore(ReferenceCatalog.Section.Rules) { true })
    }
}
