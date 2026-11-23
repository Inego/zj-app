package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.ChoiceGate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [inego.zungjunggame.session.board.BoardController] + [inego.zungjunggame.session.board.ChoiceGate] — single apply path and human gate seam.
 */
class BoardControllerTest {

    @Test
    fun identityGateAllowsDiscardAndApplies() {
        val state = GameState.fromWallOrder(canonicalWall(Random(7)), eastSeat = 0)
        val board = BoardController()
        assertEquals(ChoiceGate.Identity, board.gate)

        val discards = board.allowedChoices(state).filter { it.kind == ChoiceKind.Discard }
        assertTrue(discards.isNotEmpty())
        val tile = discards.first().tile()
        assertTrue(board.tryApplyDiscard(state, tile))
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        assertEquals(tile, state.lastDiscarded)
    }

    @Test
    fun restrictiveGateBlocksDiscardsAllowsPass() {
        // Build a reaction window where seat 1 can pass or claim.
        val state = GameState.fromWallOrder(canonicalWall(Random(21)), eastSeat = 0)
        val open = BoardController()
        val firstDiscard = open.allowedChoices(state).first { it.kind == ChoiceKind.Discard }
        assertTrue(open.tryApply(state, firstDiscard))
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        val board = BoardController(
            gate = { legal -> legal.filter { it.kind == ChoiceKind.Pass } },
        )
        val allowed = board.allowedChoices(state)
        assertTrue(allowed.isNotEmpty())
        assertTrue(allowed.all { it.kind == ChoiceKind.Pass })

        // A non-pass legal claim (if any) must not apply through the gate.
        val legal = board.legalChoices(state)
        val nonPass = legal.firstOrNull { it.kind != ChoiceKind.Pass }
        if (nonPass != null) {
            assertFalse(board.tryApply(state, nonPass))
            assertEquals(DecisionType.ReactToDiscard, state.decisionType)
        }

        assertTrue(board.tryApply(state, Choice.pass()))
        // After one reactor passes, still mid-window or advanced — either way applied.
        assertTrue(state.pendingReactionCount >= 1 || state.decisionType == DecisionType.ActOnHand)
    }

    @Test
    fun tryApplyUsesEngineChoiceNotCallerCopy() {
        val state = GameState.fromWallOrder(canonicalWall(Random(3)), eastSeat = 0)
        val board = BoardController()
        val legal = board.legalChoices(state)
        val discard = legal.first { it.kind == ChoiceKind.Discard }
        // Caller builds a new Choice with same kind/payload — still matches.
        val copy = Choice.discard(discard.tile())
        assertTrue(board.tryApply(state, copy))
        assertEquals(discard.tile(), state.lastDiscarded)
    }

    private fun canonicalWall(rng: Random): IntArray {
        val wall = IntArray(inego.zungjunggame.engine.WALL_SIZE) { it / 4 }
        for (i in wall.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}
