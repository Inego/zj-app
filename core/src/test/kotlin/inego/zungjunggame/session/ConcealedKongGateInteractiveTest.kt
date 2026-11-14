package inego.zungjunggame.session

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.net.ChoiceWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.session.online.toActionableDecision
import inego.zungjunggame.session.online.toTableViewState
import inego.zungjunggame.session.table.TableViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sequential concealed-kong gate (`ConcealedKong` + `Keep`, no `Discard`)
 * parks the hand until the human answers, so the claim row it draws is the whole
 * of the player's turn.
 *
 * Seen online, that row was dead: the server's `enumerate_hand_actions` returns
 * only the two gate choices, the online projection therefore reported neither a
 * discard turn nor a claim turn, and `isHumanInteractiveTurn` — the guard the
 * human-choice commit starts with — came back false. The buttons
 * were painted and hoverable, and every tap on them (kong *and* Оставить) was
 * dropped without a sound; the table sat on the unanswered prompt.
 *
 * Local play never showed it, because [inego.zungjunggame.session.table.TableViewState.fromGameState] derives the
 * discard turn from `decisionType` alone. Both projections feed the same input
 * path, so both are pinned here.
 */
class ConcealedKongGateInteractiveTest {

    /** The prompt the server sends for the gate: `ConcealedKong(2m)` + `Keep(2m)`. */
    private fun gateView(): TableViewState {
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 7,
            decisionType = "ActOnHand",
            choices = listOf(
                ChoiceWire(kind = "ConcealedKong", payload = Tiles.CHAR_2),
                ChoiceWire(kind = "Keep", payload = Tiles.CHAR_2),
            ),
            deadlineMs = 20_000,
        )
        return snapshot(currentPlayer = 0, decisionType = "ActOnHand")
            .toTableViewState(decision = prompt.toActionableDecision())
    }

    @Test
    fun onlineConcealedKongGatePanelIsInteractive() {
        val view = gateView()

        assertEquals(
            listOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep),
            view.myChoices.asList().map { it.kind },
            "the gate row the player sees is exactly these two buttons",
        )
        assertTrue(view.isHumanActOnHandPanel, "the row is on screen for this seat")
        assertFalse(view.isHumanDiscardTurn, "the gate offers no discard to make")
        assertFalse(view.isHumanClaimTurn, "the gate is not a discard reaction")
        assertTrue(
            view.isHumanInteractiveTurn,
            "a tap on the on-screen gate row must survive tryHumanChoice's guard",
        )
    }

    @Test
    fun promotedKongPanelStaysInteractive() {
        // The other panel action that shares a turn with discards; it offers
        // Discard too, so this half never regressed — pinned so a future change
        // to either flag cannot quietly take it away.
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 8,
            decisionType = "ActOnHand",
            choices = listOf(
                ChoiceWire(kind = "Discard", payload = Tiles.CHAR_2),
                ChoiceWire(kind = "PromoteKong", payload = Tiles.CHAR_2),
            ),
            deadlineMs = 20_000,
        )
        val view = snapshot(currentPlayer = 0, decisionType = "ActOnHand")
            .toTableViewState(decision = prompt.toActionableDecision())

        assertTrue(view.isHumanActOnHandPanel)
        assertTrue(view.isHumanDiscardTurn)
        assertTrue(view.isHumanInteractiveTurn)
    }

    @Test
    fun localConcealedKongGateIsInteractiveToo() {
        val state = GameState()
        state.decisionType = DecisionType.ActOnHand
        state.currentPlayer = 0
        state.liveWallSize = 40
        val ps = state.players[0]
        for (t in ps.concealed.indices) ps.concealed[t] = 0
        ps.concealed[Tiles.CHAR_2] = 4
        ps.concealed[Tiles.CHAR_1] = 10
        ps.activeConcealedTileCount = -1
        ps.ensureActiveConcealedTiles()

        val kinds = state.enumerateChoices().asList().map { it.kind }
        assertEquals(listOf(ChoiceKind.ConcealedKong, ChoiceKind.Keep), kinds)

        val view = TableViewState.fromGameState(state, humanSeat = 0)
        assertTrue(view.isHumanActOnHandPanel)
        assertTrue(
            view.isHumanInteractiveTurn,
            "the local projection must agree with the online one on the same gate",
        )
    }

    private fun snapshot(currentPlayer: Int, decisionType: String): SeatSnapshotWire = SeatSnapshotWire(
        handNumber = 1,
        mySeat = 0,
        eastSeat = 0,
        liveWallSize = 70,
        currentPlayer = currentPlayer,
        decisionType = decisionType,
        lastDiscarder = null,
        lastDiscarded = null,
        isPendingDiscard = false,
        isKongRobWindow = false,
        handScore = 0,
        winnerSeat = null,
        winSource = null,
        isTerminal = false,
        scoreDeltas = null,
        seats = (0 until NUM_PLAYERS).map { seat ->
            SeatSnapshotViewWire(
                seat = seat,
                concealedCount = 14,
                concealedTiles = null,
                drawnTile = -1,
                hasDrawnTile = false,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = seat,
            )
        },
    )
}
