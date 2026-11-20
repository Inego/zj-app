package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.PatternIds
import inego.zungjunggame.net.ChoiceWire
import inego.zungjunggame.net.ChallengeComparisonWire
import inego.zungjunggame.net.HandScoreRowWire
import inego.zungjunggame.net.PresentationEventWire
import inego.zungjunggame.net.ScoreComponentWire
import inego.zungjunggame.net.MeldTileWire
import inego.zungjunggame.net.MeldWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.net.TableTicketException
import inego.zungjunggame.net.TablePlayerWire
import inego.zungjunggame.platform.WebSocketClient
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.table.DiscardOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeWebSocketClient : WebSocketClient {
    var connectedUrl: String? = null
    var listener: WebSocketClient.Listener? = null
    val sentMessages = mutableListOf<String>()
    var closedWithCode: Int? = null
    private var _connected = false

    override val isConnected: Boolean get() = _connected

    override fun connect(url: String, headers: Map<String, String>, listener: WebSocketClient.Listener) {
        this.connectedUrl = url
        this.listener = listener
        this._connected = true
        listener.onOpen()
    }

    override fun send(text: String): Boolean {
        sentMessages.add(text)
        return true
    }

    override fun close(code: Int, reason: String) {
        closedWithCode = code
        _connected = false
        listener?.onClose(code, reason)
    }

    fun receiveServerText(json: String) {
        listener?.onMessage(json)
    }

    fun receiveServerMessage(msg: ServerMessageWire) {
        // Can directly invoke or serialize
        val json = when (msg) {
            is ServerMessageWire.TableJoined -> {
                val scoresJson = if (msg.scoreRows.isEmpty()) {
                    ""
                } else {
                    val body = msg.scoreRows.joinToString(",") { row ->
                        val winner = row.winnerSeat?.toString() ?: "null"
                        val discarder = row.discarderSeat?.toString() ?: "null"
                        val patterns = if (row.patterns.isEmpty()) {
                            ""
                        } else {
                            val pbody = row.patterns.joinToString(",") { p ->
                                "{\"id\":\"${p.id}\",\"points\":${p.points}}"
                            }
                            ",\"patterns\":[$pbody]"
                        }
                        "{\"hand_number\":${row.handNumber},\"hand_value\":${row.handValue}," +
                            "\"winner_seat\":$winner,\"discarder_seat\":$discarder," +
                            "\"deltas\":[${row.deltas.joinToString(",")}]," +
                            "\"running_totals\":[${row.runningTotals.joinToString(",")}]$patterns}"
                    }
                    ",\"score_rows\":[$body]"
                }
                "{\"type\":\"table_joined\",\"table_id\":\"${msg.tableId}\",\"seat\":${msg.seat}," +
                    "\"rounds\":\"${msg.rounds}\",\"allow_chicken\":${msg.allowChicken}$scoresJson}"
            }
            is ServerMessageWire.ChoiceAcknowledged ->
                "{\"type\":\"choice_acknowledged\",\"request_id\":\"${msg.requestId}\"}"
            is ServerMessageWire.ChoiceRejected ->
                "{\"type\":\"choice_rejected\",\"request_id\":\"${msg.requestId}\",\"reason\":\"${msg.reason}\"}"
            is ServerMessageWire.HandSettlement -> {
                val patternsJson = if (msg.patterns.isEmpty()) {
                    ""
                } else {
                    val body = msg.patterns.joinToString(",") { p ->
                        val args = if (p.formatArgs.isEmpty()) {
                            ""
                        } else {
                            ",\"format_args\":[${p.formatArgs.joinToString(",")}]"
                        }
                        "{\"id\":\"${p.id}\",\"points\":${p.points}$args}"
                    }
                    ",\"patterns\":[$body]"
                }
                "{\"type\":\"hand_settlement\",\"hand_number\":${msg.handNumber},\"hand_score\":${msg.handScore},\"winner_seat\":${msg.winnerSeat ?: "null"},\"deltas\":[${msg.deltas.joinToString(",")}],\"running_totals\":[${msg.runningTotals.joinToString(",")}]$patternsJson}"
            }
            is ServerMessageWire.GameFinished ->
                "{\"type\":\"game_finished\",\"running_totals\":[${msg.runningTotals.joinToString(",")}]}"
            is ServerMessageWire.TableAborted ->
                "{\"type\":\"table_aborted\",\"reason\":\"${msg.reason}\"}"
            ServerMessageWire.Pong -> "{\"type\":\"pong\"}"
            else -> throw UnsupportedOperationException("Use receiveRaw for snapshots/prompts")
        }
        receiveServerText(json)
    }
}

class OnlinePlayHostTest {

    private fun makeSampleSnapshot(
        mySeat: Int = 0,
        decisionType: String = "ActOnHand",
        lastDiscarder: Int? = null,
        lastDiscarded: Int? = null,
        isPendingDiscard: Boolean = false,
        winnerSeat: Int? = null,
        winSource: Int? = null,
        isTerminal: Boolean = false,
    ): SeatSnapshotWire {
        val seatViews = (0..3).map { s ->
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == mySeat) listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9) else null,
                drawnTile = if (s == mySeat) 10 else -1,
                hasDrawnTile = s == mySeat,
                melds = listOf(
                    MeldWire(
                        listOf(
                            MeldTileWire(tileType = 11, open = true, rotated = false),
                            MeldTileWire(tileType = 11, open = true, rotated = false),
                            MeldTileWire(tileType = 11, open = true, rotated = true),
                        ),
                    ),
                ),
                river = listOf(15, 16, 17),
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = 1,
            mySeat = mySeat,
            eastSeat = 0,
            liveWallSize = 70,
            currentPlayer = mySeat,
            decisionType = decisionType,
            lastDiscarder = lastDiscarder,
            lastDiscarded = lastDiscarded,
            isPendingDiscard = isPendingDiscard,
            isKongRobWindow = false,
            handScore = 0,
            winnerSeat = winnerSeat,
            winSource = winSource,
            isTerminal = isTerminal,
            scoreDeltas = null,
            seats = seatViews,
        )
    }

    /**
     * A terminal snapshot whose `win_source` is absent because the hand ended on a
     * **self-draw**, while `last_discarder` still names the previous discarder — the
     * wire shape protocol.rs produces for a self-draw.
     */
    private fun makeSelfDrawnTerminalSnapshot(
        mySeat: Int = 0,
        winnerSeat: Int = 2,
        staleDiscarder: Int = 1,
    ): SeatSnapshotWire = makeSampleSnapshot(
        mySeat = mySeat,
        decisionType = "ActOnHand",
        lastDiscarder = staleDiscarder,
        lastDiscarded = 15,
        isPendingDiscard = false,
        winnerSeat = winnerSeat,
        winSource = null,
        isTerminal = true,
    )

    @Test
    fun `test connect and initial state projection with privacy preservation`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )

        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)

        // Connect with ticket
        val ticket = TableTicket(ticket = "ticket-xyz", tableId = "tbl-123", seat = 0)
        host.connectWithTicket(ticket)

        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(0, host.humanSeat)
        assertEquals(true, fakeWs.connectedUrl?.contains("ticket=ticket-xyz"))

        // Receive TableJoined
        fakeWs.receiveServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "EastOnly",
                allowChicken = false,
            ),
        )
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(false, host.allowChickenHands)

        // Receive SeatSnapshot
        val snap = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap))

        val view = host.tableView
        assertNotNull(view)
        assertEquals(0, view.mySeat)
        assertEquals(70, view.liveWallSize)
        assertEquals(0, view.currentPlayer)
        assertFalse(view.isHumanDiscardTurn, "No decision prompted yet")

        // Check privacy: my seat has concealed tiles, opponents do not
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9), view.seats[0].concealedTiles)
        assertEquals(10, view.seats[0].drawnTile)
        assertTrue(view.seats[0].hasDrawnTile)

        for (s in 1..3) {
            assertNull(view.seats[s].concealedTiles, "Opponent seat $s concealed tiles must NOT be leaked")
            assertEquals(-1, view.seats[s].drawnTile)
            assertEquals(13, view.seats[s].concealedCount)
            assertEquals(3, view.seats[s].river.size)
            assertEquals(1, view.seats[s].melds.size)
        }
    }

    @Test
    fun firstSeatSnapshotFiresOnMatchLiveOnce() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        var liveCount = 0
        host.onMatchLive = { liveCount += 1 }

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "EastOnly",
                allowChicken = false,
            ),
        )
        assertEquals(0, liveCount, "TableJoined is still Reserved")

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        assertEquals(1, liveCount)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        assertEquals(1, liveCount, "later snapshots must not re-fire")
    }

    @Test
    fun `test decision prompt enables input and submission clears decision`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))

        assertNull(host.myDecision)
        assertFalse(host.isWaitingForOthers)

        // Receive DecisionPrompt for discard
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 1001L,
            decisionType = "ActOnHand",
            choices = listOf(
                ChoiceWire("Discard", 0),
                ChoiceWire("Discard", 1),
                ChoiceWire("Discard", 10),
            ),
            deadlineMs = 20_000L,
        )
        host.handleServerMessage(prompt)

        assertNotNull(host.myDecision)
        assertEquals("1001", host.myDecision?.decisionId)
        assertEquals(true, host.myDecision?.isDiscard)
        assertNotEquals(host.myDecision?.isClaim, true)
        assertEquals(true, host.tableView?.isHumanDiscardTurn)
        assertNotEquals(host.tableView?.isWaitingForOthers, true)

        // Try submitting an illegal discard (tile 99 not among choices)
        val illegalSubmitted = host.submitDiscard(99)
        assertFalse(illegalSubmitted)
        assertEquals(0, fakeWs.sentMessages.size)
        assertNotNull(host.myDecision)

        // Submit legal discard
        val submitted = host.submitDiscard(10)
        assertTrue(submitted)

        // After submit: myDecision must be cleared, isWaitingForOthers = true
        assertNull(host.myDecision)
        assertTrue(host.isWaitingForOthers)
        assertNotEquals(host.tableView?.isHumanDiscardTurn, true)
        assertEquals(true, host.tableView?.isWaitingForOthers)

        // Verify sent wire message
        assertEquals(1, fakeWs.sentMessages.size)
        val sentJson = fakeWs.sentMessages[0]
        assertTrue(sentJson.contains("\"type\":\"submit_choice\""))
        assertTrue(sentJson.contains("\"decision_id\":1001"))
        assertTrue(sentJson.contains("\"payload\":10"))
        assertTrue(sentJson.contains("\"kind\":\"Discard\""))

        // Duplicate submission attempts while waiting must be rejected
        assertFalse(host.submitDiscard(10))
        assertFalse(host.submitChoice(Choice.pass()))
        assertEquals(1, fakeWs.sentMessages.size)

        // Server acknowledges choice
        host.handleServerMessage(ServerMessageWire.ChoiceAcknowledged("req_1"))
        assertNull(host.lastRejectedReason)
    }

    @Test
    fun `null deadline does not create or advance a client clock`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost("tbl-123", "jwt-abc", fakeWs)
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        host.handleServerMessage(
            ServerMessageWire.DecisionPrompt(
                decisionId = 77L,
                decisionType = "ActOnHand",
                choices = listOf(ChoiceWire("Discard", 10)),
                deadlineMs = null,
            ),
        )

        assertNull(host.decisionTimeLeftMs)
        assertNull(host.decisionSlackMs)
        host.advance(120f)
        assertNull(host.decisionTimeLeftMs)
        assertNull(host.decisionSlackMs)

        host.handleServerMessage(
            ServerMessageWire.DecisionTimeLeft(
                decisionId = 77L,
                decisionType = "ActOnHand",
                remainingMs = 1L,
            ),
        )
        assertNull(host.decisionTimeLeftMs)
        assertNull(host.decisionSlackMs)
    }

    @Test
    fun `slack bank is stored from the prompt and not ticked`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost("tbl-123", "jwt-abc", fakeWs)
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        host.handleServerMessage(
            ServerMessageWire.DecisionPrompt(
                decisionId = 88L,
                decisionType = "ActOnHand",
                choices = listOf(ChoiceWire("Discard", 10)),
                deadlineMs = 37_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
            ),
        )

        assertEquals(37_000L, host.decisionTimeLeftMs)
        assertEquals(30_000L, host.decisionSlackMs)
        assertEquals(7_000L, host.decisionBaseMs)
        host.advance(2f)
        assertEquals(37_000L, host.decisionTimeLeftMs, "full think remaining is presentation-grace; do not tick")
        host.handleServerMessage(
            ServerMessageWire.DecisionTimeLeft(
                decisionId = 88L,
                decisionType = "ActOnHand",
                remainingMs = 36_000L,
            ),
        )
        assertEquals(36_000L, host.decisionTimeLeftMs)
        host.advance(1f)
        assertEquals(35_000L, host.decisionTimeLeftMs)
        assertEquals(30_000L, host.decisionSlackMs)
    }

    @Test
    fun `prompt schedule drives the clock without correction packets`() {
        val fakeWs = FakeWebSocketClient()
        var nowNs = 1_000_000_000L
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            nowNanos = { nowNs },
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        host.handleServerMessage(
            ServerMessageWire.DecisionPrompt(
                decisionId = 91L,
                decisionType = "ActOnHand",
                choices = listOf(ChoiceWire("Discard", 10)),
                deadlineMs = 37_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
                msUntilThinkStarts = 3_000L,
                msUntilTimeout = 40_000L,
            ),
        )

        assertTrue(host.decisionPreparing)
        assertEquals(40_000L, host.decisionTimeLeftMs)

        nowNs += 2_999_000_000L
        host.advance(0.016f)
        assertTrue(host.decisionPreparing)

        nowNs += 1_000_000L
        host.advance(0.016f)
        assertFalse(host.decisionPreparing)
        assertEquals(37_000L, host.decisionTimeLeftMs)

        nowNs += 1_000_000_000L
        host.advance(0.016f)
        assertEquals(36_000L, host.decisionTimeLeftMs)
    }

    @Test
    fun `first frame at four seconds already shows the running clock`() {
        val fakeWs = FakeWebSocketClient()
        var nowNs = 1_000_000_000L
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            nowNanos = { nowNs },
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0)))
        host.handleServerMessage(
            ServerMessageWire.DecisionPrompt(
                decisionId = 92L,
                decisionType = "ActOnHand",
                choices = listOf(ChoiceWire("Discard", 10)),
                deadlineMs = 37_000L,
                slackMs = 30_000L,
                baseMs = 7_000L,
                msUntilThinkStarts = 3_000L,
                msUntilTimeout = 40_000L,
            ),
        )

        // No advance between receipt and four seconds: the frame clock must not
        // be what carries the countdown.
        nowNs += 4_000_000_000L
        host.advance(0.016f)
        assertFalse(host.decisionPreparing)
        assertEquals(36_000L, host.decisionTimeLeftMs)
    }

    @Test
    fun `pending discard is shown on an otherwise empty river`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 1))

        val emptyRivers = makeSampleSnapshot(1).seats.map { it.copy(river = emptyList()) }
        val snap = makeSampleSnapshot(1).copy(
            currentPlayer = 0,
            decisionType = "ReactToDiscard",
            lastDiscarder = 3,
            lastDiscarded = 9, // 1-dot
            isPendingDiscard = true,
            seats = emptyRivers,
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap))

        val view = host.tableView!!
        assertEquals(listOf(9), view.seats[3].river)
        assertEquals(emptyList(), view.seats[0].river)
        assertEquals(emptyList(), view.seats[1].river)
        assertEquals(emptyList(), view.seats[2].river)
        assertTrue(view.isPendingDiscard)
        assertEquals(3, view.lastDiscarder)
        assertEquals(9, view.lastDiscarded)
    }

    @Test
    fun `pending discard already in snapshot river is not duplicated`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 1))

        val seats = makeSampleSnapshot(1).seats.map { sw ->
            if (sw.seat == 3) sw.copy(river = listOf(9)) else sw.copy(river = emptyList())
        }
        val snap = makeSampleSnapshot(1).copy(
            currentPlayer = 0,
            decisionType = "ReactToDiscard",
            lastDiscarder = 3,
            lastDiscarded = 9,
            isPendingDiscard = true,
            seats = seats,
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap))

        assertEquals(listOf(9), host.tableView!!.seats[3].river)
    }

    @Test
    fun `test claim reaction prompt and claim panel choices`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 1))

        var snap = makeSampleSnapshot(1)
        snap = snap.copy(
            currentPlayer = 0,
            decisionType = "ReactToDiscard",
            lastDiscarder = 0,
            lastDiscarded = 5,
            isPendingDiscard = true,
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap))

        val discarderRiver = host.tableView!!.seats[0].river
        assertEquals(listOf(15, 16, 17, 5), discarderRiver)
        assertEquals(5, host.tableView!!.lastDiscarded)
        assertTrue(host.tableView!!.isPendingDiscard)

        // Seat 1 gets prompt to Chow or Pass
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 2002L,
            decisionType = "ReactToDiscard",
            choices = listOf(
                ChoiceWire("Pass", 0),
                ChoiceWire("Chow", 3), // chow first tile 3 (3-4-5)
            ),
            deadlineMs = 10_000L,
        )
        host.handleServerMessage(prompt)

        assertNotNull(host.myDecision)
        assertEquals(true, host.myDecision?.isClaim)
        assertNotEquals(host.myDecision?.isDiscard, true)
        assertEquals(true, host.tableView?.isHumanClaimTurn)

        // Submit Chow choice
        val chowChoice = Choice.chow(3)
        assertTrue(host.submitChoice(chowChoice))

        assertNull(host.myDecision)
        assertTrue(host.isWaitingForOthers)
        assertNotEquals(host.tableView?.isHumanClaimTurn, true)

        assertEquals(1, fakeWs.sentMessages.size)
        val sentJson = fakeWs.sentMessages[0]
        assertTrue(sentJson.contains("\"kind\":\"Chow\""))
        assertTrue(sentJson.contains("\"payload\":3"))
    }

    @Test
    fun `submitChoice refused while disconnected`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 1))
        var snap = makeSampleSnapshot(1)
        snap = snap.copy(
            currentPlayer = 0,
            decisionType = "ReactToDiscard",
            lastDiscarder = 0,
            lastDiscarded = 5,
            isPendingDiscard = true,
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap))
        host.handleServerMessage(
            ServerMessageWire.DecisionPrompt(
                decisionId = 2002L,
                decisionType = "ReactToDiscard",
                choices = listOf(ChoiceWire("Pass", 0), ChoiceWire("Chow", 3)),
                deadlineMs = 10_000L,
            ),
        )
        fakeWs.close(1006, "lost")
        fakeWs.sentMessages.clear()

        assertFalse(host.canSubmitActions())
        assertFalse(host.submitChoice(Choice.chow(3)))
        assertTrue(fakeWs.sentMessages.isEmpty())
    }

    @Test
    fun `test hand settlement and next hand request`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))

        val settlement = ServerMessageWire.HandSettlement(
            handNumber = 1,
            handScore = 15,
            winnerSeat = 0,
            deltas = listOf(45, -15, -15, -15),
            runningTotals = listOf(45, -15, -15, -15),
        )
        host.handleServerMessage(settlement)

        assertEquals(settlement, host.handSettlement)
        assertNull(host.myDecision)
        assertFalse(host.isWaitingForOthers)
        assertFalse(host.readyForNextHand)
        assertTrue(host.settlementDetails()!!.components.isEmpty())

        // Request next hand: keep the score overlay until the next live snapshot.
        host.requestNextHand()
        assertEquals(settlement, host.handSettlement)
        assertTrue(host.readyForNextHand)
        assertNull(host.nextHandTimeLeftMs)
        assertEquals(1, fakeWs.sentMessages.size)
        assertTrue(fakeWs.sentMessages[0].contains("\"type\":\"ready_for_next_hand\""))

        host.requestNextHand()
        assertEquals(1, fakeWs.sentMessages.size)
    }

    @Test
    fun `next hand prompt starts a timed ready window`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 15,
                winnerSeat = 0,
                deltas = listOf(45, -15, -15, -15),
                runningTotals = listOf(45, -15, -15, -15),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.NextHandPrompt(handNumber = 1, deadlineMs = 10_000L, ready = false),
        )
        assertFalse(host.readyForNextHand)
        assertEquals(10_000L, host.nextHandTimeLeftMs)
        assertNotNull(host.handSettlement)

        host.advance(1f)
        assertEquals(9_000L, host.nextHandTimeLeftMs)
        assertFalse(host.readyForNextHand)

        host.handleServerMessage(ServerMessageWire.NextHandTimeLeft(remainingMs = 4_000L))
        assertEquals(4_000L, host.nextHandTimeLeftMs)
    }

    @Test
    fun `no_timer next hand prompt has no countdown`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 1))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(0, 0, 0, 0),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.NextHandPrompt(handNumber = 1, deadlineMs = null, ready = false),
        )
        assertNull(host.nextHandTimeLeftMs)
        host.advance(12f)
        assertFalse(host.readyForNextHand)
        assertTrue(fakeWs.sentMessages.none { it.contains("ready_for_next_hand") })
    }

    @Test
    fun `next hand countdown auto-sends ready`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 15,
                winnerSeat = 0,
                deltas = listOf(45, -15, -15, -15),
                runningTotals = listOf(45, -15, -15, -15),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.NextHandPrompt(handNumber = 1, deadlineMs = 500L, ready = false),
        )
        host.advance(1f)
        assertTrue(host.readyForNextHand)
        assertNull(host.nextHandTimeLeftMs)
        assertEquals(1, fakeWs.sentMessages.count { it.contains("ready_for_next_hand") })
        assertEquals(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 15,
                winnerSeat = 0,
                deltas = listOf(45, -15, -15, -15),
                runningTotals = listOf(45, -15, -15, -15),
            ),
            host.handSettlement,
        )
    }

    @Test
    fun `live snapshot after ready dismisses the previous hand overlay`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 15,
                winnerSeat = 0,
                deltas = listOf(45, -15, -15, -15),
                runningTotals = listOf(45, -15, -15, -15),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.NextHandPrompt(handNumber = 1, deadlineMs = null, ready = false),
        )
        host.requestNextHand()
        assertNotNull(host.handSettlement)
        assertTrue(host.readyForNextHand)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(0).copy(
            handNumber = 2,
            isTerminal = false,
        )))
        assertNull(host.handSettlement)
        assertFalse(host.readyForNextHand)
        assertNull(host.nextHandTimeLeftMs)
    }

    @Test
    fun `test names bots and draw settlement are retained`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost("tbl-123", "jwt-abc", fakeWs)
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 2))
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 2,
                rounds = "one",
                allowChicken = false,
                players = listOf(
                    TablePlayerWire(0, "Alice", false),
                    TablePlayerWire(1, "Bot 1", true),
                    TablePlayerWire(2, "Bob", false),
                    TablePlayerWire(3, "Bot 2", true),
                ),
            ),
        )

        assertEquals(arrayOf("Alice", "Bot 1", "Bob", "Bot 2").toList(), host.playerNames.toList())
        assertEquals(listOf(false, true, false, true), host.isBot.toList())

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(0, 0, 0, 0),
            ),
        )

        assertEquals(1, host.scores.handCount)
        assertTrue(host.scores.rows().single().isDraw)
        assertEquals(listOf(0, 0, 0, 0), host.scores.runningTotals().toList())
        assertTrue(host.settlementDetails()!!.components.isEmpty())
    }

    @Test
    fun `table joined restores score rows and later settlement does not duplicate`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        val row1 = HandScoreRowWire(
            handNumber = 1,
            handValue = 5,
            winnerSeat = 2,
            discarderSeat = 3,
            deltas = listOf(-5, -5, 15, -5),
            runningTotals = listOf(-5, -5, 15, -5),
            patterns = listOf(ScoreComponentWire(PatternIds.ALL_SEQUENCES, 5)),
        )
        val row2 = HandScoreRowWire(
            handNumber = 2,
            handValue = 0,
            winnerSeat = null,
            discarderSeat = null,
            deltas = listOf(0, 0, 0, 0),
            runningTotals = listOf(-5, -5, 15, -5),
        )
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 2,
                rounds = "two",
                allowChicken = true,
                scoreRows = listOf(row1, row2),
            ),
        )
        assertEquals(2, host.scores.handCount)
        assertEquals(listOf(-5, -5, 15, -5), host.scores.runningTotals().toList())
        val restored = host.scores.rows()[0]
        assertEquals(1, restored.handNumber)
        assertEquals(5, restored.handValue)
        assertEquals(2, restored.winnerSeat)
        assertEquals(3, restored.discarderSeat)
        assertEquals(listOf(-5, -5, 15, -5), restored.deltas.toList())
        assertTrue(host.scores.rows()[1].isDraw)

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 2,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(-5, -5, 15, -5),
            ),
        )
        assertEquals(2, host.scores.handCount)

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 3,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(25, -15, 5, -15),
            ),
        )
        assertEquals(3, host.scores.handCount)
        assertEquals(listOf(25, -15, 5, -15), host.scores.runningTotals().toList())
    }

    @Test
    fun `table joined restore replaces locally accumulated scores`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 5,
                winnerSeat = 0,
                deltas = listOf(15, -5, -5, -5),
                runningTotals = listOf(15, -5, -5, -5),
            ),
        )
        assertEquals(1, host.scores.handCount)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "one",
                allowChicken = true,
                scoreRows = listOf(
                    HandScoreRowWire(
                        handNumber = 1,
                        handValue = 5,
                        winnerSeat = 0,
                        discarderSeat = null,
                        deltas = listOf(15, -5, -5, -5),
                        runningTotals = listOf(15, -5, -5, -5),
                    ),
                    HandScoreRowWire(
                        handNumber = 2,
                        handValue = 12,
                        winnerSeat = 1,
                        discarderSeat = 0,
                        deltas = listOf(-12, 36, -12, -12),
                        runningTotals = listOf(3, 31, -17, -17),
                    ),
                ),
            ),
        )
        assertEquals(2, host.scores.handCount)
        assertEquals(listOf(3, 31, -17, -17), host.scores.runningTotals().toList())
        assertEquals(0, host.scores.rows()[1].discarderSeat)
    }

    @Test
    fun `self drawn settlement does not blame the previous discarder`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(makeSelfDrawnTerminalSnapshot(winnerSeat = 2, staleDiscarder = 1)),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 45,
                winnerSeat = 2,
                deltas = listOf(-45, -45, 135, -45),
                runningTotals = listOf(-45, -45, 135, -45),
            ),
        )

        // A self-draw has no discarder. last_discarder is still 1 from the discard before
        // the winner drew, and borrowing it titled the hand "wins on discard from 1".
        assertEquals(-1, host.scores.rows().last().discarderSeat)
    }

    @Test
    fun `discard settlement keeps the discarder the snapshot reported`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(
                    decisionType = "ReactToDiscard",
                    lastDiscarder = 1,
                    lastDiscarded = 15,
                    isPendingDiscard = true,
                    winnerSeat = 2,
                    winSource = 1,
                    isTerminal = true,
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 45,
                winnerSeat = 2,
                deltas = listOf(-45, -45, 135, -45),
                runningTotals = listOf(-45, -45, 135, -45),
            ),
        )

        assertEquals(1, host.scores.rows().last().discarderSeat)
    }

    @Test
    fun `pending discard without a win source still names the discarder`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(
                    decisionType = "ReactToDiscard",
                    lastDiscarder = 3,
                    lastDiscarded = 15,
                    isPendingDiscard = true,
                    winnerSeat = 2,
                    winSource = null,
                    isTerminal = true,
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 45,
                winnerSeat = 2,
                deltas = listOf(-45, -45, 135, -45),
                runningTotals = listOf(-45, -45, 135, -45),
            ),
        )

        // The fallback is for a discard win whose snapshot omitted win_source; it must
        // stay, or such a hand would lose its discarder entirely.
        assertEquals(3, host.scores.rows().last().discarderSeat)
    }

    @Test
    fun `settlement details include pattern lines for the overlay`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost("tbl-123", "jwt-abc", fakeWs)
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 2))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 5,
                winnerSeat = 2,
                deltas = listOf(-5, -5, 15, -5),
                runningTotals = listOf(-5, -5, 15, -5),
                patterns = listOf(ScoreComponentWire(PatternIds.ALL_SEQUENCES, 5)),
            ),
        )
        val details = host.settlementDetails()
        assertNotNull(details)
        assertEquals(5, details.totalScore)
        assertEquals(1, details.components.size)
        assertEquals(PatternIds.ALL_SEQUENCES, details.components[0].id)
        assertEquals(5, details.components[0].points)
    }

    @Test
    fun `disconnect closes socket without leave_game or callback`() {
        var leaveCallbackCalled = false
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            onLeaveCallback = { leaveCallbackCalled = true },
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        host.disconnect()

        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
        assertFalse(leaveCallbackCalled)
        assertTrue(fakeWs.sentMessages.none { it.contains("\"type\":\"leave_game\"") })
        assertEquals(1000, fakeWs.closedWithCode)
        host.advance(1f)
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
        assertFalse(fakeWs.isConnected)
    }

    @Test
    fun `test leave game sends leave message and closes socket`() {
        var leaveCallbackCalled = false
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            onLeaveCallback = { leaveCallbackCalled = true },
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        host.leave()

        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
        assertTrue(leaveCallbackCalled)
        assertEquals(1, fakeWs.sentMessages.size)
        assertTrue(fakeWs.sentMessages[0].contains("\"type\":\"leave_game\""))
        assertEquals(1000, fakeWs.closedWithCode)
    }

    @Test
    fun `test leave is reentrant when callback calls leave again`() {
        val fakeWs = FakeWebSocketClient()
        lateinit var host: OnlinePlayHost
        var callbackCount = 0
        host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            onLeaveCallback = {
                callbackCount++
                host.leave()
            },
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))

        host.leave()

        assertEquals(1, callbackCount)
        assertEquals(1, fakeWs.sentMessages.size)
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
    }

    @Test
    fun `socket drop auto-reconnects with a fresh ticket`() {
        val fakeWs = FakeWebSocketClient()
        var tickets = 0
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                tickets += 1
                onDone(Result.success(TableTicket("ticket-$tickets", "tbl-123", seat = 0)))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        fakeWs.close(1006, "Abnormal closure")
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
        assertEquals(0, tickets)

        host.advance(0.016f)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(1, tickets)
        assertEquals(true, fakeWs.connectedUrl?.contains("ticket=ticket-1"))
    }

    @Test
    fun `onAppResumed reconnects a dropped socket immediately`() {
        val fakeWs = FakeWebSocketClient()
        var tickets = 0
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                tickets += 1
                onDone(Result.success(TableTicket("ticket-$tickets", "tbl-123", seat = 0)))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        fakeWs.close(1006, "Abnormal closure")
        assertEquals(0, tickets)

        host.onAppResumed()
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(1, tickets)
    }

    @Test
    fun `onAppResumed while connected fast forwards presentation sequencer`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                onDone(Result.success(TableTicket("ticket-1", "tbl-123", seat = 0)))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))

        val snap0 = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))

        val snap1 = makeSampleSnapshot(mySeat = 0).copy(
            currentPlayer = 1,
            events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15)),
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap1))

        assertEquals(1, host.sequencer.queueSize)

        host.onAppResumed()

        assertEquals(0, host.sequencer.queueSize)
        assertEquals(1, host.sequencer.displayedViewState?.currentPlayer)
        assertTrue(host.sequencer.isCaughtUp)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
    }

    @Test
    fun `onAppPaused sends SetAway true when connected and marks wasAway`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        assertFalse(host.wasAway)

        host.onAppPaused()
        assertTrue(host.wasAway)
        val lastMsg = fakeWs.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertTrue(lastMsg.contains("\"type\":\"set_away\""))
        assertTrue(lastMsg.contains("\"away\":true"))
    }

    @Test
    fun `resumeFromAway sends SetAway false and fast-forwards sequencer`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        host.onAppPaused()
        assertTrue(host.wasAway)

        val snap0 = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))
        val snap1 = makeSampleSnapshot(mySeat = 0).copy(
            currentPlayer = 1,
            events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15)),
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap1))
        assertEquals(1, host.sequencer.queueSize)

        host.resumeFromAway()
        assertFalse(host.wasAway)
        assertEquals(0, host.sequencer.queueSize)
        assertTrue(host.sequencer.isCaughtUp)

        val lastMsg = fakeWs.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertTrue(lastMsg.contains("\"type\":\"set_away\""))
        assertTrue(lastMsg.contains("\"away\":false"))
    }

    @Test
    fun `reconnect with wasAway true resends SetAway true on TableJoined`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        host.onAppPaused()
        assertTrue(host.wasAway)
        fakeWs.sentMessages.clear()

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "FourRounds",
                allowChicken = true,
                players = emptyList(),
                scoreRows = emptyList(),
            )
        )
        val lastMsg = fakeWs.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertTrue(lastMsg.contains("\"type\":\"set_away\""))
        assertTrue(lastMsg.contains("\"away\":true"))
    }

    @Test
    fun `host created with initialAway true sends SetAway true on TableJoined`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            initialAway = true,
        )
        assertTrue(host.wasAway)
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        fakeWs.sentMessages.clear()

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "FourRounds",
                allowChicken = true,
                players = emptyList(),
                scoreRows = emptyList(),
            ),
        )
        val lastMsg = fakeWs.sentMessages.lastOrNull()
        assertNotNull(lastMsg)
        assertTrue(lastMsg.contains("\"type\":\"set_away\""))
        assertTrue(lastMsg.contains("\"away\":true"))
    }

    @Test
    fun `unreachable ticket without snapshot pauses recovery without leaving`() {
        val fakeWs = FakeWebSocketClient()
        var tickets = 0
        var left = false
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            onLeaveCallback = { left = true },
            requestTicket = { _, _, onDone ->
                tickets += 1
                onDone(Result.failure(Exception("connection refused")))
            },
        )
        host.connect()
        assertEquals(1, tickets)
        assertNull(host.tableView)
        assertFalse(left)

        repeat(20) { host.advance(1f) }

        assertFalse(left)
        assertEquals(OnlinePlayHost.MAX_UNREACHABLE_FAILURES, tickets)
        assertTrue(host.reconnectRequired)
        assertEquals(ConnectionStatus.ReconnectRequired, host.connectionStatus)
    }

    @Test
    fun `connect ticket failure still reconnects before first snapshot`() {
        val fakeWs = FakeWebSocketClient()
        var tickets = 0
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                tickets += 1
                if (tickets == 1) {
                    onDone(Result.failure(Exception("connection refused")))
                } else {
                    onDone(Result.success(TableTicket("ticket-$tickets", "tbl-123", seat = 0)))
                }
            },
        )
        host.connect()
        assertEquals(1, tickets)
        assertNull(host.tableView)
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)

        host.advance(0.016f)
        assertEquals(2, tickets)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(true, fakeWs.connectedUrl?.contains("ticket=ticket-2"))
    }

    @Test
    fun `reconnect ticket uses live access token after refresh`() {
        val fakeWs = FakeWebSocketClient()
        var currentToken = "jwt-old"
        val usedTokens = mutableListOf<String>()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-old",
            wsClient = fakeWs,
            requestTicket = { _, tok, onDone ->
                usedTokens += tok
                onDone(Result.success(TableTicket("ticket-${usedTokens.size}", "tbl-123", seat = 0)))
            },
            resolveToken = { currentToken },
        )
        host.connect()
        assertEquals(listOf("jwt-old"), usedTokens)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        currentToken = "jwt-new"
        fakeWs.close(1006, "Abnormal closure")
        host.advance(0.016f)

        assertEquals(listOf("jwt-old", "jwt-new"), usedTokens)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertEquals(true, fakeWs.connectedUrl?.contains("ticket=ticket-2"))
    }

    @Test
    fun `gone table ticket pauses recovery without leaving`() {
        val fakeWs = FakeWebSocketClient()
        var left = false
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            onLeaveCallback = { left = true },
            requestTicket = { _, _, onDone ->
                onDone(Result.failure(TableTicketException(404, "not found")))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        fakeWs.close(1006, "Abnormal closure")
        host.advance(0.016f)
        assertFalse(left)
        assertTrue(host.reconnectRequired)
        assertEquals(ConnectionStatus.ReconnectRequired, host.connectionStatus)
    }

    @Test
    fun `test table aborted updates state`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))
        assertFalse(host.isTableAborted)

        host.handleServerMessage(ServerMessageWire.TableAborted("Player disconnected"))

        assertTrue(host.isTableAborted)
        assertEquals("Player disconnected", host.abortReason)
        assertNull(host.myDecision)
    }

    @Test
    fun `reconnect empty snapshot snaps instead of animating a stale diff`() {
        val fakeWs = FakeWebSocketClient()
        var tickets = 0
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                tickets += 1
                onDone(Result.success(TableTicket("ticket-$tickets", "tbl-123", seat = 0)))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))
        val snap0 = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                snap0.copy(
                    currentPlayer = 1,
                    events = listOf(PresentationEventWire.Discard(1, 22)),
                ),
            ),
        )
        assertEquals(1, host.sequencer.queueSize)

        fakeWs.close(1006, "Abnormal closure")
        host.advance(0.016f)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        val resync = snap0.copy(currentPlayer = 2, liveWallSize = 60, events = emptyList())
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(resync))
        assertEquals(0, host.sequencer.queueSize)
        assertTrue(host.sequencer.isCaughtUp)
        assertEquals(2, host.tableView?.currentPlayer)
        assertEquals(60, host.tableView?.liveWallSize)
    }

    @Test
    fun `test sequencer integrates with snapshots and latches prompt after playback`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))

        // Initial snapshot
        val snap0 = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))
        assertEquals(0, host.sequencer.queueSize)

        // Snapshot with bot discard event
        val snap1 = snap0.copy(
            currentPlayer = 1,
            lastDiscarder = 1,
            lastDiscarded = 22,
            events = listOf(PresentationEventWire.Discard(seat = 1, tile = 22)),
        )
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap1))
        assertEquals(1, host.sequencer.queueSize)

        // Decision prompt arrives
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 42L,
            decisionType = "ReactToDiscard",
            choices = listOf(ChoiceWire(ChoiceKind.Pass.name, 0)),
            deadlineMs = 10000L,
        )
        host.handleServerMessage(prompt)

        // Authoritative decision is stored, but displayed myDecision is held until sequencer plays through
        assertNotNull(host.authoritativeDecision)
        assertNull(host.myDecision)

        // Clocks must not drain presentation
        host.advance(0.4f)
        assertEquals(10_000L, host.decisionTimeLeftMs)
        assertEquals(1, host.sequencer.queueSize)
        assertNull(host.myDecision)

        // One idle apply, then the post-apply pause latches the prompt
        assertTrue(host.tryPresent(boardIdle = true))
        assertEquals(0, host.sequencer.queueSize)
        assertNull(host.myDecision)
        assertFalse(host.tryPresent(boardIdle = true))
        assertNotNull(host.myDecision)
        assertEquals("42", host.myDecision?.decisionId)
    }

    @Test
    fun `game finished does not skip remaining presentation`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
        )
        host.connectWithTicket(TableTicket("ticket-xyz", "tbl-123", seat = 0))

        val snap0 = makeSampleSnapshot(mySeat = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                snap0.copy(events = listOf(PresentationEventWire.Discard(1, 10))),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                snap0.copy(
                    isTerminal = true,
                    winnerSeat = 0,
                    events = listOf(PresentationEventWire.HandEnd(winnerSeat = 0, winSource = 1)),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 15,
                winnerSeat = 0,
                deltas = listOf(45, -15, -15, -15),
                runningTotals = listOf(45, -15, -15, -15),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(45, -15, -15, -15),
            ),
        )

        assertTrue(host.isGameFinished)
        assertEquals(2, host.sequencer.queueSize)
        assertFalse(host.sequencer.isCaughtUp)
        assertEquals(-1, host.tableView?.winnerSeat)
        assertNotEquals(host.tableView?.isTerminal, true)

        assertTrue(host.tryPresent(boardIdle = true))
        assertFalse(host.tryPresent(boardIdle = true))
        assertTrue(host.tryPresent(boardIdle = true))
        assertFalse(host.tryPresent(boardIdle = true))
        assertTrue(host.sequencer.isCaughtUp)
        assertEquals(0, host.tableView?.winnerSeat)
        assertEquals(true, host.tableView?.isTerminal)
    }

    @Test
    fun `rapid burst of snapshots while active queues all steps for sequential playback`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-123",
            token = "jwt-abc",
            wsClient = fakeWs,
            requestTicket = { _, _, onDone ->
                onDone(Result.success(TableTicket("ticket-1", "tbl-123", seat = 0)))
            },
        )
        host.connectWithTicket(TableTicket("ticket-0", "tbl-123", seat = 0))

        val snap0 = makeSampleSnapshot(mySeat = 0).copy(currentPlayer = 0)
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap0))

        // Opponent away moves and next draw arrive in rapid burst
        val snap1 = makeSampleSnapshot(mySeat = 0).copy(
            currentPlayer = 1,
            events = listOf(PresentationEventWire.Draw(1, null)),
        )
        val snap2 = makeSampleSnapshot(mySeat = 0).copy(
            currentPlayer = 1,
            events = listOf(PresentationEventWire.Discard(1, 15)),
        )
        val snap3 = makeSampleSnapshot(mySeat = 0).copy(
            currentPlayer = 0,
            events = listOf(PresentationEventWire.Draw(0, 8)),
        )

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap1))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap2))
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(snap3))

        // Must retain all 3 steps in the animation queue for the player who never left
        assertEquals(3, host.sequencer.queueSize)
        assertFalse(host.sequencer.isCaughtUp)

        assertTrue(host.tryPresent(boardIdle = true))
        assertFalse(host.tryPresent(boardIdle = true))
        assertEquals(2, host.sequencer.queueSize)

        assertTrue(host.tryPresent(boardIdle = true))
        assertFalse(host.tryPresent(boardIdle = true))
        assertEquals(1, host.sequencer.queueSize)

        assertTrue(host.tryPresent(boardIdle = true))
        assertFalse(host.tryPresent(boardIdle = true))
        assertEquals(0, host.sequencer.queueSize)
        assertTrue(host.sequencer.isCaughtUp)
    }

    @Test
    fun `parseRoundsToMaxHands parses standard, legacy, and test round formats`() {
        assertEquals(4, OnlinePlayHost.parseRoundsToMaxHands("one"))
        assertEquals(8, OnlinePlayHost.parseRoundsToMaxHands("two"))
        assertEquals(4, OnlinePlayHost.parseRoundsToMaxHands("EastOnly"))
        assertEquals(4, OnlinePlayHost.parseRoundsToMaxHands("Rounds1"))
        assertEquals(8, OnlinePlayHost.parseRoundsToMaxHands("Rounds2"))
        assertEquals(16, OnlinePlayHost.parseRoundsToMaxHands("Rounds4"))
        assertEquals(16, OnlinePlayHost.parseRoundsToMaxHands("FourRounds"))
        assertEquals(4, OnlinePlayHost.parseRoundsToMaxHands("four_hands"))
        assertEquals(8, OnlinePlayHost.parseRoundsToMaxHands("eight_hands"))
        assertEquals(16, OnlinePlayHost.parseRoundsToMaxHands("sixteen_hands"))
        assertEquals(4, OnlinePlayHost.parseRoundsToMaxHands("1"))
        assertEquals(8, OnlinePlayHost.parseRoundsToMaxHands("2"))
        assertEquals(16, OnlinePlayHost.parseRoundsToMaxHands("4"))
        assertEquals(null, OnlinePlayHost.parseRoundsToMaxHands(null))
        assertEquals(null, OnlinePlayHost.parseRoundsToMaxHands("unlimited"))
        assertEquals(null, OnlinePlayHost.parseRoundsToMaxHands("unknown"))
    }

    @Test
    fun `terminal snapshot on the last hand finishes the match without a GameFinished message`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.connectWithTicket(TableTicket("ticket-0", "tbl-1", seat = 0))
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-1",
                seat = 0,
                rounds = "one",
                allowChicken = false,
            )
        )
        assertEquals(4, host.maxHands)
        assertEquals(MatchPhase.Live, host.matchPhase)

        // Hand 3 ends: terminal, but two hands would still be left in a 4-hand match.
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(handNumber = 3, isTerminal = true, winnerSeat = 0)
            )
        )
        assertEquals(MatchPhase.Live, host.matchPhase)
        assertTrue(host.canSubmitActions())

        // Hand 4 ends: the server never deals again, so this snapshot *is* match over.
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(handNumber = 4, isTerminal = true, winnerSeat = 0)
            )
        )
        assertEquals(MatchPhase.Finished, host.matchPhase)
        assertTrue(host.isGameFinished)
        assertFalse(host.isTableAborted)
        assertFalse(host.canSubmitActions())
    }

    @Test
    fun `match phase is monotone and never returns to live`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.TableJoined(tableId = "tbl-1", seat = 0, rounds = "one", allowChicken = false)
        )
        host.handleServerMessage(ServerMessageWire.TableAborted("Player disconnected"))
        assertEquals(MatchPhase.Aborted, host.matchPhase)

        // A late snapshot cannot resurrect a live phase.
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(mySeat = 0).copy(handNumber = 1))
        )
        assertEquals(MatchPhase.Aborted, host.matchPhase)
    }

    @Test
    fun `unparsable rounds leave the phase to the GameFinished message`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.TableJoined(tableId = "tbl-1", seat = 0, rounds = "unlimited", allowChicken = false)
        )
        assertNull(host.maxHands)

        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(handNumber = 12, isTerminal = true)
            )
        )
        assertEquals(MatchPhase.Live, host.matchPhase)

        host.handleServerMessage(ServerMessageWire.GameFinished(runningTotals = listOf(0, 0, 0, 0)))
        assertEquals(MatchPhase.Finished, host.matchPhase)
    }

    @Test
    fun `TableJoined and SeatSnapshotMsg track currentHandNumber and maxHands`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        assertEquals(1, host.currentHandNumber)
        assertEquals(null, host.maxHands)

        // Join fresh table: 1 round = 4 hands, 0 score rows
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-1",
                seat = 0,
                rounds = "one",
                allowChicken = false,
                players = emptyList(),
            )
        )
        assertEquals(4, host.maxHands)
        assertEquals(1, host.currentHandNumber)

        // Snapshot for hand 1
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(handNumber = 1)
            )
        )
        assertEquals(1, host.currentHandNumber)

        // Dealing next hand updates to hand 2
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(handNumber = 2)
            )
        )
        assertEquals(2, host.currentHandNumber)
    }

    @Test
    fun `reconnecting TableJoined with existing scoreRows initializes currentHandNumber`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        val dummyRows = listOf(
            HandScoreRowWire(1, 10, 0, 1, listOf(30, -10, -10, -10), listOf(30, -10, -10, -10)),
            HandScoreRowWire(2, 20, 1, 0, listOf(-20, 60, -20, -20), listOf(10, 50, -30, -30)),
        )

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-1",
                seat = 0,
                rounds = "two",
                allowChicken = false,
                scoreRows = dummyRows,
            )
        )
        assertEquals(8, host.maxHands)
        // 2 finished hands -> currently on hand 3
        assertEquals(3, host.currentHandNumber)
    }

    @Test
    fun `ServerShutdown message sets phase, message, and remaining time`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        assertNull(host.shutdownPhase)
        assertNull(host.shutdownRemainingMs)
        assertNull(host.shutdownMessage)

        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 180000L,
                message = "Maintenance in 3 minutes",
            )
        )
        assertEquals("warning", host.shutdownPhase)
        assertEquals(180000L, host.shutdownRemainingMs)
        assertEquals("Maintenance in 3 minutes", host.shutdownMessage)
    }

    @Test
    fun `advance ticks down shutdownRemainingMs and clamps at zero`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "final",
                remainingMs = 5000L,
                message = null,
            )
        )
        host.advance(2.0f)
        assertEquals(3000L, host.shutdownRemainingMs)

        host.advance(4.0f)
        assertEquals(0L, host.shutdownRemainingMs)
    }

    @Test
    fun `second ServerShutdown applies minOf correction to remaining time`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 100000L,
            )
        )
        assertEquals(100000L, host.shutdownRemainingMs)

        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 80000L,
            )
        )
        assertEquals(80000L, host.shutdownRemainingMs)

        // Slower/lagged higher value does not increase remaining time
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 90000L,
            )
        )
        assertEquals(80000L, host.shutdownRemainingMs)
    }

    @Test
    fun `ServerShutdownWithdrawn clears all shutdown state`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 60000L,
                message = "Temporary alert",
            )
        )
        assertEquals("warning", host.shutdownPhase)

        host.handleServerMessage(ServerMessageWire.ServerShutdownWithdrawn)
        assertNull(host.shutdownPhase)
        assertNull(host.shutdownRemainingMs)
        assertNull(host.shutdownMessage)
        assertFalse(host.shutdownEndedMatch)
    }

    @Test
    fun `TableJoined resets previous connect-scoped shutdown state`() {
        val host = OnlinePlayHost(tableId = "tbl-1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "warning",
                remainingMs = 60000L,
            )
        )
        assertEquals("warning", host.shutdownPhase)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-1",
                seat = 0,
                rounds = "one",
                allowChicken = false,
            )
        )
        assertNull(host.shutdownPhase)
        assertNull(host.shutdownRemainingMs)
        assertNull(host.shutdownMessage)
    }

    @Test
    fun `socket lost during pending shutdown marks shutdownEndedMatch and invokes leave callback without reconnect`() {
        val fakeWs = FakeWebSocketClient()
        var leaveCallbackInvoked = false
        val host = OnlinePlayHost(
            tableId = "tbl-1",
            token = "tok",
            wsClient = fakeWs,
            onLeaveCallback = { leaveCallbackInvoked = true },
        )

        val ticket = TableTicket(ticket = "t-1", tableId = "tbl-1", seat = 0)
        host.connectWithTicket(ticket)
        host.handleServerMessage(
            ServerMessageWire.ServerShutdown(
                phase = "final",
                remainingMs = 15000L,
                message = "Server restarting now",
            )
        )

        // Server closes socket when final shutdown completes
        fakeWs.close(1001, "Server shutdown")

        assertTrue(host.shutdownEndedMatch)
        assertEquals(ConnectionStatus.Disconnected, host.connectionStatus)
        assertFalse(host.reconnectRequired)
        assertTrue(leaveCallbackInvoked)
    }

    @Test
    fun `unknown wire message payload is swallowed without crashing or disconnecting (D11)`() {
        val fakeWs = FakeWebSocketClient()
        val host = OnlinePlayHost(
            tableId = "tbl-1",
            token = "tok",
            wsClient = fakeWs,
        )

        val ticket = TableTicket(ticket = "t-1", tableId = "tbl-1", seat = 0)
        host.connectWithTicket(ticket)
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)

        // Receive unknown message from future server
        fakeWs.receiveServerText("""{"type":"future_wire_msg","data":{"foo":"bar"}}""")

        // Host remains connected and did not crash
        assertEquals(ConnectionStatus.Connected, host.connectionStatus)
        assertTrue(fakeWs.isConnected)
    }

    @Test
    fun `pending discard origin is exposed as a session value`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(mySeat = 0)))
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                makeSampleSnapshot(mySeat = 0).copy(
                    currentPlayer = 1,
                    events = listOf(
                        PresentationEventWire.Discard(seat = 1, tile = 15, slotIndex = 3),
                    ),
                ),
            ),
        )
        assertEquals(1, host.sequencer.queueSize)

        assertTrue(host.tryPresent(boardIdle = true))
        assertEquals(DiscardOrigin.hand(1, 3), host.consumePendingDiscardOrigin())
        assertNull(host.consumePendingDiscardOrigin())
    }

    @Test
    fun `snapshot exposes the online read model without leaking the host`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(makeSampleSnapshot(mySeat = 0)))
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-123",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(0, "Alice", false),
                    TablePlayerWire(1, "Bot 1", true),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(30, -10, -10, -10),
            ),
        )

        val snapshot = host.snapshot
        assertEquals(ConnectionStatus.Connected, snapshot.connectionStatus)
        assertEquals(0, snapshot.humanSeat)
        assertEquals(listOf("Alice", "Bot 1", "", ""), snapshot.playerNames)
        assertEquals(listOf(false, true, false, false), snapshot.isBot)
        assertNotNull(snapshot.settlement)
        assertEquals(0, snapshot.settlement.winnerSeat)
        assertEquals(10, snapshot.settlement.details.totalScore)
        assertNull(snapshot.connectionFailure)
        assertFalse(snapshot.reconnectRequired)
    }

    @Test
    fun `a Shadow Ranked breakdown latches and a new table clears it`() {
        val host = OnlinePlayHost("tbl-123", "jwt-abc", FakeWebSocketClient())
        assertNull(host.snapshot.shadowRankedBreakdown)

        host.handleServerMessage(
            ServerMessageWire.ChallengeScoreBreakdown(
                publicId = "7K3H-M9PQ",
                totalScore = 48,
                ratingBefore = 1214.0,
                ratingAfter = 1219.33,
                ratingDelta = 0.01,
                comparisons = listOf(
                    ChallengeComparisonWire(
                        opponentName = null,
                        playerScore = 48,
                        opponentScore = 32,
                        opponentRating = 1200.0,
                        s = 1.0,
                        delta = 2.67,
                    ),
                ),
            ),
        )
        assertEquals("7K3H-M9PQ", host.snapshot.shadowRankedBreakdown?.publicId)
        assertEquals(1, host.snapshot.shadowRankedBreakdown?.comparisons?.size)

        // The next table is a different attempt: the old breakdown must not leak.
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl-124",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )
        assertNull(host.snapshot.shadowRankedBreakdown)
    }
}


