package inego.zungjunggame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GameState.fromLayout] / [GameState.hydrateFrom] — mid-hand restore for tutorials.
 */
class FromLayoutTest {

    @Test
    fun hydrateFromDoesNotAliasCallerBuffers() {
        val original = GameState.fromWallOrder(canonicalWall(Random(5)), eastSeat = 0)
        val layout = GameStateLayout.from(original)
        val state = GameState.fromLayout(layout)
        // Mutating the layout's wall must not affect the hydrated state (layout copies;
        // hydrate copies again into GameState arrays).
        layout.wall[0] = (layout.wall[0] + 1) % NUM_TILE_TYPES
        assertEquals(original.wall[0], state.wall[0])
    }

    @Test
    fun craftedReactLayoutOffersPung() {
        // Build a legal multiset mid-hand where seat 1 can pung seat 0's East discard.
        val wall = IntArray(WALL_SIZE) { it / 4 }
        // Put two EAST into seat 1's "would-be" dealt region is hard; instead:
        // start from a valid opening deal, force a known reaction by hand-editing a copy
        // is multiset-fragile. Use play-then-capture for the wall, then rebuild players
        // only when multiset stays valid — here: open deal, move tiles carefully.

        val base = GameState.fromWallOrder(wall, eastSeat = 0)
        // East (0) discards a tile that seat 1 holds two of, if any such exists.
        val eastHand = base.players[0]
        var target: Int? = null
        for (tile in 0 until NUM_TILE_TYPES) {
            if (eastHand.concealed[tile] > 0 && base.players[1].concealed[tile] >= 2) {
                target = tile
                break
            }
        }
        if (target == null) {
            // Fallback: still exercise fromLayout on post-discard state without pung.
            val discard = base.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
            base.applyChoice(discard)
            val restored = GameState.fromLayout(GameStateLayout.from(base))
            assertEquals(DecisionType.ReactToDiscard, restored.decisionType)
            assertTrue(restored.enumerateChoices().asList().any { it.kind == ChoiceKind.Pass })
            return
        }

        base.applyChoice(Choice.discard(target))
        assertEquals(DecisionType.ReactToDiscard, base.decisionType)
        // Advance to seat 1 if needed (seat order after discarder 0: 1,2,3).
        while (base.currentPlayer != 1 && base.decisionType == DecisionType.ReactToDiscard) {
            base.applyChoice(Choice.pass())
        }
        assertEquals(1, base.currentPlayer)
        val kinds = base.enumerateChoices().asList().map { it.kind }.toSet()
        assertTrue(ChoiceKind.Pung in kinds)

        val restored = GameState.fromLayout(GameStateLayout.from(base))
        val restoredKinds = restored.enumerateChoices().asList().map { it.kind }.toSet()
        assertTrue(ChoiceKind.Pung in restoredKinds)
        assertTrue(ChoiceKind.Pass in restoredKinds)

        restored.applyChoice(Choice.pung(target))
        // Remaining reactors pass.
        while (restored.decisionType == DecisionType.ReactToDiscard) {
            restored.applyChoice(Choice.pass())
        }
        assertEquals(DecisionType.ActOnHand, restored.decisionType)
        assertEquals(1, restored.currentPlayer)
        assertEquals(PlayerState.PK_OPEN_PUNG, restored.players[1].pungsKongs[target])
        assertEquals(0, restored.players[1].pungKongCalledFrom[target])
    }

    @Test
    fun rejectsBadLiveWallSize() {
        val good = GameStateLayout.from(GameState.fromWallOrder(canonicalWall(Random(1))))
        assertFailsWith<IllegalArgumentException> {
            GameState.fromLayout(
                GameStateLayout(
                    wall = good.wall,
                    liveWallSize = LIVE_WALL_DRAWABLE_SIZE + 1,
                    players = good.players,
                    unknownTileCountsByPlayer = good.unknownTileCountsByPlayer,
                    handRevisions = good.handRevisions,
                    discards = good.discards,
                    eastSeat = good.eastSeat,
                    currentPlayer = good.currentPlayer,
                    decisionType = good.decisionType,
                    allowChickenHands = good.allowChickenHands,
                ),
            )
        }
    }

    @Test
    fun rejectsReactWithoutDiscarder() {
        val good = GameStateLayout.from(GameState.fromWallOrder(canonicalWall(Random(3))))
        assertFailsWith<IllegalArgumentException> {
            GameState.fromLayout(
                GameStateLayout(
                    wall = good.wall,
                    liveWallSize = good.liveWallSize,
                    players = good.players,
                    unknownTileCountsByPlayer = good.unknownTileCountsByPlayer,
                    handRevisions = good.handRevisions,
                    discards = good.discards,
                    lastDiscarder = -1,
                    lastDiscarded = Tiles.EAST,
                    eastSeat = good.eastSeat,
                    currentPlayer = 1,
                    decisionType = DecisionType.ReactToDiscard,
                ),
            )
        }
    }

    private fun canonicalWall(rng: Random): IntArray {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        for (i in WALL_SIZE - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}
