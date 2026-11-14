package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceJsonTest {
    @Test
    fun heartbeatRequestBodyWaitingIncludesMinOtherHumansAndRounds() {
        assertEquals(
            """{"activity":"waiting","min_other_humans":1,"rounds":"either","chicken":"either"}""",
            PresenceJson.heartbeatRequestBody(PresenceActivity.Waiting),
        )
        assertEquals(
            """{"activity":"waiting","min_other_humans":2,"rounds":"one","chicken":"either"}""",
            PresenceJson.heartbeatRequestBody(
                PresenceActivity.Waiting,
                2,
                QueueRounds.One,
            ),
        )
        assertEquals(
            """{"activity":"waiting","min_other_humans":0,"rounds":"two","chicken":"allowed"}""",
            PresenceJson.heartbeatRequestBody(
                PresenceActivity.Waiting,
                0,
                QueueRounds.Two,
                QueueChicken.Allowed,
            ),
        )
    }

    @Test
    fun heartbeatRequestBodyIdleOmitsPrefs() {
        assertEquals(
            """{"activity":"idle"}""",
            PresenceJson.heartbeatRequestBody(PresenceActivity.Idle),
        )
        assertEquals(
            """{"activity":"idle"}""",
            PresenceJson.heartbeatRequestBody(
                PresenceActivity.Idle,
                2,
                QueueRounds.One,
                QueueChicken.Disallowed,
            ),
        )
        assertEquals(
            """{"activity":"playing"}""",
            PresenceJson.heartbeatRequestBody(PresenceActivity.Playing),
        )
    }

    @Test
    fun parseSnapshotReadsSnakeCaseSelfWithQueue() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 4,
              "playing": 0,
              "waiting": 2,
              "self": {
                "user_id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "activity": "waiting",
                "queue": {
                  "min_other_humans": 2,
                  "rounds": "either",
                  "chicken": "allowed"
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(4, parsed.online)
        assertEquals(0, parsed.playing)
        assertEquals(2, parsed.waiting)
        assertNotNull(parsed.self)
        assertEquals("c61b2b80-60f1-432a-bc91-236b2fbb1429", parsed.self.userId)
        assertEquals("1", parsed.self.displayName)
        assertEquals(PresenceActivity.Waiting, parsed.self.activity)

        val queue = parsed.self.queue
        assertNotNull(queue)
        assertEquals(2, queue.minOtherHumans)
        assertEquals(QueueRounds.Either, queue.rounds)
        assertEquals(QueueChicken.Allowed, queue.chicken)
        assertNull(parsed.self.table)
    }

    @Test
    fun parseSnapshotReadsSnakeCaseSelfWithTable() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 4,
              "playing": 4,
              "waiting": 0,
              "self": {
                "user_id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "activity": "playing",
                "table": {
                  "id": "3e0c1a7a-0b2a-4c5d-9e1f-7a8b9c0d1e2f",
                  "rounds": "one",
                  "allow_chicken": true,
                  "humans": 4,
                  "bots": 0
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(4, parsed.online)
        assertEquals(4, parsed.playing)
        assertEquals(0, parsed.waiting)
        assertNotNull(parsed.self)
        assertEquals(PresenceActivity.Playing, parsed.self.activity)

        val table = parsed.self.table
        assertNotNull(table)
        assertEquals("3e0c1a7a-0b2a-4c5d-9e1f-7a8b9c0d1e2f", table.id)
        assertEquals(QueueRounds.One, table.rounds)
        assertEquals(true, table.allowChicken)
        assertEquals(4, table.humans)
        assertEquals(0, table.bots)
        assertNull(parsed.self.queue)
    }

    @Test
    fun parseSnapshotAllowsMissingExtrasForOldServer() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 2,
              "playing": 0,
              "waiting": 2,
              "self": {
                "user_id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "activity": "waiting"
              }
            }
            """.trimIndent(),
        )
        assertEquals(2, parsed.online)
        assertNotNull(parsed.self)
        assertNull(parsed.self.queue)
        assertNull(parsed.self.table)
        assertNull(parsed.self.lastMatch)
        assertNull(parsed.self.matchmakingCooldown)
        assertFalse(parsed.needsStartingLevel)
    }

    @Test
    fun parseSnapshotReadsTheMatchmakingCooldown() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 1,
              "playing": 0,
              "waiting": 0,
              "self": {
                "user_id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "activity": "idle",
                "matchmaking_cooldown": {
                  "seconds_remaining": 42,
                  "reason": "forfeit"
                }
              }
            }
            """.trimIndent(),
        )
        val cooldown = parsed.self?.matchmakingCooldown
        assertNotNull(cooldown)
        assertEquals(42, cooldown.secondsRemaining)
        assertEquals("forfeit", cooldown.reason)
    }

    @Test
    fun parseSnapshotReadsLastMatch() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 1,
              "playing": 0,
              "waiting": 0,
              "self": {
                "user_id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "activity": "idle",
                "last_match": {
                  "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                  "ended_at": "2026-09-13T12:00:00Z",
                  "rounds": "one",
                  "allow_chicken": false,
                  "status": "finished",
                  "placement": 2,
                  "final_score": -8,
                  "old_rating": 1500.0,
                  "new_rating": 1492.0,
                  "delta": -8.0
                }
              }
            }
            """.trimIndent(),
        )
        val match = parsed.self?.lastMatch
        assertNotNull(match)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", match.id)
        assertEquals("2026-09-13T12:00:00Z", match.endedAt)
        assertEquals(MatchStatus.Finished, match.status)
        assertEquals(2, match.placement)
        assertEquals(-8, match.finalScore)
        assertEquals(1500.0, match.oldRating)
        assertEquals(1492.0, match.newRating)
        assertEquals(-8.0, match.delta)
        assertEquals("one", match.rounds)
        assertFalse(match.allowChicken)
    }

    @Test
    fun parseSnapshotReadsNeedsStartingLevel() {
        val gated = PresenceJson.parseSnapshot(
            """{"online":1,"playing":0,"waiting":0,"needs_starting_level":true,"self":{"user_id":"x","display_name":"1","activity":"idle"}}""",
        )
        assertTrue(gated.needsStartingLevel)
        assertEquals(PresenceActivity.Idle, gated.self?.activity)

        val omitted = PresenceJson.parseSnapshot(
            """{"online":1,"playing":0,"waiting":0}""",
        )
        assertFalse(omitted.needsStartingLevel)
    }

    @Test
    fun parseSnapshotAllowsMissingSelf() {
        val parsed = PresenceJson.parseSnapshot(
            """{"online":2,"playing":0,"waiting":2}""",
        )
        assertEquals(2, parsed.online)
        assertNull(parsed.self)
    }

    @Test
    fun parseSnapshotAllowsNullSelf() {
        val parsed = PresenceJson.parseSnapshot(
            """{"online":0,"playing":0,"waiting":0,"self":null}""",
        )
        assertNull(parsed.self)
    }

    @Test
    fun parseSnapshotRejectsPascalActivity() {
        assertFailsWith<IllegalArgumentException> {
            PresenceJson.parseSnapshot(
                """{"online":1,"playing":0,"waiting":1,"self":{"user_id":"x","display_name":"1","activity":"Waiting"}}""",
            )
        }
    }

    @Test
    fun parseSnapshotReadsServerShutdownWithMessage() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 4,
              "playing": 0,
              "waiting": 2,
              "server_shutdown": {
                "phase": "warning",
                "remaining_ms": 120000,
                "message": "Routine maintenance in 2 minutes"
              }
            }
            """.trimIndent(),
        )
        assertNotNull(parsed.serverShutdown)
        assertEquals("warning", parsed.serverShutdown.phase)
        assertEquals(120000L, parsed.serverShutdown.remainingMs)
        assertEquals("Routine maintenance in 2 minutes", parsed.serverShutdown.message)
    }

    @Test
    fun parseSnapshotReadsServerShutdownWithoutMessage() {
        val parsed = PresenceJson.parseSnapshot(
            """
            {
              "online": 4,
              "playing": 0,
              "waiting": 2,
              "server_shutdown": {
                "phase": "final",
                "remaining_ms": 25000
              }
            }
            """.trimIndent(),
        )
        assertNotNull(parsed.serverShutdown)
        assertEquals("final", parsed.serverShutdown.phase)
        assertEquals(25000L, parsed.serverShutdown.remainingMs)
        assertNull(parsed.serverShutdown.message)
    }

    @Test
    fun parseSnapshotAllowsMissingServerShutdown() {
        val parsed = PresenceJson.parseSnapshot(
            """{"online":1,"playing":0,"waiting":0}""",
        )
        assertNull(parsed.serverShutdown)
    }
}
