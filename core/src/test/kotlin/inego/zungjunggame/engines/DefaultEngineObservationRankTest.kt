package inego.zungjunggame.engines

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.online.toSeatObservation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultEngineObservationRankTest {

    @Test
    fun discardOnlyChoicesMatchGameStateRanking() {
        val engine = DefaultEngine()
        for (seed in 1..20) {
            val state = GameState.generateRandom(Random(seed))
            val human = state.currentPlayer
            val choices = state.enumerateChoices()
            // Filter to discard only
            val discardChoices = ChoiceList()
            for (element in choices) {
                if (element.kind == ChoiceKind.Discard) {
                    discardChoices.pushBack(element)
                }
            }
            if (discardChoices.isEmpty()) continue

            val obs = TableViewState.fromGameState(state, human).toSeatObservation()
            assertNotNull(obs)

            // Rank with state (on discard-only list)
            val stateBest = engine.rank(state, discardChoices)
            // Rank with obs
            val obsBest = engine.rank(obs, DecisionType.ActOnHand, discardChoices)

            assertContentEquals(stateBest, obsBest, "Seed $seed discard ranking must match")
        }
    }

    @Test
    fun reactionChoicesMatchGameStateRanking() {
        val engine = DefaultEngine()
        for (seed in 1..20) {
            val state = GameState.generateRandom(Random(seed))
            // Step 1: discard a tile from player 0
            val p0Choices = state.enumerateChoices()
            val discard = p0Choices.asList().firstOrNull { it.kind == ChoiceKind.Discard } ?: continue
            state.applyChoice(discard)

            if (state.decisionType == DecisionType.ReactToDiscard) {
                val actor = state.currentPlayer
                val choices = state.enumerateChoices()
                // If there are only reaction choices without kongs (Chow, Pung, Pass):
                val nonKongChoices = ChoiceList()
                for (element in choices) {
                    if (element.kind != ChoiceKind.Kong) {
                        nonKongChoices.pushBack(element)
                    }
                }
                if (nonKongChoices.isEmpty()) continue

                val obs = TableViewState.fromGameState(state, actor).toSeatObservation()
                assertNotNull(obs)

                val stateBest = engine.rank(state, nonKongChoices)
                val obsBest = engine.rank(obs, DecisionType.ReactToDiscard, nonKongChoices)

                assertContentEquals(stateBest, obsBest, "Seed $seed reaction ranking must match")
            }
        }
    }

    @Test
    fun kongGateReturnsKongNotKeep() {
        val engine = DefaultEngine()
        val state = GameState.generateRandom(Random(99))
        val obs = TableViewState.fromGameState(state, 0).toSeatObservation()!!

        val choices = ChoiceList()
        choices.pushBack(Choice(ChoiceKind.ConcealedKong, Tiles.EAST))
        choices.pushBack(Choice(ChoiceKind.Keep, 0))

        val best = engine.rank(obs, DecisionType.ActOnHand, choices)
        assertEquals(1, best.size)
        assertEquals(0, best[0]) // Chooses ConcealedKong at index 0, not Keep
    }

    @Test
    fun kongChoicesReturnsLegalIndexWithoutThrow() {
        val engine = DefaultEngine()
        val state = GameState.generateRandom(Random(101))
        val obs = TableViewState.fromGameState(state, 0).toSeatObservation()!!

        // Give 4 EAST tiles to player concealed
        obs.player.concealed[Tiles.EAST] = 4

        val choices = ChoiceList()
        choices.pushBack(Choice(ChoiceKind.Discard, Tiles.SOUTH))
        choices.pushBack(Choice(ChoiceKind.ConcealedKong, Tiles.EAST))
        choices.pushBack(Choice(ChoiceKind.Keep, 0))

        val best = engine.rank(obs, DecisionType.ActOnHand, choices)
        assertTrue(best.isNotEmpty())
        for (idx in best) {
            assertTrue(idx in 0 until choices.size)
        }
    }
}
