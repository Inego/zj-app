package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.online.OnlinePlayHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineIntegrationSmokeTest {

    private fun makeSampleSnapshot(mySeat: Int, hand: Int = 1): SeatSnapshotWire {
        val seatViews = (0..3).map { s ->
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == mySeat) listOf(0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 27, 28, 28) else null,
                drawnTile = if (s == mySeat) 30 else -1,
                hasDrawnTile = s == mySeat,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = hand,
            mySeat = mySeat,
            eastSeat = 0,
            liveWallSize = 70,
            currentPlayer = mySeat,
            decisionType = "ActOnHand",
            lastDiscarder = null,
            lastDiscarded = null,
            isPendingDiscard = false,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = null,
            winSource = null,
            isTerminal = false,
            scoreDeltas = null,
            seats = seatViews,
        )
    }

    @Test
    fun `smoke test full online match lifecycle with Elo ratings`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "table-smoke-1",
            token = "jwt-smoke",
            wsClient = fakeWs,
        )

        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)

        // 1. Connect with ticket
        val ticket = TableTicket(ticket = "test-ticket", tableId = "table-smoke-1", seat = 2)
        host.connectWithTicket(ticket)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(2, host.humanSeat)

        fakeWs.receiveServerText(
            """{"type":"table_joined","table_id":"table-smoke-1","seat":2,"rounds":"one","allow_chicken":false}"""
        )

        // 2. Initial Hand Deal
        val snapWire = makeSampleSnapshot(mySeat = 2, hand = 1)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snapWire))

        val view = host.tableView
        assertNotNull(view)
        assertEquals(2, view.mySeat)
        assertEquals(13, view.seats[2].concealedTiles?.size)
        assertNull(view.seats[0].concealedTiles)

        // 3. ActOnHand Decision
        fakeWs.receiveServerText(
            """
            {
                "type":"decision_prompt",
                "decision_id":201,
                "decision_type":"ActOnHand",
                "choices":[
                    {"kind":"Discard","payload":30},
                    {"kind":"Discard","payload":28}
                ],
                "deadline_ms":15000
            }
            """.trimIndent()
        )

        assertNotNull(host.myDecision)
        assertEquals(2, host.myDecision!!.choices.size)
        assertFalse(host.isWaitingForOthers)

        // Submit discard
        val submitted = host.submitChoice(Choice(ChoiceKind.Discard, 30))
        assertTrue(submitted)
        assertNull(host.myDecision)
        assertTrue(host.isWaitingForOthers)
        assertTrue(fakeWs.sentMessages.any { it.contains("submit_choice") && it.contains("\"payload\":30") })

        // Acknowledge discard
        fakeWs.receiveServerText("""{"type":"choice_acknowledged","request_id":"req_1"}""")

        // 4. Opponent Discard & Reaction Window
        fakeWs.receiveServerText(
            """
            {
                "type":"decision_prompt",
                "decision_id":202,
                "decision_type":"ReactToDiscard",
                "choices":[
                    {"kind":"Hu","payload":0},
                    {"kind":"Pass","payload":0}
                ],
                "deadline_ms":8000
            }
            """.trimIndent()
        )
        assertNotNull(host.myDecision)
        assertFalse(host.isWaitingForOthers)

        // Submit Hu
        val huSubmitted = host.submitChoice(Choice(ChoiceKind.Hu, 0))
        assertTrue(huSubmitted)
        assertTrue(host.isWaitingForOthers)

        // 5. Hand Settlement
        fakeWs.receiveServerText(
            """
            {
                "type":"hand_settlement",
                "hand_number":1,
                "hand_score":30,
                "winner_seat":2,
                "deltas":[-30,-30,90,-30],
                "running_totals":[-30,-30,90,-30]
            }
            """.trimIndent()
        )

        assertNotNull(host.handSettlement)
        assertEquals(2, host.handSettlement!!.winnerSeat)
        assertEquals(listOf(-30, -30, 90, -30), host.handSettlement!!.deltas)

        // Request next hand
        host.requestNextHand()
        assertTrue(fakeWs.sentMessages.any { it.contains("ready_for_next_hand") })

        // 6. Match Completion with Authoritative Elo Updates
        fakeWs.receiveServerText(
            """
            {
                "type":"game_finished",
                "running_totals":[-30,-30,90,-30],
                "rating_updates":[
                    {"seat":0,"user_id":"u0","old_rating":1500.0,"new_rating":1492.0,"delta":-8.0},
                    {"seat":1,"user_id":"u1","old_rating":1500.0,"new_rating":1492.0,"delta":-8.0},
                    {"seat":2,"user_id":"u2","old_rating":1500.0,"new_rating":1524.0,"delta":24.0},
                    {"seat":3,"user_id":"u3","old_rating":1500.0,"new_rating":1492.0,"delta":-8.0}
                ]
            }
            """.trimIndent()
        )

        assertTrue(host.isGameFinished)
        assertEquals(4, host.lastRatingUpdates.size)
        val winnerUpdate = host.lastRatingUpdates.find { it.seat == 2 }
        assertNotNull(winnerUpdate)
        assertEquals(24.0, winnerUpdate.delta, 1e-6)
        assertEquals(1524.0, winnerUpdate.newRating, 1e-6)

        val totalDelta = host.lastRatingUpdates.sumOf { it.delta }
        assertEquals(0.0, totalDelta, 1e-6)

        // 7. Leave game
        host.leave()
        assertTrue(fakeWs.sentMessages.any { it.contains("leave_game") })
    }

    @Test
    fun `smoke test abrupt connection drop and recovery with ticket reacquisition`() {
        val fakeWs1 = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "table-drop-1",
            token = "jwt-drop",
            wsClient = fakeWs1,
        )

        host.connectWithTicket(TableTicket(ticket = "initial-ticket", tableId = "table-drop-1", seat = 1))
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        // Simulate abrupt network drop on socket 1
        fakeWs1.close(1006, "Abnormal closure")
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)

        // Fresh ticket connection on socket 2
        val fakeWs2 = FakeWebSocketClient()
        val host2 = OnlinePlayHost(
            tableId = "table-drop-1",
            token = "jwt-drop",
            wsClient = fakeWs2,
        )
        host2.connectWithTicket(TableTicket(ticket = "fresh-ticket-2", tableId = "table-drop-1", seat = 1))
        assertEquals(ConnectionStatus.Connected, host2.connectionStatus)

        // Server re-sends TableJoined, current snapshot, and re-prompts active decision
        fakeWs2.receiveServerText("""{"type":"table_joined","table_id":"table-drop-1","seat":1,"rounds":"one","allow_chicken":true}""")
        fakeWs2.receiveServerText(
            """
            {
                "type":"decision_prompt",
                "decision_id":505,
                "decision_type":"ActOnHand",
                "choices":[{"kind":"Discard","payload":12}],
                "deadline_ms":7500
            }
            """.trimIndent()
        )

        assertNotNull(host2.myDecision)
        assertEquals(505L, host2.myDecision!!.decisionId.toLong())

        // Choice submitted across reconnected socket successfully
        assertTrue(host2.submitChoice(Choice(ChoiceKind.Discard, 12)))
        assertTrue(fakeWs2.sentMessages.any { it.contains("\"decision_id\":505") })
    }
}
