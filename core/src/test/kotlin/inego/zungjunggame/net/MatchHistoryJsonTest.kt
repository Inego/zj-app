package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchHistoryJsonTest {
    @Test
    fun parseListReadsSummariesAndPaging() {
        val parsed = MatchHistoryJson.parseList(
            """
            {
              "matches": [
                {
                  "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "ended_at": "2026-09-08T12:00:00Z",
                  "rounds": "two",
                  "allow_chicken": true,
                  "status": "finished",
                  "placement": 2,
                  "final_score": 42,
                  "old_rating": 1470.2,
                  "new_rating": 1482.4,
                  "delta": 12.2
                },
                {
                  "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "ended_at": "2026-09-07T09:00:00Z",
                  "rounds": "one",
                  "allow_chicken": false,
                  "status": "left",
                  "placement": 4,
                  "final_score": -20,
                  "old_rating": null,
                  "new_rating": null,
                  "delta": null
                }
              ],
              "total": 7,
              "limit": 3,
              "offset": 0
            }
            """.trimIndent(),
        )
        assertEquals(7, parsed.total)
        assertEquals(3, parsed.limit)
        assertEquals(0, parsed.offset)
        assertEquals(2, parsed.matches.size)

        val first = parsed.matches[0]
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", first.id)
        assertEquals("two", first.rounds)
        assertTrue(first.allowChicken)
        assertEquals(MatchStatus.Finished, first.status)
        assertEquals(2, first.placement)
        assertEquals(42, first.finalScore)
        assertEquals(1470.2, first.oldRating)
        assertEquals(1482.4, first.newRating)
        assertEquals(12.2, first.delta)

        val second = parsed.matches[1]
        assertEquals(MatchStatus.Left, second.status)
        assertNull(second.oldRating)
        assertNull(second.newRating)
        assertNull(second.delta)
        assertEquals(-20, second.finalScore)
    }

    @Test
    fun parseListAllowsEmptyMatches() {
        val parsed = MatchHistoryJson.parseList(
            """{"matches":[],"total":0,"limit":20,"offset":0}""",
        )
        assertTrue(parsed.matches.isEmpty())
        assertEquals(0, parsed.total)
    }

    @Test
    fun unknownStatusDefaultsToFinished() {
        assertEquals(MatchStatus.Finished, MatchHistoryJson.parseStatus("finished"))
        assertEquals(MatchStatus.Left, MatchHistoryJson.parseStatus("left"))
        assertEquals(MatchStatus.Aborted, MatchHistoryJson.parseStatus("aborted"))
        assertEquals(MatchStatus.Finished, MatchHistoryJson.parseStatus("mystery"))
    }
}
