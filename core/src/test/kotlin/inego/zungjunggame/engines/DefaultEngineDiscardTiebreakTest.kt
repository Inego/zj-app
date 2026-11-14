package inego.zungjunggame.engines

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Isolated honor singles with equal def/acceptance should still prefer discarding
 * the tile with fewer remaining copies (e.g. R already discarded by someone).
 */
class DefaultEngineDiscardTiebreakTest {
    @Test
    fun prefersDiscardingHonorWithFewerRemaining() {
        // Hand where discarding S, R, or W is equally good on def+acceptance
        // alone (isolated honors; 1→2 does not improve deficiency). With one
        // Red already out, only Red should remain co-best among R/W.
        val tiles = intArrayOf(
            Tiles.CHAR_2, Tiles.CHAR_2, Tiles.CHAR_4, Tiles.CHAR_6, Tiles.CHAR_8,
            Tiles.DOT_2, Tiles.DOT_2, Tiles.DOT_4, Tiles.DOT_6,
            Tiles.BAMBOO_2, Tiles.BAMBOO_4,
            Tiles.SOUTH,
            Tiles.RED, Tiles.WHITE,
        )

        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        state.liveWallSize = 70

        val hand = state.players[0]
        for (t in tiles) hand.concealed[t]++
        hand.activeConcealedTileCount = -1

        for (p in 0 until 4) {
            for (t in 0 until NUM_TILE_TYPES) {
                state.unknownTileCountsByPlayer[p][t] = 4
            }
        }
        for (t in 0 until NUM_TILE_TYPES) {
            state.unknownTileCountsByPlayer[0][t] -= hand.concealed[t]
        }
        // One Red already discarded by another player
        state.unknownTileCountsByPlayer[0][Tiles.RED] -= 1
        state.discards[1].add(Tiles.RED)

        val engine = DefaultEngine()
        val choices = state.enumerateChoices()
        val ranked = engine.rank(state, choices)
        val rankedTiles = ranked
            .filter { choices[it].kind == ChoiceKind.Discard }
            .map { choices[it].tile() }
            .toSet()

        assertTrue(
            Tiles.RED in rankedTiles,
            "Red (scarcer) should be among co-best discards; ranked=$rankedTiles",
        )
        assertFalse(
            Tiles.WHITE in rankedTiles,
            "White (more remaining) should not tie Red; ranked=$rankedTiles",
        )
    }

    @Test
    fun equalRemainingHonorsStillCoBest() {
        val tiles = intArrayOf(
            Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3, Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6,
            Tiles.DOT_1, Tiles.DOT_2, Tiles.DOT_3, Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
            Tiles.RED, Tiles.WHITE,
        )

        val state = GameState()
        state.eastSeat = 0
        state.currentPlayer = 0
        state.decisionType = DecisionType.ActOnHand
        state.liveWallSize = 70

        val hand = state.players[0]
        for (t in tiles) hand.concealed[t]++
        hand.activeConcealedTileCount = -1

        for (p in 0 until 4) {
            for (t in 0 until NUM_TILE_TYPES) {
                state.unknownTileCountsByPlayer[p][t] = 4
            }
        }
        for (t in 0 until NUM_TILE_TYPES) {
            state.unknownTileCountsByPlayer[0][t] -= hand.concealed[t]
        }

        val engine = DefaultEngine()
        val choices = state.enumerateChoices()
        val ranked = engine.rank(state, choices)
        val rankedTiles = ranked
            .filter { choices[it].kind == ChoiceKind.Discard }
            .map { choices[it].tile() }
            .toSet()

        // Ready on the other dragon either way; equal remaining → both co-best.
        assertTrue(Tiles.RED in rankedTiles && Tiles.WHITE in rankedTiles, "ranked=$rankedTiles")
    }
}
