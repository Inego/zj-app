package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthJsonTest {
    @Test
    fun everyRequestBodyBuilderEscapesQuotes() {
        val cases = listOf(
            Triple(
                "refresh",
                AuthJson.refreshRequestBody("""a"b\c"""),
                """{"refresh_token":"a\"b\\c"}""",
            ),
            Triple(
                "googleChallenge",
                AuthJson.googleChallengeRequestBody("""id"x""", """ab\cd"""),
                """{"client_id":"id\"x","verifier_hash":"ab\\cd"}""",
            ),
            Triple(
                "googleExchange",
                AuthJson.googleExchangeRequestBody("""ver"ifier""", """tok\en"""),
                """{"verifier":"ver\"ifier","id_token":"tok\\en"}""",
            ),
            Triple(
                "patchMe",
                AuthJson.patchMeRequestBody("""Alice "M.""""),
                """{"display_name":"Alice \"M.\""}""",
            ),
            Triple(
                "startingLevel",
                AuthJson.startingLevelRequestBody("""no"vice"""),
                """{"level":"no\"vice"}""",
            ),
        )
        for ((builder, actual, expected) in cases) {
            assertEquals(expected, actual, "$builder request body must escape quotes and backslashes")
        }
    }

    @Test
    fun parseLoginReadsUserPayload() {
        val parsed = AuthJson.parseLogin(
            """
            {
              "token": "jwt-token",
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                  "display_name": "1",
                  "statuses": ["no_timer"]
              }
            }
            """.trimIndent(),
        )
        assertEquals("jwt-token", parsed.token)
        assertEquals("1", parsed.user.displayName)
        assertEquals(listOf("no_timer"), parsed.user.statuses)
        assertEquals(null, parsed.user.eloRating)
        assertEquals(null, parsed.user.aliasCustomizedAt)
        assertEquals("1", parsed.user.identityLabel())
    }

    @Test
    fun parseMeReadsAliasCustomizedAt() {
        val parsed = AuthJson.parseMe(
            """
            {
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "user-k7m4p9q2",
                "alias_customized_at": "2026-09-10T12:00:00Z"
              }
            }
            """.trimIndent(),
        )
        assertEquals("2026-09-10T12:00:00Z", parsed.aliasCustomizedAt)
        assertFalse(parsed.needsFirstLoginAlias())
    }

    @Test
    fun googleUserWithoutCustomizedAliasNeedsFirstLoginPrompt() {
        val parsed = AuthJson.parseMe(
            """
            {
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "user-k7m4p9q2",
                "alias_customized_at": null
              }
            }
            """.trimIndent(),
        )
        assertEquals(null, parsed.aliasCustomizedAt)
        assertTrue(parsed.needsFirstLoginAlias())
    }

    @Test
    fun parseMeReadsUserWithoutToken() {
        val parsed = AuthJson.parseMe(
            """
            {
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "elo_rating": 1516.0
              }
            }
            """.trimIndent(),
        )
        assertEquals(1516.0, parsed.eloRating)
    }

    @Test
    fun parseLoginReadsEloRating() {
        val parsed = AuthJson.parseLogin(
            """
            {
              "token": "jwt-token",
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1",
                "elo_rating": 1482.4
              }
            }
            """.trimIndent(),
        )
        assertEquals(1482.4, parsed.user.eloRating)
    }

    @Test
    fun identityLabelUsesDisplayNameOnly() {
        val matching = AuthUser(id = "x", displayName = "2")
        assertEquals("2", matching.identityLabel())
        val mixedCase = AuthUser(id = "x", displayName = "yanis")
        assertEquals("yanis", mixedCase.identityLabel())
        val different = AuthUser(id = "x", displayName = "Alice M.")
        assertEquals("Alice M.", different.identityLabel())
        val padded = AuthUser(id = "x", displayName = "  Alice  ")
        assertEquals("Alice", padded.identityLabel())
    }

    @Test
    fun parseLoginRejectsMissingToken() {
        assertFailsWith<IllegalArgumentException> {
            AuthJson.parseLogin("""{"user":{"id":"x","display_name":"1"}}""")
        }
    }

    @Test
    fun parseLoginPrefersAccessTokenAndReadsRefresh() {
        val parsed = AuthJson.parseLogin(
            """
            {
              "access_token": "access-jwt",
              "refresh_token": "refresh-opaque",
              "token": "legacy-alias",
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1"
              }
            }
            """.trimIndent(),
        )
        assertEquals("access-jwt", parsed.token)
        assertEquals("refresh-opaque", parsed.refreshToken)
    }

    @Test
    fun parseLoginFallsBackToTokenAlias() {
        val parsed = AuthJson.parseLogin(
            """
            {
              "token": "jwt-token",
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "1"
              }
            }
            """.trimIndent(),
        )
        assertEquals("jwt-token", parsed.token)
        assertEquals(null, parsed.refreshToken)
    }

    @Test
    fun parseGoogleChallengeReadsNonceAndTtl() {
        val parsed = AuthJson.parseGoogleChallenge(
            """{"nonce":"abc123","expires_in":600}""",
        )
        assertEquals("abc123", parsed.nonce)
        assertEquals(600L, parsed.expiresInSeconds)
    }

    @Test
    fun parseGoogleChallengeRejectsMissingFields() {
        assertFailsWith<IllegalArgumentException> {
            AuthJson.parseGoogleChallenge("""{"expires_in":600}""")
        }
        assertFailsWith<IllegalArgumentException> {
            AuthJson.parseGoogleChallenge("""{"nonce":"abc"}""")
        }
    }

    @Test
    fun parseErrorMessageReadsServerBody() {
        assertEquals(
            "Invalid session",
            AuthJson.parseErrorMessage(
                """{"error":"Invalid session","code":"AUTH_FAILED"}""",
                "fallback",
            ),
        )
        assertEquals("fallback", AuthJson.parseErrorMessage(null, "fallback"))
        assertEquals("fallback", AuthJson.parseErrorMessage("not-json", "fallback"))
    }

    @Test
    fun parseMeReadsStartingLevelAndTimestamp() {
        val parsed = AuthJson.parseMe(
            """
            {
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "Alice",
                "elo_rating": 800.0,
                "starting_level": "novice",
                "starting_level_set_at": "2026-09-11T20:00:00Z"
              }
            }
            """.trimIndent(),
        )
        assertEquals("novice", parsed.startingLevel)
        assertEquals("2026-09-11T20:00:00Z", parsed.startingLevelSetAt)
        assertEquals(800.0, parsed.eloRating)
        assertFalse(parsed.needsStartingLevel())

        val unset = AuthJson.parseMe(
            """
            {
              "user": {
                "id": "c61b2b80-60f1-432a-bc91-236b2fbb1429",
                "display_name": "Bob"
              }
            }
            """.trimIndent(),
        )
        assertEquals(null, unset.startingLevel)
        assertEquals(null, unset.startingLevelSetAt)
        assertTrue(unset.needsStartingLevel())
    }
}
