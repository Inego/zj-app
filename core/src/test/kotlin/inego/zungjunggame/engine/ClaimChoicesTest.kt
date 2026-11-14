package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * React-to-discard enumeration: multiple chow shapes and claim kinds.
 */
class ClaimChoicesTest {
    @Test
    fun multipleChowShapesAreSeparateChoices() {
        // Human (seat 0) is next after discarder 3 and can form three chows on a 5-man.
        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 3
        state.lastDiscarded = Tiles.CHAR_5
        state.liveWallSize = 40

        val hand = state.players[0]
        // 3,4 → chow 3-4-5; 4,6 → 4-5-6; 6,7 → 5-6-7
        hand.concealed[Tiles.CHAR_3] = 1
        hand.concealed[Tiles.CHAR_4] = 1
        hand.concealed[Tiles.CHAR_6] = 1
        hand.concealed[Tiles.CHAR_7] = 1
        // Fill to a plausible size (not required for enumerate, but avoids empty-hand weirdness).
        hand.concealed[Tiles.DOT_1] = 2
        hand.concealed[Tiles.DOT_2] = 2
        hand.concealed[Tiles.DOT_3] = 2
        hand.concealed[Tiles.BAMBOO_1] = 2
        hand.activeConcealedTileCount = -1

        val choices = state.enumerateChoices().asList()
        val chows = choices.filter { it.kind == ChoiceKind.Chow }.map { it.chowFirstTile() }.sorted()
        assertEquals(listOf(Tiles.CHAR_3, Tiles.CHAR_4, Tiles.CHAR_5), chows)
        assertTrue(choices.any { it.kind == ChoiceKind.Pass })
    }

    @Test
    fun pungAndKongOfferedTogetherWhenHoldingThree() {
        val state = GameState()
        state.currentPlayer = 1
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40
        state.players[1].concealed[Tiles.EAST] = 3
        state.players[1].activeConcealedTileCount = -1

        val kinds = state.enumerateChoices().asList().map { it.kind }.toSet()
        assertTrue(ChoiceKind.Pass in kinds)
        assertTrue(ChoiceKind.Pung in kinds)
        assertTrue(ChoiceKind.Kong in kinds)
        assertTrue(ChoiceKind.Chow !in kinds) // honors cannot chow
    }

    @Test
    fun openPungRecordsCallSourceSeat() {
        val state = GameState()
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40
        state.currentPlayer = 1
        state.players[2].concealed[Tiles.EAST] = 2
        state.players[2].activeConcealedTileCount = -1

        // Seat 1 passes, seat 2 pungs, seat 3 passes.
        state.applyChoice(Choice.pass())
        assertEquals(2, state.currentPlayer)
        state.applyChoice(Choice.pung(Tiles.EAST))
        assertEquals(3, state.currentPlayer)
        state.applyChoice(Choice.pass())

        assertEquals(PlayerState.PK_OPEN_PUNG, state.players[2].pungsKongs[Tiles.EAST])
        assertEquals(0, state.players[2].pungKongCalledFrom[Tiles.EAST])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(2, state.currentPlayer)
    }

    @Test
    fun afterChowHuIsNotOfferedEvenIfHandIsComplete() {
        // Seat 0 can Hu or Chow on 3-man into a complete regular hand.
        // Choosing Chow must leave only discards (no self-draw Hu popup).
        val state = GameState()
        state.eastSeat = 0
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 3
        state.lastDiscarded = Tiles.CHAR_3
        state.lastDrawnTile = -1
        state.lastDrawnPlayer = -1
        state.liveWallSize = 40
        state.currentPlayer = 0

        val hand = state.players[0]
        // Waiting shape: 12m + 111p + 222p + 333s + 99s
        hand.concealed[Tiles.CHAR_1] = 1
        hand.concealed[Tiles.CHAR_2] = 1
        hand.concealed[Tiles.DOT_1] = 3
        hand.concealed[Tiles.DOT_2] = 3
        hand.concealed[Tiles.BAMBOO_3] = 3
        hand.concealed[Tiles.BAMBOO_9] = 2
        hand.activeConcealedTileCount = -1

        val reactionChoices = state.enumerateChoices().asList()
        assertTrue(reactionChoices.any { it.kind == ChoiceKind.Hu }, "expected Hu on discard")
        assertTrue(
            reactionChoices.any { it.kind == ChoiceKind.Chow && it.chowFirstTile() == Tiles.CHAR_1 },
            "expected chow 1-2-3",
        )

        state.applyChoice(Choice.chow(Tiles.CHAR_1))
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertTrue(!state.canDeclareSelfDrawHu())

        val afterChow = state.enumerateChoices().asList()
        assertTrue(afterChow.none { it.kind == ChoiceKind.Hu }, "no Hu after chow")
        assertTrue(afterChow.any { it.kind == ChoiceKind.Discard }, "must discard after chow")
    }

    @Test
    fun afterPungHuIsNotOfferedEvenIfHandIsComplete() {
        val state = GameState()
        state.eastSeat = 0
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.lastDrawnTile = -1
        state.lastDrawnPlayer = -1
        state.liveWallSize = 40
        state.currentPlayer = 1

        val hand = state.players[1]
        // Pair of East + complete sets; claim East → open pung, still complete 14.
        hand.concealed[Tiles.EAST] = 2
        hand.concealed[Tiles.DOT_1] = 3
        hand.concealed[Tiles.DOT_2] = 3
        hand.concealed[Tiles.BAMBOO_3] = 3
        hand.concealed[Tiles.BAMBOO_9] = 2
        hand.activeConcealedTileCount = -1

        assertTrue(state.enumerateChoices().asList().any { it.kind == ChoiceKind.Hu })
        assertTrue(state.enumerateChoices().asList().any { it.kind == ChoiceKind.Pung })

        // Seat 1 pungs; seats 2 and 3 pass.
        state.applyChoice(Choice.pung(Tiles.EAST))
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(1, state.currentPlayer)
        val afterPung = state.enumerateChoices().asList()
        assertTrue(afterPung.none { it.kind == ChoiceKind.Hu }, "no Hu after pung")
        assertTrue(afterPung.any { it.kind == ChoiceKind.Discard })
    }
}
