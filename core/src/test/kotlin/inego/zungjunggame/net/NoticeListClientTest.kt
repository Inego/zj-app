package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticeListClientTest {
    private class FakeApi : NoticeLister, NoticeMarker {
        val pendingList = mutableListOf<(Result<NoticeList>) -> Unit>()
        val marked = mutableListOf<List<Long>>()

        override fun list(token: String, onDone: (Result<NoticeList>) -> Unit) {
            pendingList += onDone
        }

        override fun markSeen(token: String, ids: List<Long>, onDone: (Result<Unit>) -> Unit) {
            marked += ids
            onDone(Result.success(Unit))
        }

        fun complete(result: Result<NoticeList>) {
            pendingList.removeFirst()(result)
        }
    }

    private fun notice(id: Long, seen: Boolean = false, emailed: Boolean = false) = NoticeEntry(
        id = id,
        kind = NoticeKind.LaterPlay,
        templateKey = "notice.later_play",
        args = emptyList(),
        createdAt = "2026-09-08T12:00:00Z",
        seen = seen,
        emailed = emailed,
    )

    @Test
    fun refreshStoresTheListAndDropsAStaleResponse() {
        val api = FakeApi()
        val client = NoticeListClient(api = api, marker = api)

        client.refresh("tok")
        assertTrue(client.loading)
        client.refresh("tok")
        api.complete(Result.success(NoticeList(listOf(notice(1)))))
        assertTrue(client.notices.isEmpty(), "stale first response is dropped")

        api.complete(Result.success(NoticeList(listOf(notice(2)))))
        assertEquals(listOf(2L), client.notices.map { it.id })
        assertFalse(client.loading)
    }

    @Test
    fun aFreshUnseenNoticeBecomesThePopupAndIsClearedWhenAcknowledged() {
        val api = FakeApi()
        val client = NoticeListClient(api = api, marker = api)
        var changed = 0
        client.onNoticesChanged = { changed++ }

        client.refresh("tok")
        api.complete(Result.success(NoticeList(listOf(notice(2), notice(1, seen = true)))))
        assertEquals(1, changed)
        assertEquals(2L, client.popupNotice?.id)

        client.acknowledgePopup()
        assertNull(client.popupNotice, "an acknowledged popup is not shown again")
    }

    @Test
    fun anEmailedNoticeIsListedButDoesNotPop() {
        val api = FakeApi()
        val client = NoticeListClient(api = api, marker = api)

        client.refresh("tok")
        api.complete(
            Result.success(
                NoticeList(
                    listOf(notice(3, emailed = true), notice(2), notice(1, seen = true)),
                ),
            ),
        )

        assertEquals(
            listOf(3L, 2L, 1L),
            client.notices.map { it.id },
            "an emailed notice stays in the list",
        )
        assertEquals(2L, client.popupNotice?.id, "an unseen emailed notice is not popped")
    }

    @Test
    fun markSeenUpdatesTheRowsAndAcknowledgedNoticesAreNotRepresented() {
        val api = FakeApi()
        val client = NoticeListClient(api = api, marker = api)
        client.refresh("tok")
        api.complete(Result.success(NoticeList(listOf(notice(4), notice(5)))))
        assertEquals(4L, client.popupNotice?.id)

        client.markSeen("tok", listOf(4L))
        assertTrue(client.notices.first { it.id == 4L }.seen)
        assertFalse(client.notices.first { it.id == 5L }.seen)
        assertEquals(listOf(4L), api.marked.single())

        // Dismissing each popup walks the inbox, then leaves nothing to show.
        client.acknowledgePopup()
        assertEquals(5L, client.popupNotice?.id)
        client.acknowledgePopup()
        assertNull(client.popupNotice)

        // A refresh racing the server call cannot re-present an acknowledged notice.
        client.refresh("tok")
        api.complete(Result.success(NoticeList(listOf(notice(4), notice(5)))))
        assertNull(client.popupNotice)
    }

    @Test
    fun unauthorizedFailureInvokesTheCallbackAndClearDropsEverything() {
        val api = FakeApi()
        val client = NoticeListClient(api = api, marker = api)
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }

        client.refresh("tok")
        api.complete(Result.failure(NoticeException("no", unauthorized = true)))
        assertEquals(1, unauthorized)

        client.refresh("tok")
        api.complete(Result.success(NoticeList(listOf(notice(1)))))
        client.clear()
        assertTrue(client.notices.isEmpty())
        assertNull(client.popupNotice)
    }
}
