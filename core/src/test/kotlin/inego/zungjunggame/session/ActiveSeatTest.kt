package inego.zungjunggame.session

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.online.toTableViewState
import inego.zungjunggame.session.table.TableViewState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [inego.zungjunggame.session.table.TableViewState.activeSeat] is the seat the table highlights — the seat whose turn it is.
 * It parts company with [inego.zungjunggame.session.table.TableViewState.currentPlayer] while a discard is open for claims:
 * the engine walks `currentPlayer` through the reactors, but the discarder keeps the
 * highlight. Local projection ([inego.zungjunggame.session.table.TableViewState.fromGameState]) and online snapshot mapping
 * (`SeatSnapshotWire.toTableViewState`) must agree, since both feed the same renderer.
 */
class ActiveSeatTest {

    private fun canonicalWall(rng: Random): IntArray {
        val list = ArrayList<Int>(136)
        for (t in 0 until NUM_TILE_TYPES) repeat(4) { list.add(t) }
        list.shuffle(rng)
        return list.toIntArray()
    }

    @Test
    fun actingSeatIsActiveSeatOnItsOwnTurn() {
        val state = GameState.fromWallOrder(canonicalWall(Random(11)), eastSeat = 0)
        assertEquals(DecisionType.ActOnHand, state.decisionType)

        val view = TableViewState.fromGameState(state, humanSeat = 0)
        assertEquals(view.currentPlayer, view.activeSeat)
    }

    @Test
    fun discarderKeepsActiveSeatWhileOthersReact() {
        val state = GameState.fromWallOrder(canonicalWall(Random(4)), eastSeat = 0)
        val board = BoardController()
        val discarder = state.currentPlayer

        val discard = board.allowedChoices(state).first { it.kind == ChoiceKind.Discard }
        board.tryApply(state, discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        var steps = 0
        while (state.decisionType == DecisionType.ReactToDiscard && steps++ < NUM_PLAYERS) {
            // The engine hands the window to another seat...
            assertNotEquals(discarder, state.currentPlayer)
            // ...but the highlight must not follow it.
            assertEquals(
                discarder,
                TableViewState.fromGameState(state, humanSeat = discarder).activeSeat,
                "discarder must stay highlighted while seat ${state.currentPlayer} reacts",
            )
            // Everyone passes, so the window resolves without a claim.
            state.applyChoice(state.enumerateChoices().asList().first { it.kind == ChoiceKind.Pass })
        }

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        val resolved = TableViewState.fromGameState(state, humanSeat = discarder)
        assertEquals(resolved.currentPlayer, resolved.activeSeat, "claim resolved: the turn moves on")
        assertNotEquals(discarder, resolved.activeSeat)
    }

    @Test
    fun winnerKeepsActiveSeatAtHandEnd() {
        val state = GameState.fromWallOrder(canonicalWall(Random(7)), eastSeat = 0)
        // Resting state of a win on a discard: the winner is currentPlayer, the
        // discarder is lastDiscarder, and decisionType stays ReactToDiscard.
        state.lastDiscarder = 1
        state.lastDiscarded = 10
        state.currentPlayer = 2
        state.decisionType = DecisionType.ReactToDiscard
        state.handScore = 24
        state.winnerSeat = 2
        state.winSource = 1

        val view = TableViewState.fromGameState(state, humanSeat = 0)
        assertEquals(2, view.activeSeat, "the winner, not the discarder, holds the table at hand end")
    }

    // --- online: same rule, fed from a server snapshot ---------------------

    private fun snapshot(
        currentPlayer: Int,
        decisionType: String,
        lastDiscarder: Int?,
        lastDiscarded: Int? = null,
        isPendingDiscard: Boolean = false,
        isKongRobWindow: Boolean = false,
    ): SeatSnapshotWire = SeatSnapshotWire(
        handNumber = 1,
        mySeat = 0,
        eastSeat = 0,
        liveWallSize = 70,
        currentPlayer = currentPlayer,
        decisionType = decisionType,
        lastDiscarder = lastDiscarder,
        lastDiscarded = lastDiscarded,
        isPendingDiscard = isPendingDiscard,
        isKongRobWindow = isKongRobWindow,
        handScore = 0,
        winnerSeat = null,
        winSource = null,
        isTerminal = false,
        scoreDeltas = null,
        seats = (0 until NUM_PLAYERS).map { seat ->
            SeatSnapshotViewWire(
                seat = seat,
                concealedCount = 13,
                concealedTiles = null,
                drawnTile = -1,
                hasDrawnTile = false,
                melds = emptyList(),
                river = emptyList(),
                seatWindTile = seat,
            )
        },
    )

    @Test
    fun onlineReactingSeatDoesNotStealTheHighlight() {
        val view = snapshot(
            currentPlayer = 2,
            decisionType = "ReactToDiscard",
            lastDiscarder = 0,
            lastDiscarded = 10,
            isPendingDiscard = true,
        ).toTableViewState()

        assertEquals(2, view.currentPlayer)
        assertEquals(0, view.activeSeat, "the discarder stays highlighted online too")
    }

    @Test
    fun onlineKongRobWindowStaysOnThePromoter() {
        val view = snapshot(
            currentPlayer = 1,
            decisionType = "react_to_discard",
            lastDiscarder = 0,
            lastDiscarded = 10,
            isPendingDiscard = true,
            isKongRobWindow = true,
        ).toTableViewState()

        assertEquals(0, view.activeSeat, "a promoted kong keeps the promoter highlighted")
    }

    @Test
    fun onlineActOnHandKeepsCurrentPlayer() {
        val view = snapshot(
            currentPlayer = 3,
            decisionType = "ActOnHand",
            lastDiscarder = 0,
            lastDiscarded = 10,
        ).toTableViewState()

        assertEquals(3, view.activeSeat, "a stale last discarder must not hold the highlight")
    }
}
