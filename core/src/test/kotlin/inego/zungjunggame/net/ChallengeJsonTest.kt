package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChallengeJsonTest {
    @Test
    fun startRequestBodyCarriesTheTwoRankedAxes() {
        assertEquals(
            """{"rounds":"one","allow_chicken":false}""",
            ChallengeJson.startRequestBody(ChallengeRounds.One, allowChicken = false),
        )
        assertEquals(
            """{"rounds":"two","allow_chicken":true}""",
            ChallengeJson.startRequestBody(ChallengeRounds.Two, allowChicken = true),
        )
    }

    @Test
    fun parseStartReadsTheAssignedTableAndTicket() {
        val start = ChallengeJson.parseStart(
            """{"public_id":"7K3H-M9PQ","table_id":"tbl-42","seat":0,"ticket":"abc"}""",
        )
        assertEquals(
            start,
            ChallengeStart(publicId = "7K3H-M9PQ", tableId = "tbl-42", seat = 0, ticket = "abc"),
        )
    }

    @Test
    fun parseStartRejectsAResponseWithoutATable() {
        assertFailsWith<IllegalArgumentException> {
            ChallengeJson.parseStart("""{"public_id":"7K3H-M9PQ","seat":0,"ticket":"abc"}""")
        }
    }

    @Test
    fun parseListReadsAttemptsAndFinishedScores() {
        val parsed = ChallengeJson.parseList(
            """
            {
              "challenges": [
                {
                  "public_id": "7K3H-M9PQ",
                  "rounds": "one",
                  "allow_chicken": false,
                  "created_at": "2026-09-08T12:00:00Z",
                  "status": "finished",
                  "total_score": 48,
                  "started_at": "2026-09-08T12:01:00Z",
                  "finished_at": "2026-09-08T12:20:00Z",
                  "players": 2,
                  "rating_delta": -5.333333333333333,
                  "scores": [
                    {"name": null, "score": 32, "is_bot": true, "is_you": false},
                    {"name": "Maria", "score": 80, "is_bot": false, "is_you": false},
                    {"name": "You", "score": 48, "is_bot": false, "is_you": true}
                  ]
                },
                {
                  "public_id": "4R8N-2WCV",
                  "rounds": "two",
                  "allow_chicken": true,
                  "created_at": "2026-09-07T12:00:00Z",
                  "status": "started",
                  "total_score": null,
                  "started_at": "2026-09-07T12:01:00Z",
                  "finished_at": null,
                  "players": 1,
                  "rating_delta": 0.0,
                  "scores": []
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, parsed.challenges.size)

        val finished = parsed.challenges[0]
        assertEquals("7K3H-M9PQ", finished.publicId)
        assertEquals("one", finished.rounds)
        assertEquals(ChallengeStatus.Finished, finished.status)
        assertEquals(48, finished.totalScore)
        assertEquals(2, finished.players)
        assertEquals(-5.333333333333333, finished.ratingDelta)
        assertEquals(3, finished.scores.size)
        assertTrue(finished.scores[0].isBot)
        assertNull(finished.scores[0].name)
        assertEquals(32, finished.scores[0].score)
        assertTrue(finished.scores[2].isYou)
        assertEquals("Maria", finished.scores[1].name)

        val started = parsed.challenges[1]
        assertEquals(ChallengeStatus.Started, started.status)
        assertNull(started.totalScore)
        assertNull(started.finishedAt)
        assertTrue(started.scores.isEmpty())
    }

    @Test
    fun parseListAllowsAnEmptyList() {
        val parsed = ChallengeJson.parseList("""{"challenges":[]}""")
        assertTrue(parsed.challenges.isEmpty())
    }

    @Test
    fun unknownStatusReadsAsStartedSoNoScoresLeak() {
        assertEquals(ChallengeStatus.Finished, ChallengeJson.parseStatus("finished"))
        assertEquals(ChallengeStatus.Abandoned, ChallengeJson.parseStatus("abandoned"))
        assertEquals(ChallengeStatus.Voided, ChallengeJson.parseStatus("voided"))
        assertEquals(ChallengeStatus.Started, ChallengeJson.parseStatus("mystery"))
    }
}
