package inego.zungjunggame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReactionTest {

    @Test
    fun enumerateReactionsForSeatsWithoutMutatingCurrentPlayer() {
        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 1
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40

        // Seat 1 holds 2 Easts (can Pung)
        state.players[1].concealed[Tiles.EAST] = 2
        state.players[1].activeConcealedTileCount = -1

        // Seat 2 holds 3 Easts (can Pung and Kong)
        state.players[2].concealed[Tiles.EAST] = 3
        state.players[2].activeConcealedTileCount = -1

        // Seat 3 holds no Easts and cannot chow (seat 1 is next after discarder 0)
        state.players[3].activeConcealedTileCount = -1

        val context = ReactionContext.fromState(state)!!

        // Enumerate seat 1
        val c1 = enumerateReactions(state, context, 1).asList()
        assertTrue(c1.any { it.kind == ChoiceKind.Pung })
        assertEquals(1, state.currentPlayer, "currentPlayer must not be mutated by enumerateReactions")

        // Enumerate seat 2
        val c2 = enumerateReactions(state, context, 2).asList()
        assertTrue(c2.any { it.kind == ChoiceKind.Kong })
        assertTrue(c2.any { it.kind == ChoiceKind.Pung })
        assertEquals(1, state.currentPlayer, "currentPlayer must not be mutated by enumerateReactions")

        // Enumerate seat 3 (pass-only)
        val c3 = enumerateReactions(state, context, 3).asList()
        assertEquals(1, c3.size)
        assertEquals(ChoiceKind.Pass, c3[0].kind)
        assertEquals(1, state.currentPlayer, "currentPlayer must not be mutated by enumerateReactions")
    }

    @Test
    fun competingHuResolverIndependentOfMapOrder() {
        val discarder = 0
        val hu1 = Choice.hu(30)
        val hu3 = Choice.hu(50)

        // Map A: seat 1 inserted before seat 3
        val mapA = linkedMapOf(
            1 to hu1,
            2 to Choice.pass(),
            3 to hu3,
        )
        // Map B: seat 3 inserted before seat 1
        val mapB = linkedMapOf(
            3 to hu3,
            2 to Choice.pass(),
            1 to hu1,
        )

        val resA = resolveReactions(discarder, mapA)
        val resB = resolveReactions(discarder, mapB)

        assertIs<ReactionResolution.Winning>(resA)
        assertIs<ReactionResolution.Winning>(resB)
        // Seating order after discarder 0 is: 1, 2, 3. Seat 1 takes priority over seat 3.
        assertEquals(1, resA.seat)
        assertEquals(hu1, resA.choice)
        assertEquals(1, resB.seat)
        assertEquals(hu1, resB.choice)
    }

    @Test
    fun equalRankPungResolverIndependentOfMapOrder() {
        val discarder = 0
        val pung2 = Choice.pung(Tiles.EAST)
        val pung3 = Choice.pung(Tiles.EAST)

        val mapA = linkedMapOf(
            2 to pung2,
            3 to pung3,
            1 to Choice.pass(),
        )
        val mapB = linkedMapOf(
            3 to pung3,
            2 to pung2,
            1 to Choice.pass(),
        )

        val resA = resolveReactions(discarder, mapA)
        val resB = resolveReactions(discarder, mapB)

        assertIs<ReactionResolution.Winning>(resA)
        assertIs<ReactionResolution.Winning>(resB)
        // Seating order after 0 is 1, 2, 3. Seat 2 has priority over seat 3.
        assertEquals(2, resA.seat)
        assertEquals(2, resB.seat)
    }

    @Test
    fun bidArrivalPermutationsMatchSequentialResult() {
        val discarder = 0
        val pass1 = Choice.pass()
        val hu2 = Choice.hu(20)
        val hu3 = Choice.hu(40)

        val bids = mapOf(
            1 to pass1,
            2 to hu2,
            3 to hu3,
        )

        // Check all 6 arrival permutations for resolveReactions
        val permutations = listOf(
            listOf(1, 2, 3),
            listOf(1, 3, 2),
            listOf(2, 1, 3),
            listOf(2, 3, 1),
            listOf(3, 1, 2),
            listOf(3, 2, 1),
        )

        for (perm in permutations) {
            val map = LinkedHashMap<Int, Choice>()
            for (seat in perm) {
                map[seat] = bids[seat]!!
            }
            val res = resolveReactions(discarder, map)
            assertIs<ReactionResolution.Winning>(res)
            assertEquals(2, res.seat, "Permutation $perm should resolve to seat 2")
            assertEquals(hu2, res.choice)
        }

        // Sequential collection with applyChoice (visits 1, 2, 3 in order)
        val state = GameState()
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = discarder
        state.lastDiscarded = Tiles.DOT_5
        state.currentPlayer = 1

        state.applyChoice(pass1)
        assertEquals(2, state.currentPlayer)
        state.applyChoice(hu2)
        assertEquals(3, state.currentPlayer)
        state.applyChoice(hu3)

        // After all three bids submitted sequentially, seat 2 won Hu
        assertTrue(state.isTerminal())
        assertEquals(2, state.winnerSeat)
        assertEquals(2, state.currentPlayer)
        assertEquals(20, state.handScore)
    }

    @Test
    fun kongRobWindowNobodyCanHuSkipsWindow() {
        val state = setupPromoterState(robberCanHu = false)
        val liveBefore = state.liveWallSize

        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))

        // Nobody could rob: the promote immediately completes without entering ReactToDiscard
        assertFalse(state.kongRobWindow)
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertEquals(PlayerState.PK_OPEN_KONG, state.players[0].pungsKongs[Tiles.DOT_5])
        assertTrue(state.lastDrawWasKongSupplement)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun kongRobWindowAllPassCompletesPromotedKong() {
        val state = setupPromoterState(robberCanHu = true)
        val liveBefore = state.liveWallSize

        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))

        // Rob window is open
        assertTrue(state.kongRobWindow)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        assertEquals(1, state.currentPlayer)
        assertEquals(PlayerState.PK_OPEN_PUNG, state.players[0].pungsKongs[Tiles.DOT_5])

        // All seats pass
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        // Kong completed after all-pass
        assertFalse(state.kongRobWindow)
        assertEquals(PlayerState.PK_OPEN_KONG, state.players[0].pungsKongs[Tiles.DOT_5])
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0, state.currentPlayer)
        assertTrue(state.lastDrawWasKongSupplement)
        assertEquals(liveBefore - 1, state.liveWallSize)
    }

    @Test
    fun kongRobWindowHuLeavesPungIntactAndScoresRobbery() {
        val state = setupPromoterState(robberCanHu = true)

        state.applyChoice(Choice.promoteKong(Tiles.DOT_5))
        assertTrue(state.kongRobWindow)

        val huChoice = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Hu }
        state.applyChoice(huChoice)
        state.applyChoice(Choice.pass())
        state.applyChoice(Choice.pass())

        assertTrue(state.isTerminal())
        assertEquals(1, state.winnerSeat)
        assertEquals(0, state.winSource)
        assertEquals(1, state.currentPlayer)

        // Promoter's pung remains OPEN_PUNG (the fourth tile was robbed)
        assertEquals(PlayerState.PK_OPEN_PUNG, state.players[0].pungsKongs[Tiles.DOT_5])

        // Scoring includes 9.3 Robbing a Kong
        val details = Scoring.computeHandScoreDetails(state)
        assertTrue(details.components.any { it.id == PatternIds.ROBBING_A_KONG && it.points == 10 })

        // Settlement: seat 0 (promoter/discarder) pays discard-source liability
        val deltas = state.computeScoreDeltas()
        assertEquals(details.totalScore * 3, deltas[1])
        assertTrue(deltas[0] < 0)
        assertEquals(0, deltas[0] + deltas[1] + deltas[2] + deltas[3])
    }

    private fun setupPromoterState(robberCanHu: Boolean): GameState {
        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        state.allowChickenHands = false
        state.liveWallSize = 40
        state.lastDrawnPlayer = 0
        state.lastDrawnTile = Tiles.DOT_5
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - state.liveWallSize
        state.wall[drawIndex] = Tiles.CHAR_9

        val promoter = state.players[0]
        promoter.pungsKongs[Tiles.DOT_5] = PlayerState.PK_OPEN_PUNG
        promoter.pungKongCalledFrom[Tiles.DOT_5] = 2
        promoter.concealed[Tiles.DOT_5] = 1
        promoter.concealed[Tiles.CHAR_1] = 2
        promoter.concealed[Tiles.CHAR_2] = 2
        promoter.concealed[Tiles.CHAR_3] = 2
        promoter.concealed[Tiles.BAMBOO_8] = 2
        promoter.concealed[Tiles.WHITE] = 2
        promoter.activeConcealedTileCount = -1

        val robber = state.players[1]
        if (robberCanHu) {
            // 111p 222p 333s 444s pair wait 5p (all triplets on 5p)
            robber.concealed[Tiles.DOT_1] = 3
            robber.concealed[Tiles.DOT_2] = 3
            robber.concealed[Tiles.BAMBOO_3] = 3
            robber.concealed[Tiles.BAMBOO_4] = 3
            robber.concealed[Tiles.DOT_5] = 1
        } else {
            robber.concealed[Tiles.CHAR_1] = 1
            robber.concealed[Tiles.CHAR_9] = 1
            robber.concealed[Tiles.DOT_1] = 1
            robber.concealed[Tiles.DOT_9] = 1
            robber.concealed[Tiles.BAMBOO_1] = 1
            robber.concealed[Tiles.BAMBOO_9] = 1
            robber.concealed[Tiles.EAST] = 1
            robber.concealed[Tiles.SOUTH] = 1
            robber.concealed[Tiles.WEST] = 1
            robber.concealed[Tiles.NORTH] = 1
            robber.concealed[Tiles.RED] = 1
            robber.concealed[Tiles.GREEN] = 1
            robber.concealed[Tiles.WHITE] = 1
        }
        robber.activeConcealedTileCount = -1
        return state
    }
}
