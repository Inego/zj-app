package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShadowRankedAttemptsClientTest {
    data class WireCall(val token: String, val limit: Int, val cursor: String?)

    private class FakeLister : ShadowRankedAttemptsWire {
        val calls = mutableListOf<WireCall>()
        private val pending = mutableListOf<(Result<ChallengeList>) -> Unit>()

        override fun list(token: String, limit: Int, cursor: String?, onDone: (Result<ChallengeList>) -> Unit) {
            calls += WireCall(token, limit, cursor)
            pending += onDone
        }

        fun complete(result: Result<ChallengeList>) {
            pending.removeFirst()(result)
        }
    }

    private fun entry(id: String) = ChallengeEntry(
        publicId = id,
        rounds = "one",
        allowChicken = false,
        createdAt = "2026-09-08T12:00:00Z",
        status = ChallengeStatus.Started,
        totalScore = null,
        startedAt = "2026-09-08T12:01:00Z",
        finishedAt = null,
        players = 1,
        ratingDelta = 0.0,
        scores = emptyList(),
    )

    @Test
    fun aSupersededResponseIsDroppedAndClearDropsInFlightWork() {
        val fake = FakeLister()
        val client = ShadowRankedAttemptsClient(api = fake)

        client.load("tok")
        client.load("tok")
        fake.complete(Result.success(ChallengeList(listOf(entry("old")))))
        assertTrue(client.entries.isEmpty(), "the superseded response is dropped")

        client.clear()
        fake.complete(Result.success(ChallengeList(listOf(entry("new")))))
        assertTrue(client.entries.isEmpty())
        assertFalse(client.loading)
        assertNull(client.error)
    }

    @Test
    fun aNullTokenClearsAndSendsNothing() {
        val fake = FakeLister()
        val client = ShadowRankedAttemptsClient(api = fake)

        client.load("tok")
        client.load(null)

        assertEquals(listOf(WireCall("tok", ShadowRankedAttemptsClient.PAGE_SIZE, null)), fake.calls)
        assertTrue(client.entries.isEmpty())
    }

    @Test
    fun unauthorizedInvokesTheCallbackAndStoresTheMessage() {
        val fake = FakeLister()
        val client = ShadowRankedAttemptsClient(api = fake)
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }

        client.load("tok")
        fake.complete(Result.failure(ShadowRankedWireException("no", ShadowRankedWireError.Unauthorized)))

        assertEquals(1, unauthorized)
        assertEquals("no", client.error)
        assertFalse(client.loading)
    }

    @Test
    fun paginationLoadInitialAndLoadMoreAppendsEntries() {
        val fake = FakeLister()
        val client = ShadowRankedAttemptsClient(api = fake)

        client.load("tok")
        assertEquals(listOf(WireCall("tok", ShadowRankedAttemptsClient.PAGE_SIZE, null)), fake.calls)
        assertTrue(client.loading)

        fake.complete(
            Result.success(
                ChallengeList(
                    challenges = listOf(entry("c1"), entry("c2")),
                    nextCursor = "cur_1",
                    hasMore = true,
                ),
            ),
        )

        assertFalse(client.loading)
        assertEquals(listOf("c1", "c2"), client.entries.map { it.publicId })
        assertEquals("cur_1", client.nextCursor)
        assertTrue(client.hasMore)

        // Load next page
        client.loadMore("tok")
        assertEquals(
            listOf(
                WireCall("tok", ShadowRankedAttemptsClient.PAGE_SIZE, null),
                WireCall("tok", ShadowRankedAttemptsClient.PAGE_SIZE, "cur_1"),
            ),
            fake.calls,
        )
        assertTrue(client.loadingMore)

        fake.complete(
            Result.success(
                ChallengeList(
                    challenges = listOf(entry("c3")),
                    nextCursor = null,
                    hasMore = false,
                ),
            ),
        )

        assertFalse(client.loadingMore)
        assertEquals(listOf("c1", "c2", "c3"), client.entries.map { it.publicId })
        assertNull(client.nextCursor)
        assertFalse(client.hasMore)

        // Further loadMore does nothing because hasMore is false
        client.loadMore("tok")
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun loadMoreFailureStoresErrorWithoutLosingExistingEntries() {
        val fake = FakeLister()
        val client = ShadowRankedAttemptsClient(api = fake)

        client.load("tok")
        fake.complete(
            Result.success(
                ChallengeList(
                    challenges = listOf(entry("c1")),
                    nextCursor = "cur_1",
                    hasMore = true,
                ),
            ),
        )

        client.loadMore("tok")
        fake.complete(Result.failure(ShadowRankedWireException("network timeout", ShadowRankedWireError.Timeout)))

        assertFalse(client.loadingMore)
        assertEquals("network timeout", client.loadMoreError)
        assertEquals(listOf("c1"), client.entries.map { it.publicId })
        assertTrue(client.hasMore)
    }
}
