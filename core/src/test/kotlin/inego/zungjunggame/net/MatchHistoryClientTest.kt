package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchHistoryClientTest {
    private class FakeSender : MatchHistorySender {
        data class Call(val token: String, val limit: Int, val offset: Int)

        val calls = mutableListOf<Call>()
        private val pending = mutableListOf<(Result<MatchList>) -> Unit>()

        override fun list(
            token: String,
            limit: Int,
            offset: Int,
            onDone: (Result<MatchList>) -> Unit,
        ) {
            calls += Call(token, limit, offset)
            pending += onDone
        }

        fun complete(result: Result<MatchList>) {
            val cb = pending.removeFirst()
            cb(result)
        }
    }

    private fun summary(id: String) = MatchSummary(
        id = id,
        endedAt = "2026-09-08T12:00:00Z",
        rounds = "two",
        allowChicken = false,
        status = MatchStatus.Finished,
        placement = 1,
        finalScore = 10,
        oldRating = 1500.0,
        newRating = 1510.0,
        delta = 10.0,
    )

    @Test
    fun loadMoreAppendsUntilExhausted() {
        val fake = FakeSender()
        val client = MatchHistoryClient(api = fake)
        client.loadFirstPage("tok")
        fake.complete(
            Result.success(
                MatchList(
                    matches = listOf(summary("a"), summary("b")),
                    total = 3,
                    limit = 2,
                    offset = 0,
                ),
            ),
        )
        assertTrue(client.hasMore)

        client.loadMore("tok")
        assertEquals(2, fake.calls.last().offset)
        fake.complete(
            Result.success(
                MatchList(listOf(summary("c")), total = 3, limit = 2, offset = 2),
            ),
        )
        assertEquals(listOf("a", "b", "c"), client.all.map { it.id })
        assertFalse(client.hasMore)

        client.loadMore("tok")
        assertEquals(2, fake.calls.size, "no extra request when exhausted")
    }

    @Test
    fun reloadAllIfLoadedOnlyAfterViewAllOpened() {
        val fake = FakeSender()
        val client = MatchHistoryClient(api = fake)
        client.reloadAllIfLoaded("tok")
        assertTrue(fake.calls.isEmpty())

        client.loadFirstPage("tok")
        fake.complete(
            Result.success(
                MatchList(listOf(summary("a")), total = 1, limit = 20, offset = 0),
            ),
        )
        client.reloadAllIfLoaded("tok")
        assertEquals(2, fake.calls.size)
        assertEquals(0, fake.calls.last().offset)
    }
}
