package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.LocalPlayHost
import inego.zungjunggame.session.table.TableViewState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableViewStateTest {

    private fun canonicalWall(rng: Random): IntArray {
        val list = ArrayList<Int>(136)
        for (t in 0 until NUM_TILE_TYPES) repeat(4) { list.add(t) }
        list.shuffle(rng)
        return list.toIntArray()
    }

    @Test
    fun opponentConcealedTilesAndDrawsAreStrictlyHidden() {
        val state = GameState.fromWallOrder(canonicalWall(Random(42)), eastSeat = 0)
        val view = TableViewState.fromGameState(state, humanSeat = 0)

        assertEquals(0, view.mySeat)
        assertEquals(0, view.eastSeat)
        assertEquals(4, view.seats.size)

        // Human seat (0): concealed tiles must be visible and size 14 (opening hand)
        val mySeatView = view.seats[0]
        assertNotNull(mySeatView.concealedTiles)
        assertEquals(14, mySeatView.concealedCount)
        assertEquals(14, mySeatView.concealedTiles.size)

        // Opponents (1, 2, 3): concealed tiles MUST be null and drawnTile must be -1
        for (opp in 1..3) {
            val oppView = view.seats[opp]
            assertNull(oppView.concealedTiles, "Opponent $opp concealed tiles must be null")
            assertEquals(13, oppView.concealedCount, "Opponent $opp concealed count must be 13")
            assertEquals(-1, oppView.drawnTile, "Opponent $opp drawn tile must be -1")
            assertFalse(oppView.hasDrawnTile, "Opponent $opp should not have drawn tile parked at start")
        }
    }

    @Test
    fun opponentDrawnTileWhenActingIsHiddenTypeButFlagged() {
        val state = GameState.fromWallOrder(canonicalWall(Random(10)), eastSeat = 0)
        val tile = Tiles.BAMBOO_1
        state.players[1].concealed[tile]++
        state.currentPlayer = 1
        state.decisionType = DecisionType.ActOnHand
        state.lastDrawnPlayer = 1
        state.lastDrawnTile = tile

        val view = TableViewState.fromGameState(state, humanSeat = 0)
        val oppView = view.seats[1]

        assertNull(oppView.concealedTiles, "Opponent concealed tiles must be null")
        assertEquals(-1, oppView.drawnTile, "Opponent drawn tile type must NOT be exposed")
        assertTrue(oppView.hasDrawnTile, "Opponent hasDrawnTile must be true to reserve drawn slot")
    }

    @Test
    fun revealedSeatsExposesOnlySpecifiedSeats() {
        val state = GameState.fromWallOrder(canonicalWall(Random(99)), eastSeat = 0)
        val view = TableViewState.fromGameState(state, humanSeat = 0, revealedSeats = setOf(2))

        assertNotNull(view.seats[0].concealedTiles, "Human tiles must be visible")
        assertNull(view.seats[1].concealedTiles, "Unrevealed opponent 1 tiles must be null")
        assertNotNull(view.seats[2].concealedTiles, "Revealed opponent 2 tiles must be visible")
        assertNull(view.seats[3].concealedTiles, "Unrevealed opponent 3 tiles must be null")
    }

    @Test
    fun revealedSeatsAtTerminalExposesWinner() {
        val state = GameState.fromWallOrder(canonicalWall(Random(7)), eastSeat = 0)
        // Set handScore > 0 and winnerSeat = 2
        state.handScore = 40
        state.winnerSeat = 2
        state.winSource = 1
        state.decisionType = DecisionType.ReactToDiscard

        val view = TableViewState.fromGameState(state, humanSeat = 0)

        assertEquals(2, view.winnerSeat)
        assertEquals(1, view.winSource)
        assertTrue(view.isTerminal)
        assertNotNull(view.seats[0].concealedTiles, "Human tiles must be visible")
        assertNull(view.seats[1].concealedTiles, "Opponent 1 tiles must be null")
        assertNotNull(view.seats[2].concealedTiles, "Winner (opponent 2) tiles must be visible at hand end")
        assertNull(view.seats[3].concealedTiles, "Opponent 3 tiles must be null")
    }

    @Test
    fun humanTurnPredicatesActOnHand() {
        val state = GameState.fromWallOrder(canonicalWall(Random(123)), eastSeat = 0)

        // Opening hand: human is east (seat 0) in ActOnHand
        val humanView = TableViewState.fromGameState(state, humanSeat = 0)
        assertTrue(humanView.isHumanDiscardTurn)
        assertFalse(humanView.isHumanClaimTurn)
        assertTrue(humanView.isHumanInteractiveTurn)

        // When seat 1 is acting on hand
        state.currentPlayer = 1
        val opponentView = TableViewState.fromGameState(state, humanSeat = 0)
        assertFalse(opponentView.isHumanDiscardTurn)
        assertFalse(opponentView.isHumanClaimTurn)
        assertFalse(opponentView.isHumanInteractiveTurn)
    }

    @Test
    fun localPlayHostDecisionLifecycle() {
        val state = GameState.fromWallOrder(canonicalWall(Random(77)), eastSeat = 0)
        val board = BoardController()
        val liveHand = object : LiveHand {
            override val state: GameState get() = state
            override val humanSeat: Int get() = 0
            override val active: Boolean get() = true
            override val choiceRng: Random get() = Random(1)
        }

        var nextHandRequested = false
        var leaveRequested = false
        val host = LocalPlayHost(
            liveHand = liveHand,
            board = board,
            onNextHand = { nextHandRequested = true },
            onLeave = { leaveRequested = true },
        )

        assertEquals(ConnectionStatus.Local, host.connectionStatus)
        assertEquals(0, host.humanSeat)

        // On opening turn: human seat 0 has an open discard decision
        val decision = host.myDecision
        assertNotNull(decision)
        assertTrue(decision.isDiscard)
        assertFalse(decision.isClaim)

        // Find a legal discard tile
        val legalDiscard = decision.choices.asList().first { it.kind == ChoiceKind.Discard }.tile()

        // Submit discard through the host
        val applied = host.submitDiscard(legalDiscard)
        assertTrue(applied)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        // Now it's ReactToDiscard: seat 1's reaction window
        // Human (seat 0) was the discarder, so human has no open decision
        val decisionAfterDiscard = host.myDecision
        assertNull(decisionAfterDiscard, "Discarder should have no decision in their own reaction window")

        // Test lifecycle hooks
        host.requestNextHand()
        assertTrue(nextHandRequested)

        host.leave()
        assertTrue(leaveRequested)
    }

    @Test
    fun canDiscardDrawnTileGatesEastFirstDiscardAndHuOption() {
        val state = GameState.fromWallOrder(canonicalWall(Random(123)), eastSeat = 0)

        // 1. East's opening hand (East's first discard):
        // Human is east (seat 0) in ActOnHand, but it's East's first discard (and dealt, not drawn from wall)
        val eastOpeningView = TableViewState.fromGameState(state, humanSeat = 0)
        assertTrue(eastOpeningView.isHumanDiscardTurn)
        assertFalse(eastOpeningView.canDiscardDrawnTile, "Must not work in East's first discard")

        // 2. East discards; others pass -> seat 1 draws from the wall
        val openingDiscard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(openingDiscard)
        while (state.decisionType == DecisionType.ReactToDiscard) {
            state.applyChoice(Choice.pass())
        }

        // Now seat 1 drew a tile from the wall
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(1, state.currentPlayer)
        val seat1View = TableViewState.fromGameState(state, humanSeat = 1)
        assertTrue(seat1View.isHumanDiscardTurn)
        assertTrue(seat1View.seats[1].hasDrawnTile)
        assertTrue(seat1View.canDiscardDrawnTile, "Allowed when tile just taken from wall and no Hu option")

        // Opponents during seat 1's turn cannot discard
        val seat0DuringSeat1View = TableViewState.fromGameState(state, humanSeat = 0)
        assertFalse(seat0DuringSeat1View.isHumanDiscardTurn)
        assertFalse(seat0DuringSeat1View.canDiscardDrawnTile)

        // 3. When a Hu option (self-draw win) is available:
        val choicesWithHu = ChoiceList()
        choicesWithHu.pushBack(Choice.hu(40))
        choicesWithHu.pushBack(Choice.discard(seat1View.seats[1].drawnTile))
        val seat1ViewWithHu = TableViewState.fromGameState(state, humanSeat = 1, choices = choicesWithHu)
        assertTrue(seat1ViewWithHu.isHumanDiscardTurn)
        assertFalse(seat1ViewWithHu.canDiscardDrawnTile, "Must not work when a Hu option is available")

        // 4. East's second turn (after drawing from the wall on turn 2):
        // Advance until east acts again
        val seat1Discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(seat1Discard)
        while (state.decisionType == DecisionType.ReactToDiscard) state.applyChoice(Choice.pass())
        val seat2Discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(seat2Discard)
        while (state.decisionType == DecisionType.ReactToDiscard) state.applyChoice(Choice.pass())
        val seat3Discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(seat3Discard)
        while (state.decisionType == DecisionType.ReactToDiscard) state.applyChoice(Choice.pass())

        assertEquals(0, state.currentPlayer)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        val eastTurn2View = TableViewState.fromGameState(state, humanSeat = 0)
        assertTrue(eastTurn2View.seats[0].river.isNotEmpty(), "East's river has the first discard")
        assertTrue(eastTurn2View.seats[0].hasDrawnTile, "East has a tile just taken from the wall")
        assertTrue(eastTurn2View.canDiscardDrawnTile, "East's second discard is allowed to discard drawn tile")
    }

    @Test
    fun ambiguousPanelProjectionsCountButtonsNotDiscards() {
        fun choices(vararg picks: Choice): ChoiceList =
            ChoiceList().apply { picks.forEach { pushBack(it) } }

        // A self-drawn win beside a rack of discards: discards are hand hits, not
        // panel buttons, so the lone special is the unambiguous action.
        val opening = GameState.fromWallOrder(canonicalWall(Random(123)), eastSeat = 0)
        val selfDrawHu = TableViewState.fromGameState(
            opening,
            humanSeat = 0,
            choices = choices(Choice.hu(40), Choice.discard(Tiles.BAMBOO_1)),
        )
        assertTrue(selfDrawHu.isHumanActOnHandPanel)
        assertEquals(Choice.hu(40), selfDrawHu.unambiguousClaim)
        assertNull(selfDrawHu.nonHuDecline, "a self-drawn win has no decline button")

        // A plain discard turn has no special button at all.
        val plain = TableViewState.fromGameState(opening, humanSeat = 0)
        assertTrue(plain.isHumanDiscardTurn)
        assertNull(plain.unambiguousClaim)

        // Concealed-kong gate: Kong is the unambiguous affirmative action, and Keep is the Space-declinable choice.
        val kongGate = TableViewState.fromGameState(
            opening,
            humanSeat = 0,
            choices = choices(Choice.concealedKong(3), Choice.keep(3)),
        )
        assertTrue(kongGate.isHumanActOnHandPanel)
        assertEquals(Choice.concealedKong(3), kongGate.unambiguousClaim)
        assertEquals(Choice.keep(3), kongGate.nonHuDecline)

        // Multiple affirmative actions (e.g. self-draw Hu alongside Concealed Kong) remain ambiguous.
        val kongWithHu = TableViewState.fromGameState(
            opening,
            humanSeat = 0,
            choices = choices(Choice.hu(40), Choice.concealedKong(3), Choice.keep(3)),
        )
        assertNull(kongWithHu.unambiguousClaim)
        assertNull(kongWithHu.nonHuDecline, "Hu presence suppresses Space decline")

        // Reaction windows after a discard.
        val reacting = GameState.fromWallOrder(canonicalWall(Random(123)), eastSeat = 0)
        reacting.applyChoice(reacting.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard })
        val human = reacting.currentPlayer
        fun reaction(picks: ChoiceList): TableViewState =
            TableViewState.fromGameState(reacting, humanSeat = human, choices = picks)

        val pung = reaction(choices(Choice.pass(), Choice.pung(3)))
        assertTrue(pung.isHumanClaimTurn)
        assertEquals(Choice.pung(3), pung.unambiguousClaim)
        assertEquals(Choice.pass(), pung.nonHuDecline)

        // A Hu is still the one unambiguous claim, but never Space-declinable.
        val hu = reaction(choices(Choice.pass(), Choice.hu(40)))
        assertEquals(Choice.hu(40), hu.unambiguousClaim)
        assertNull(hu.nonHuDecline)

        // Two claims to choose between: not unambiguous, but still Space-declinable.
        val two = reaction(choices(Choice.pass(), Choice.pung(3), Choice.chow(1)))
        assertNull(two.unambiguousClaim)
        assertEquals(Choice.pass(), two.nonHuDecline)

        // Pass-only reactions are not human decisions at all.
        val trivial = reaction(choices(Choice.pass()))
        assertFalse(trivial.isHumanClaimTurn)
        assertNull(trivial.unambiguousClaim)
        assertNull(trivial.nonHuDecline)
    }
}
