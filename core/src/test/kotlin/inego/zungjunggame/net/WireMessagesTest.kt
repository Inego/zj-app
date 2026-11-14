package inego.zungjunggame.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireMessagesTest {

    @Test
    fun `parse TableJoined`() {
        val json = """
            {
                "type": "table_joined",
                "table_id": "d120fb37-4d76-47b2-bd7f-eb6a7c36a6e7",
                "seat": 2,
                "rounds": "one",
                "allow_chicken": true
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertTrue(msg is ServerMessageWire.TableJoined)
        val joined = msg as ServerMessageWire.TableJoined
        assertEquals("d120fb37-4d76-47b2-bd7f-eb6a7c36a6e7", joined.tableId)
        assertEquals("", joined.gameId)
        assertEquals(2, joined.seat)
        assertEquals("one", joined.rounds)
        assertTrue(joined.allowChicken)
    }

    @Test
    fun `parse TableJoined with gameId`() {
        val json = """
            {
                "type": "table_joined",
                "table_id": "d120fb37-4d76-47b2-bd7f-eb6a7c36a6e7",
                "game_id": "a1b2c3d4",
                "seat": 2,
                "rounds": "one",
                "allow_chicken": true
            }
        """.trimIndent()

        val joined = WireJson.parseServerMessage(json) as ServerMessageWire.TableJoined
        assertEquals("a1b2c3d4", joined.gameId)
    }

    @Test
    fun `parse TableJoined player names and bot flags`() {
        val json = """
            {
                "type": "table_joined",
                "table_id": "table-1",
                "seat": 2,
                "rounds": "one",
                "allow_chicken": false,
                "players": [
                    {"seat": 0, "name": "Alice", "is_bot": false},
                    {"seat": 1, "name": "Bot 1", "is_bot": true},
                    {"seat": 2, "name": "Bob", "is_bot": false},
                    {"seat": 3, "name": "Bot 2", "is_bot": true}
                ]
            }
        """.trimIndent()

        val joined = WireJson.parseServerMessage(json) as ServerMessageWire.TableJoined
        assertEquals(4, joined.players.size)
        assertEquals("Alice", joined.players[0].name)
        assertFalse(joined.players[0].isBot)
        assertEquals("Bot 1", joined.players[1].name)
        assertTrue(joined.players[1].isBot)
        assertEquals(3, joined.players[3].seat)
        assertTrue(joined.scoreRows.isEmpty())
        assertEquals("Alice", joined.players[0].publicProfile().displayName)
    }

    @Test
    fun `parse TableJoined ignores private identity fields on players`() {
        val json = """
            {
                "type": "table_joined",
                "table_id": "table-1",
                "seat": 0,
                "rounds": "one",
                "allow_chicken": false,
                "players": [
                    {
                        "seat": 0,
                        "name": "Alice M.",
                        "is_bot": false,
                        "login": "alice",
                        "user_type": "plain",
                        "email": "alice@example.com"
                    }
                ]
            }
        """.trimIndent()

        val joined = WireJson.parseServerMessage(json) as ServerMessageWire.TableJoined
        assertEquals(1, joined.players.size)
        assertEquals("Alice M.", joined.players[0].name)
        assertEquals("Alice M.", joined.players[0].publicProfile().displayName)
        assertFalse(joined.players[0].isBot)
    }

    @Test
    fun `parse TableJoined score rows`() {
        val json = """
            {
                "type": "table_joined",
                "table_id": "table-1",
                "seat": 0,
                "rounds": "two",
                "allow_chicken": true,
                "score_rows": [
                    {
                        "hand_number": 1,
                        "hand_value": 5,
                        "winner_seat": 2,
                        "discarder_seat": 3,
                        "deltas": [-5, -5, 15, -5],
                        "running_totals": [-5, -5, 15, -5],
                        "patterns": [{"id": "pattern-1.1", "points": 5}]
                    },
                    {
                        "hand_number": 2,
                        "hand_value": 0,
                        "winner_seat": null,
                        "deltas": [0, 0, 0, 0],
                        "running_totals": [-5, -5, 15, -5]
                    }
                ]
            }
        """.trimIndent()

        val joined = WireJson.parseServerMessage(json) as ServerMessageWire.TableJoined
        assertEquals(2, joined.scoreRows.size)
        val win = joined.scoreRows[0]
        assertEquals(1, win.handNumber)
        assertEquals(5, win.handValue)
        assertEquals(2, win.winnerSeat)
        assertEquals(3, win.discarderSeat)
        assertEquals(listOf(-5, -5, 15, -5), win.deltas)
        assertEquals(listOf(-5, -5, 15, -5), win.runningTotals)
        assertEquals(1, win.patterns.size)
        assertEquals("pattern-1.1", win.patterns[0].id)
        val draw = joined.scoreRows[1]
        assertEquals(2, draw.handNumber)
        assertEquals(0, draw.handValue)
        assertNull(draw.winnerSeat)
        assertNull(draw.discarderSeat)
        assertTrue(draw.patterns.isEmpty())
    }

    @Test
    fun `parse SeatSnapshot with privacy invariants`() {
        val json = """
            {
                "type": "seat_snapshot",
                "hand_number": 1,
                "my_seat": 0,
                "east_seat": 0,
                "live_wall_size": 70,
                "current_player": 0,
                "decision_type": "ActOnHand",
                "last_discarder": null,
                "last_discarded": null,
                "is_pending_discard": false,
                "is_kong_rob_window": false,
                "hand_score": 0,
                "winner_seat": null,
                "win_source": null,
                "is_terminal": false,
                "score_deltas": null,
                "seats": [
                    {
                        "seat": 0,
                        "name": "Alice",
                        "is_bot": false,
                        "concealed_count": 13,
                        "concealed_tiles": [0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 27, 28, 28],
                        "drawn_tile": 30,
                        "has_drawn_tile": true,
                        "melds": [],
                        "river": [5, 14],
                        "seat_wind_tile": 27
                    },
                    {
                        "seat": 1,
                        "name": "Bot 1",
                        "is_bot": true,
                        "concealed_count": 13,
                        "concealed_tiles": null,
                        "drawn_tile": -1,
                        "has_drawn_tile": false,
                        "melds": [
                            {
                                "tiles": [
                                    {"tile_type": 1, "open": true, "rotated": true},
                                    {"tile_type": 2, "open": true, "rotated": false},
                                    {"tile_type": 3, "open": true, "rotated": false}
                                ]
                            }
                        ],
                        "river": [8],
                        "seat_wind_tile": 28
                    },
                    {
                        "seat": 2,
                        "concealed_count": 13,
                        "concealed_tiles": null,
                        "drawn_tile": -1,
                        "has_drawn_tile": false,
                        "melds": [],
                        "river": [],
                        "seat_wind_tile": 29
                    },
                    {
                        "seat": 3,
                        "concealed_count": 13,
                        "concealed_tiles": null,
                        "drawn_tile": -1,
                        "has_drawn_tile": false,
                        "melds": [],
                        "river": [],
                        "seat_wind_tile": 30
                    }
                ]
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertTrue(msg is ServerMessageWire.SeatSnapshotMsg)
        val snap = (msg as ServerMessageWire.SeatSnapshotMsg).snapshot
        assertEquals(1, snap.handNumber)
        assertEquals(0, snap.mySeat)
        assertEquals(0, snap.eastSeat)
        assertEquals(70, snap.liveWallSize)
        assertEquals("ActOnHand", snap.decisionType)
        assertFalse(snap.isTerminal)
        assertNull(snap.lastDiscarder)
        assertNull(snap.scoreDeltas)
        assertEquals(4, snap.seats.size)

        // Own seat reveals tiles and drawn tile
        val mySeatView = snap.seats[0]
        assertEquals("Alice", mySeatView.name)
        assertFalse(mySeatView.isBot)
        assertEquals(13, mySeatView.concealedCount)
        assertNotNull(mySeatView.concealedTiles)
        assertEquals(13, mySeatView.concealedTiles!!.size)
        assertEquals(30, mySeatView.drawnTile)
        assertTrue(mySeatView.hasDrawnTile)
        assertEquals(listOf(5, 14), mySeatView.river)

        // Opponent seat 1 has null concealedTiles, but reveals public meld
        val opp1View = snap.seats[1]
        assertEquals("Bot 1", opp1View.name)
        assertTrue(opp1View.isBot)
        assertNull("Opponent concealed tiles must be null", opp1View.concealedTiles)
        assertEquals(-1, opp1View.drawnTile)
        assertFalse(opp1View.hasDrawnTile)
        assertEquals(1, opp1View.melds.size)
        val meld = opp1View.melds[0]
        assertEquals(3, meld.tiles.size)
        assertEquals(1, meld.tiles[0].tileType)
        assertTrue(meld.tiles[0].open)
        assertTrue(meld.tiles[0].rotated)
        assertFalse(meld.tiles[1].rotated)
    }

    @Test
    fun `parse DecisionPrompt`() {
        val json = """
            {
                "type": "decision_prompt",
                "decision_id": 101,
                "decision_type": "ActOnHand",
                "choices": [
                    {"kind": "Discard", "payload": 10},
                    {"kind": "Discard", "payload": 11},
                    {"kind": "Hu", "payload": 40}
                ],
                "deadline_ms": 15000
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertTrue(msg is ServerMessageWire.DecisionPrompt)
        val prompt = msg as ServerMessageWire.DecisionPrompt
        assertEquals(101L, prompt.decisionId)
        assertEquals("ActOnHand", prompt.decisionType)
        assertEquals(3, prompt.choices.size)
        assertEquals("Discard", prompt.choices[0].kind)
        assertEquals(10, prompt.choices[0].payload)
        assertEquals("Hu", prompt.choices[2].kind)
        assertEquals(40, prompt.choices[2].payload)
        assertEquals(15000L, prompt.deadlineMs)
        assertNull(prompt.slackMs)
    }

    @Test
    fun `parse DecisionPrompt with slack`() {
        val msg = WireJson.parseServerMessage(
            """
            {
                "type": "decision_prompt",
                "decision_id": 101,
                "decision_type": "ActOnHand",
                "choices": [{"kind": "Discard", "payload": 10}],
                "deadline_ms": 37000,
                "slack_ms": 30000,
                "base_ms": 7000
            }
            """.trimIndent(),
        ) as ServerMessageWire.DecisionPrompt
        assertEquals(37000L, msg.deadlineMs)
        assertEquals(30000L, msg.slackMs)
        assertEquals(7000L, msg.baseMs)
    }

    @Test
    fun `parse DecisionTimeLeft`() {
        val msg = WireJson.parseServerMessage(
            """
            {
                "type": "decision_time_left",
                "decision_id": 101,
                "decision_type": "ReactToDiscard",
                "remaining_ms": 8123
            }
            """.trimIndent(),
        ) as ServerMessageWire.DecisionTimeLeft

        assertEquals(101L, msg.decisionId)
        assertEquals("ReactToDiscard", msg.decisionType)
        assertEquals(8123L, msg.remainingMs)
    }

    @Test
    fun `parse DecisionPrompt without deadline`() {
        val omitted = WireJson.parseServerMessage(
            """
            {
                "type": "decision_prompt",
                "decision_id": 102,
                "decision_type": "ActOnHand",
                "choices": []
            }
            """.trimIndent(),
        ) as ServerMessageWire.DecisionPrompt
        assertNull(omitted.deadlineMs)
        assertNull(omitted.slackMs)

        val explicitNull = WireJson.parseServerMessage(
            """
            {
                "type": "decision_prompt",
                "decision_id": 103,
                "decision_type": "ReactToDiscard",
                "choices": [],
                "deadline_ms": null
            }
            """.trimIndent(),
        ) as ServerMessageWire.DecisionPrompt
        assertNull(explicitNull.deadlineMs)
    }

    @Test
    fun `parse HandSettlement and GameFinished`() {
        val jsonSettlement = """
            {
                "type": "hand_settlement",
                "hand_number": 2,
                "hand_score": 30,
                "winner_seat": 1,
                "deltas": [-30, 90, -30, -30],
                "running_totals": [10, 150, -40, -120]
            }
        """.trimIndent()

        val msgSettlement = WireJson.parseServerMessage(jsonSettlement)
        assertTrue(msgSettlement is ServerMessageWire.HandSettlement)
        val settlement = msgSettlement as ServerMessageWire.HandSettlement
        assertEquals(2, settlement.handNumber)
        assertEquals(30, settlement.handScore)
        assertEquals(1, settlement.winnerSeat)
        assertEquals(listOf(-30, 90, -30, -30), settlement.deltas)
        assertEquals(listOf(10, 150, -40, -120), settlement.runningTotals)
        assertTrue(settlement.patterns.isEmpty())

        val jsonWithPatterns = """
            {
                "type": "hand_settlement",
                "hand_number": 1,
                "hand_score": 5,
                "winner_seat": 2,
                "deltas": [-5, -5, 15, -5],
                "running_totals": [-5, -5, 15, -5],
                "patterns": [
                    {"id": "pattern-1.1", "points": 5},
                    {"id": "pattern-3.1", "points": 10, "format_args": [1]}
                ]
            }
        """.trimIndent()
        val withPatterns = WireJson.parseServerMessage(jsonWithPatterns)
            as ServerMessageWire.HandSettlement
        assertEquals(2, withPatterns.patterns.size)
        assertEquals("pattern-1.1", withPatterns.patterns[0].id)
        assertEquals(5, withPatterns.patterns[0].points)
        assertTrue(withPatterns.patterns[0].formatArgs.isEmpty())
        assertEquals("pattern-3.1", withPatterns.patterns[1].id)
        assertEquals(10, withPatterns.patterns[1].points)
        assertEquals(listOf(1), withPatterns.patterns[1].formatArgs)

        val jsonPrompt = """
            {
                "type": "next_hand_prompt",
                "hand_number": 2,
                "deadline_ms": 10000
            }
        """.trimIndent()
        val prompt = WireJson.parseServerMessage(jsonPrompt) as ServerMessageWire.NextHandPrompt
        assertEquals(2, prompt.handNumber)
        assertEquals(10_000L, prompt.deadlineMs)
        assertFalse(prompt.ready)

        val jsonReadyPrompt = """
            {
                "type": "next_hand_prompt",
                "hand_number": 2,
                "ready": true
            }
        """.trimIndent()
        val readyPrompt = WireJson.parseServerMessage(jsonReadyPrompt) as ServerMessageWire.NextHandPrompt
        assertNull(readyPrompt.deadlineMs)
        assertTrue(readyPrompt.ready)

        val jsonTimeLeft = """
            {"type":"next_hand_time_left","remaining_ms":3500}
        """.trimIndent()
        val timeLeft = WireJson.parseServerMessage(jsonTimeLeft) as ServerMessageWire.NextHandTimeLeft
        assertEquals(3500L, timeLeft.remainingMs)

        val jsonFinished = """
            {
                "type": "game_finished",
                "running_totals": [10, 150, -40, -120]
            }
        """.trimIndent()
        val msgFinished = WireJson.parseServerMessage(jsonFinished)
        assertTrue(msgFinished is ServerMessageWire.GameFinished)
        val finished = msgFinished as ServerMessageWire.GameFinished
        assertEquals(listOf(10, 150, -40, -120), finished.runningTotals)
        assertTrue(finished.ratingUpdates.isEmpty())

        val jsonFinishedWithRatings = """
            {
                "type": "game_finished",
                "running_totals": [10, 150, -40, -120],
                "rating_updates": [
                    {
                        "seat": 0,
                        "user_id": "u0-uuid",
                        "old_rating": 1500.0,
                        "new_rating": 1508.5,
                        "delta": 8.5
                    },
                    {
                        "seat": 1,
                        "user_id": "u1-uuid",
                        "old_rating": 1500.0,
                        "new_rating": 1524.0,
                        "delta": 24.0
                    }
                ]
            }
        """.trimIndent()
        val msgFinishedRatings = WireJson.parseServerMessage(jsonFinishedWithRatings)
        assertTrue(msgFinishedRatings is ServerMessageWire.GameFinished)
        val finishedRatings = msgFinishedRatings as ServerMessageWire.GameFinished
        assertEquals(2, finishedRatings.ratingUpdates.size)
        assertEquals(0, finishedRatings.ratingUpdates[0].seat)
        assertEquals("u0-uuid", finishedRatings.ratingUpdates[0].userId)
        assertEquals(1500.0, finishedRatings.ratingUpdates[0].oldRating, 1e-6)
        assertEquals(1508.5, finishedRatings.ratingUpdates[0].newRating, 1e-6)
        assertEquals(8.5, finishedRatings.ratingUpdates[0].delta, 1e-6)
    }

    @Test
    fun `parse ChallengeScoreBreakdown with a nameless bot comparison`() {
        val json = """
            {
                "type": "challenge_score_breakdown",
                "public_id": "7K3H-M9PQ",
                "total_score": 48,
                "rating_before": 1214.0,
                "rating_after": 1219.33,
                "rating_delta": 0.01,
                "comparisons": [
                    {
                        "player_score": 48,
                        "opponent_score": 32,
                        "opponent_rating": 1200.0,
                        "s": 1.0,
                        "delta": 2.67
                    },
                    {
                        "opponent_name": "Maria",
                        "player_score": 48,
                        "opponent_score": 80,
                        "opponent_rating": 1204.0,
                        "s": 0.0,
                        "delta": -2.67
                    }
                ]
            }
        """.trimIndent()

        val message = WireJson.parseServerMessage(json)
        assertTrue(message is ServerMessageWire.ChallengeScoreBreakdown)
        val breakdown = message as ServerMessageWire.ChallengeScoreBreakdown
        assertEquals("7K3H-M9PQ", breakdown.publicId)
        assertEquals(48, breakdown.totalScore)
        assertEquals(1214.0, breakdown.ratingBefore, 1e-6)
        assertEquals(1219.33, breakdown.ratingAfter, 1e-6)
        assertEquals(0.01, breakdown.ratingDelta, 1e-6)
        assertEquals(2, breakdown.comparisons.size)
        // The bot is the comparison the server did not name.
        assertNull(breakdown.comparisons[0].opponentName)
        assertEquals(1200.0, breakdown.comparisons[0].opponentRating, 1e-6)
        assertEquals(32, breakdown.comparisons[0].opponentScore)
        assertEquals(1.0, breakdown.comparisons[0].s, 1e-6)
        assertEquals(2.67, breakdown.comparisons[0].delta, 1e-6)
        assertEquals("Maria", breakdown.comparisons[1].opponentName)
        assertEquals(0.0, breakdown.comparisons[1].s, 1e-6)
    }

    @Test
    fun `serialize ClientMessages`() {        val submit = ClientMessageWire.SubmitChoice(
            tableId = "tbl-123",
            handId = 1,
            decisionId = 42L,
            requestId = "req-99",
            choice = ChoiceWire(kind = "Discard", payload = 15)
        )
        val jsonSubmit = WireJson.serializeClientMessage(submit)
        assertEquals(
            """{"type":"submit_choice","table_id":"tbl-123","hand_id":1,"decision_id":42,"request_id":"req-99","choice":{"kind":"Discard","payload":15}}""",
            jsonSubmit
        )

        val submitWithOrigin = ClientMessageWire.SubmitChoice(
            tableId = "tbl-123",
            handId = 1,
            decisionId = 42L,
            requestId = "req-100",
            choice = ChoiceWire(kind = "Discard", payload = 15),
            slotIndex = 3,
            isDrawn = false,
        )
        val jsonSubmitWithOrigin = WireJson.serializeClientMessage(submitWithOrigin)
        assertEquals(
            """{"type":"submit_choice","table_id":"tbl-123","hand_id":1,"decision_id":42,"request_id":"req-100","slot_index":3,"is_drawn":false,"choice":{"kind":"Discard","payload":15}}""",
            jsonSubmitWithOrigin
        )

        val ready = ClientMessageWire.ReadyForNextHand("tbl-123")
        assertEquals(
            """{"type":"ready_for_next_hand","table_id":"tbl-123"}""",
            WireJson.serializeClientMessage(ready)
        )

        val leave = ClientMessageWire.LeaveGame("tbl-123")
        assertEquals(
            """{"type":"leave_game","table_id":"tbl-123"}""",
            WireJson.serializeClientMessage(leave)
        )

        val ping = ClientMessageWire.Ping
        assertEquals(
            """{"type":"ping"}""",
            WireJson.serializeClientMessage(ping)
        )

        val setAwayTrue = ClientMessageWire.SetAway(true)
        assertEquals(
            """{"type":"set_away","away":true}""",
            WireJson.serializeClientMessage(setAwayTrue)
        )

        val setAwayFalse = ClientMessageWire.SetAway(false)
        assertEquals(
            """{"type":"set_away","away":false}""",
            WireJson.serializeClientMessage(setAwayFalse)
        )
    }

    @Test
    fun `parse ServerShutdown with message`() {
        val json = """
            {
                "type": "server_shutdown",
                "phase": "warning",
                "remaining_ms": 180000,
                "message": "Routine maintenance in 3 minutes"
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertTrue(msg is ServerMessageWire.ServerShutdown)
        val shutdown = msg as ServerMessageWire.ServerShutdown
        assertEquals("warning", shutdown.phase)
        assertEquals(180000L, shutdown.remainingMs)
        assertEquals("Routine maintenance in 3 minutes", shutdown.message)
    }

    @Test
    fun `parse ServerShutdown without message`() {
        val json = """
            {
                "type": "server_shutdown",
                "phase": "final",
                "remaining_ms": 30000
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertTrue(msg is ServerMessageWire.ServerShutdown)
        val shutdown = msg as ServerMessageWire.ServerShutdown
        assertEquals("final", shutdown.phase)
        assertEquals(30000L, shutdown.remainingMs)
        assertNull(shutdown.message)
    }

    @Test
    fun `parse ServerShutdownWithdrawn`() {
        val json = """
            {
                "type": "server_shutdown_withdrawn"
            }
        """.trimIndent()

        val msg = WireJson.parseServerMessage(json)
        assertEquals(ServerMessageWire.ServerShutdownWithdrawn, msg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse unknown server message throws IllegalArgumentException`() {
        WireJson.parseServerMessage("""{"type":"unknown_future_message","data":123}""")
    }
}
