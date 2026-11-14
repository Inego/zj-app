package inego.zungjunggame.features.online

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.PresentationEventWire
import inego.zungjunggame.net.ChallengeComparisonWire
import inego.zungjunggame.net.ScoreComponentWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TablePlayerWire
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.session.FakeWebSocketClient
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.hosts.FreePlayBreakdownPort
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest
import kotlin.test.Test

class OnlineSettlementPresenterTest {

    @BeforeTest
    fun setUp() {
        L10n.resetForTests()
        val cwd = File(".").canonicalFile
        val assets = listOf(
            File(cwd, "assets"),
            File(cwd.parentFile, "assets"),
        ).first { File(it, "i18n").isDirectory }
        L10n.bootstrap(Locale.US, com.badlogic.gdx.files.FileHandle(File(assets, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
    }


    /** The route-backed port is exercised in `ScreenTransitionsTest`; here it is inert. */
    private object NoopBreakdownPort : FreePlayBreakdownPort {
        override fun show() {}
        override fun hide() {}
    }

    /** Counts the presenter's port calls, so a gated sync's effect is observable. */
    private class RecordingBreakdownPort : FreePlayBreakdownPort {
        var shows: Int = 0
            private set
        var hides: Int = 0
            private set

        override fun show() {
            shows++
        }

        override fun hide() {
            hides++
        }
    }

    @Test
    fun returnsNullWhenNoSettlement() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        assertNull(presenter.buildPresentation(host.snapshot))
    }

    @Test
    fun formatsWinSettlementWithDeltasAndScores() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 1,
                rounds = "Rounds4",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(seat = 0, name = "Alice", isBot = false),
                    TablePlayerWire(seat = 1, name = "Bob", isBot = false),
                    TablePlayerWire(seat = 2, name = "Charlie", isBot = false),
                    TablePlayerWire(seat = 3, name = "Diana", isBot = true),
                ),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 40,
                winnerSeat = 1,
                deltas = listOf(-25, 65, -20, -20),
                runningTotals = listOf(-25, 65, -20, -20),
                patterns = listOf(
                    ScoreComponentWire("all_simples", 5),
                    ScoreComponentWire("concealed_hand", 5),
                ),
            ),
        )

        PlayerLabels.setPlayerNames(host.playerNames, host.isBot)
        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(HandEndAction.NextHand, presentation.primaryAction)
        // A running match has no game result yet: the card is only about the hand.
        assertNull(presentation.gameResult)
        assertEquals(40, presentation.details.totalScore)
        assertEquals(2, presentation.details.components.size)
        assertEquals(4, presentation.settlement.size)

        // Check human seat in settlement
        val humanLine = presentation.settlement.firstOrNull { it.isHuman }
        assertNotNull(humanLine)
        assertEquals(65, humanLine.delta)
        assertEquals(65, humanLine.after)
        assertEquals(0, humanLine.before)

        // Verify settlement ordering: cum score decreasing, ties broken counterclockwise from Bob (seat 1)
        // Bob (65), Charlie (-20, right/rel 1), Diana (-20, opp/rel 2), Alice (-25, left/rel 3)
        assertEquals(
            listOf("Bob", "Charlie", L10n.get("seat.bot"), "Alice"),
            presentation.settlement.map { it.name },
        )
        assertEquals(listOf(65, -20, -20, -25), presentation.settlement.map { it.after })
    }

    @Test
    fun finishedGameWithATiedPlaceSaysHowManyShareIt() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(80, 80, -40, -40),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(80, 80, -40, -40),
            ),
        )

        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(
            L10n.format("history.place_shared", L10n.get("history.place_1"), 2),
            presentation.gameResult,
        )
    }

    @Test
    fun finishedGameWithAnAllSquareMatchSharesFirstPlace() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(0, 0, 0, 0),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(0, 0, 0, 0),
            ),
        )

        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        // Nobody lost: the drawn last hand keeps its own headline, and all four
        // seats share first place.
        assertEquals(L10n.get("hand.draw"), presentation.headline)
        assertEquals(
            L10n.format("history.place_shared", L10n.get("history.place_1"), 4),
            presentation.gameResult,
        )
    }

    @Test
    fun handlesDrawSettlement() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 2,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(10, 10, -10, -10),
            ),
        )

        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(0, presentation.details.totalScore)
        assertTrue(presentation.ratingLines.isEmpty())
    }

    @Test
    fun formatsRatingLinesForTwoHumansWithRounding() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(seat = 0, name = "Alice", isBot = false),
                    TablePlayerWire(seat = 1, name = "Bob", isBot = false),
                    TablePlayerWire(seat = 2, name = "Bot1", isBot = true),
                    TablePlayerWire(seat = 3, name = "Bot2", isBot = true),
                ),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(100, -20, -40, -40),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(100, -20, -40, -40),
                ratingUpdates = listOf(
                    inego.zungjunggame.net.PlayerRatingUpdateWire(
                        seat = 0,
                        userId = "u1",
                        oldRating = 1500.0,
                        newRating = 1515.5,
                        delta = 15.5,
                    ),
                    inego.zungjunggame.net.PlayerRatingUpdateWire(
                        seat = 1,
                        userId = "u2",
                        oldRating = 1500.0,
                        newRating = 1484.5,
                        delta = -15.5,
                    ),
                ),
            ),
        )

        PlayerLabels.setPlayerNames(host.playerNames, host.isBot)
        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(2, presentation.ratingLines.size)

        // Ordered uniformly by match ranking (Alice with cum score 100 > Bob with -20)
        assertEquals(listOf("Alice", "Bob"), presentation.ratingLines.map { it.name })
        val alice = presentation.ratingLines.first { it.isHuman }
        assertEquals("Alice", alice.name)
        assertEquals(1500, alice.oldRating)
        assertEquals(1516, alice.newRating)
        assertEquals(16, alice.delta)
        assertTrue(alice.isHuman)

        val bob = presentation.ratingLines.first { !it.isHuman }
        assertEquals("Bob", bob.name)
        assertEquals(1500, bob.oldRating)
        assertEquals(1485, bob.newRating)
        assertEquals(-15, bob.delta)
    }

    @Test
    fun ordersRatingLinesByMatchRankingWhenOpponentWins() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(seat = 0, name = "Alice", isBot = false),
                    TablePlayerWire(seat = 1, name = "Bob", isBot = false),
                ),
            ),
        )

        // Bob has cum score 80 (1st), Alice has cum score -40 (2nd)
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 1,
                deltas = listOf(-40, 40, 0, 0),
                runningTotals = listOf(-40, 80, 0, 0),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(-40, 80, 0, 0),
                ratingUpdates = listOf(
                    inego.zungjunggame.net.PlayerRatingUpdateWire(
                        seat = 0,
                        userId = "u1",
                        oldRating = 1500.0,
                        newRating = 1488.0,
                        delta = -12.0,
                    ),
                    inego.zungjunggame.net.PlayerRatingUpdateWire(
                        seat = 1,
                        userId = "u2",
                        oldRating = 1500.0,
                        newRating = 1512.0,
                        delta = 12.0,
                    ),
                ),
            ),
        )

        PlayerLabels.setPlayerNames(host.playerNames, host.isBot)
        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(2, presentation.ratingLines.size)
        // 1st: Bob (cum score 80)
        assertEquals("Bob", presentation.ratingLines[0].name)
        assertEquals(1512, presentation.ratingLines[0].newRating)
        assertEquals(12, presentation.ratingLines[0].delta)
        assertFalse(presentation.ratingLines[0].isHuman)

        // 2nd: Alice (cum score -40)
        assertEquals("Alice", presentation.ratingLines[1].name)
        assertEquals(1488, presentation.ratingLines[1].newRating)
        assertEquals(-12, presentation.ratingLines[1].delta)
        assertTrue(presentation.ratingLines[1].isHuman)
    }

    @Test
    fun showsSelfRatingWhenOnlyOneHuman() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(seat = 0, name = "Alice", isBot = false),
                    TablePlayerWire(seat = 1, name = "Bot1", isBot = true),
                ),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(100, -20, -40, -40),
            ),
        )

        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(100, -20, -40, -40),
                ratingUpdates = listOf(
                    inego.zungjunggame.net.PlayerRatingUpdateWire(
                        seat = 0,
                        userId = "u1",
                        oldRating = 1500.0,
                        newRating = 1500.0,
                        delta = 0.0,
                    ),
                ),
            ),
        )

        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        assertEquals(1, presentation.ratingLines.size)
        val line = presentation.ratingLines.single()
        assertTrue(line.isHuman)
        assertFalse(line.pending)
        assertEquals(1500, line.oldRating)
        assertEquals(1500, line.newRating)
        assertEquals(0, line.delta)
    }

    @Test
    fun holdsScoreOverlayUntilPrecedingAnimationsFinish() {
        val host = joinedHost()
        val port = RecordingBreakdownPort()
        val presenter = OnlineSettlementPresenter(breakdown = port)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(sampleSnapshot()))
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                sampleSnapshot(
                    events = listOf(PresentationEventWire.Discard(seat = 1, tile = 10)),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                sampleSnapshot(
                    isTerminal = true,
                    winnerSeat = 0,
                    events = listOf(PresentationEventWire.HandEnd(winnerSeat = 0, winSource = 1)),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 30,
                winnerSeat = 0,
                deltas = listOf(90, -30, -30, -30),
                runningTotals = listOf(90, -30, -30, -30),
            ),
        )

        assertEquals(2, host.sequencer.queueSize)
        assertFalse(host.sequencer.isCaughtUp)
        presenter.sync(host.snapshot)
        assertEquals(0, port.shows)

        assertTrue(host.tryPresent(boardIdle = true))
        presenter.sync(host.snapshot)
        assertEquals(0, port.shows)

        assertFalse(host.tryPresent(boardIdle = true))
        presenter.sync(host.snapshot)
        assertEquals(0, port.shows)

        assertTrue(host.tryPresent(boardIdle = true))
        presenter.sync(host.snapshot)
        assertEquals(0, port.shows)

        assertFalse(host.tryPresent(boardIdle = true))
        assertTrue(host.sequencer.isCaughtUp)
        presenter.sync(host.snapshot)
        assertEquals(1, port.shows)
    }

    @Test
    fun holdsDrawOverlayUntilPrecedingAnimationsFinish() {
        val host = joinedHost()
        val port = RecordingBreakdownPort()
        val presenter = OnlineSettlementPresenter(breakdown = port)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(sampleSnapshot()))
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                sampleSnapshot(
                    events = listOf(PresentationEventWire.Draw(seat = 2, tile = null)),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.SeatSnapshotMsg(
                sampleSnapshot(
                    isTerminal = true,
                    events = listOf(PresentationEventWire.HandEnd()),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 0,
                winnerSeat = null,
                deltas = listOf(0, 0, 0, 0),
                runningTotals = listOf(0, 0, 0, 0),
            ),
        )

        presenter.sync(host.snapshot)
        assertEquals(0, port.shows)

        while (!host.sequencer.isCaughtUp) {
            host.tryPresent(boardIdle = true)
        }
        presenter.sync(host.snapshot)
        assertEquals(1, port.shows)
    }

    @Test
    fun showsScoreOverlayImmediatelyWhenPresentationIsCaughtUp() {
        val host = joinedHost()
        val port = RecordingBreakdownPort()
        val presenter = OnlineSettlementPresenter(breakdown = port)

        host.handleServerMessage(ServerMessageWire.SeatSnapshotMsg(sampleSnapshot()))
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 1,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(30, -10, -10, -10),
            ),
        )

        assertTrue(host.sequencer.isCaughtUp)
        presenter.sync(host.snapshot)
        assertEquals(1, port.shows)
    }

    @Test
    fun opensTheShadowRankedComparisonWithTheBotAndEarlierPlayers() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val port = RecordingBreakdownPort()
        val presenter = OnlineSettlementPresenter(breakdown = port)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
                players = listOf(
                    TablePlayerWire(seat = 0, name = "Alice", isBot = false),
                    TablePlayerWire(seat = 1, name = "Bot 1", isBot = true),
                    TablePlayerWire(seat = 2, name = "Bot 2", isBot = true),
                    TablePlayerWire(seat = 3, name = "Bot 3", isBot = true),
                ),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(48, -16, -16, -16),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(48, -16, -16, -16),
                startedAt = "2024-01-01T00:00:00Z",
                finishedAt = "2024-01-01T00:03:15Z",
            ),
        )
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
                    ChallengeComparisonWire(
                        opponentName = "Maria",
                        playerScore = 48,
                        opponentScore = 80,
                        opponentRating = 1204.0,
                        s = 0.0,
                        delta = -2.67,
                    ),
                ),
            ),
        )

        // The finish opens the card, and the bot's match total is only here.
        presenter.sync(host.snapshot)
        assertEquals(1, port.shows)

        val presentation = presenter.buildPresentation(host.snapshot)
        assertNotNull(presentation)
        val comparison = assertNotNull(presentation.comparison)
        assertEquals("7K3H-M9PQ", comparison.publicId)
        assertEquals(48, comparison.playerScore)
        assertEquals(1214.0, assertNotNull(comparison.ratingBefore), 0.0)
        assertEquals(1219.33, assertNotNull(comparison.ratingAfter), 1e-6)
        assertEquals(0.01, comparison.ratingDelta, 1e-6)
        assertEquals(2, comparison.comparisons.size)
        val bot = comparison.comparisons[0]
        assertEquals(L10n.get("seat.bot"), bot.name)
        assertTrue(bot.isBot)
        assertEquals(1200.0, assertNotNull(bot.opponentRating), 0.0)
        assertEquals(32, bot.opponentScore)
        assertEquals(2.67, assertNotNull(bot.delta), 1e-6)
        val maria = comparison.comparisons[1]
        assertEquals("Maria", maria.name)
        assertFalse(maria.isBot)
        assertEquals(1204.0, assertNotNull(maria.opponentRating), 0.0)
        assertEquals(80, maria.opponentScore)
        assertEquals(-2.67, assertNotNull(maria.delta), 1e-6)
        // A Shadow Ranked attempt has no place among the bots: the comparison is the result.
        assertNull(presentation.gameResult)
        assertTrue(presentation.ratingLines.isEmpty())
        // The attempt is a finished game too, so it shows its span under the result.
        assertEquals(L10n.format("hand.duration", "03:15"), presentation.durationLabel)
    }

    @Test
    fun finishedMatchCarriesItsWallClockDuration() {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 4,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(100, -20, -40, -40),
            ),
        )
        host.handleServerMessage(
            ServerMessageWire.GameFinished(
                runningTotals = listOf(100, -20, -40, -40),
                startedAt = "2024-01-01T00:00:00Z",
                finishedAt = "2024-01-01T00:08:30Z",
            ),
        )

        val presentation = assertNotNull(presenter.buildPresentation(host.snapshot))
        assertEquals(L10n.format("hand.duration", "08:30"), presentation.durationLabel)
    }

    @Test
    fun aRunningMatchHasNoDurationLine() {
        val host = joinedHost()
        val presenter = OnlineSettlementPresenter(breakdown = NoopBreakdownPort)

        host.handleServerMessage(
            ServerMessageWire.HandSettlement(
                handNumber = 2,
                handScore = 10,
                winnerSeat = 0,
                deltas = listOf(30, -10, -10, -10),
                runningTotals = listOf(30, -10, -10, -10),
            ),
        )

        val presentation = assertNotNull(presenter.buildPresentation(host.snapshot))
        assertNull(presentation.durationLabel)
    }

    private fun joinedHost(): OnlinePlayHost {
        val host = OnlinePlayHost(tableId = "tbl_1", token = "tok", wsClient = FakeWebSocketClient())
        host.handleServerMessage(
            ServerMessageWire.TableJoined(
                tableId = "tbl_1",
                seat = 0,
                rounds = "Rounds1",
                allowChicken = true,
            ),
        )
        return host
    }

    private fun sampleSnapshot(
        isTerminal: Boolean = false,
        winnerSeat: Int? = null,
        events: List<PresentationEventWire> = emptyList(),
        decisionType: String = "ActOnHand",
        lastDiscarder: Int? = null,
        lastDiscarded: Int? = null,
        isPendingDiscard: Boolean = false,
        winSource: Int? = null,
    ): SeatSnapshotWire {
        val seats = (0..3).map { s ->
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == 0) listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9) else null,
                drawnTile = -1,
                hasDrawnTile = false,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = 1,
            mySeat = 0,
            eastSeat = 0,
            liveWallSize = 70,
            currentPlayer = 0,
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
            seats = seats,
            events = events,
        )
    }
}
