package inego.zungjunggame.engines

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.Deficiency
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState

/**
 * Beginner-style greedy meld policy:
 * - No global shanten / deficiency search: evaluates a hand with a single
 *   myopic pass (triplets first, then left-to-right sequences, then pairs,
 *   then adjacent 2-tile connectors). Naturally misses subtle overlapping
 *   shapes while never breaking an already-formed meld.
 * - Flat leftover value: no middle-tile preference, no gap-1 (`kanchan`)
 *   awareness, no honour/terminal dumping bias, no pattern or river awareness.
 * - Eager calling: takes any legal Chow or Pung that strictly increases total
 *   completed melds, exposing the hand early the way new players tend to do.
 * - Always takes a legal [ChoiceKind.Hu] (highest score).
 *
 * [rank] returns every co-best index deterministically; [select] (interface
 * default) RNG-breaks ties.
 */
class GreedyMeldEngine : PlayingEngine {

    /** Local trial hand and scratch counts (not thread-safe; [clone] per worker). */
    private val trialHand = PlayerState()
    private val scratch = IntArray(NUM_TILE_TYPES)

    override fun name(): String = "greedy-meld"

    override fun rank(state: GameState, choices: ChoiceList): IntArray {
        check(!choices.isEmpty())

        for ((kind) in choices) {
            if (kind == ChoiceKind.Hu) {
                return allBestHuIndices(choices)
            }
        }

        if (state.decisionType == DecisionType.ReactToDiscard) {
            return rankReact(
                hand = state.players[state.currentPlayer],
                discarded = state.lastDiscarded,
                choices = choices,
                trialHand = trialHand,
                scratch = scratch,
            )
        }

        return rankActOnHand(
            hand = state.players[state.currentPlayer],
            choices = choices,
            trialHand = trialHand,
            scratch = scratch,
        )
    }

    override fun clone(): PlayingEngine = GreedyMeldEngine()

    companion object {
        private const val MELD_SCORE = 100
        private const val FIRST_PAIR_SCORE = 25
        private const val EXTRA_PAIR_SCORE = 10
        private const val ADJACENT_SCORE = 12

        /**
         * Pack `(totalMelds, score)` into a single `Long` to keep evaluation
         * allocation-free inside tight self-play loops.
         */
        private fun greedyEvaluate(hand: PlayerState, rem: IntArray): Long {
            val c = hand.concealed
            for (t in 0 until NUM_TILE_TYPES) rem[t] = c[t]

            var concealedMelds = 0
            // 1. Complete triplets first (classic tunnel vision on overlapping shapes).
            for (t in 0 until NUM_TILE_TYPES) {
                if (rem[t] >= 3) {
                    rem[t] -= 3
                    concealedMelds++
                }
            }

            // 2. Complete sequences left-to-right per suit.
            for (suit in 0 until 3) {
                val base = suit * 9
                for (r in 0..6) {
                    val t = base + r
                    while (rem[t] > 0 && rem[t + 1] > 0 && rem[t + 2] > 0) {
                        rem[t]--
                        rem[t + 1]--
                        rem[t + 2]--
                        concealedMelds++
                    }
                }
            }

            val totalMelds = Deficiency.countOpenMelds(hand) + concealedMelds

            // 3. Remaining pairs.
            var pairs = 0
            for (t in 0 until NUM_TILE_TYPES) {
                if (rem[t] >= 2) {
                    rem[t] -= 2
                    pairs++
                }
            }

            // 4. Remaining adjacent 2-tile suited connectors (no gap-1 awareness).
            var adj = 0
            for (suit in 0 until 3) {
                val base = suit * 9
                for (r in 0..7) {
                    val t = base + r
                    while (rem[t] > 0 && rem[t + 1] > 0) {
                        rem[t]--
                        rem[t + 1]--
                        adj++
                    }
                }
            }

            val pairScore = if (pairs > 0) {
                FIRST_PAIR_SCORE + (pairs - 1) * EXTRA_PAIR_SCORE
            } else {
                0
            }
            val score = totalMelds * MELD_SCORE + pairScore + adj * ADJACENT_SCORE
            return (totalMelds.toLong() shl 32) or (score.toLong() and 0xFFFF_FFFFL)
        }

        private fun unpackMelds(packed: Long): Int = (packed ushr 32).toInt()
        private fun unpackScore(packed: Long): Int = packed.toInt()

        private fun allBestHuIndices(choices: ChoiceList): IntArray {
            val best = ArrayList<Int>()
            var bestScore = Int.MIN_VALUE
            for ((i, element) in choices.withIndex()) {
                if (element.kind != ChoiceKind.Hu) continue
                val score = element.huScore()
                if (score > bestScore) {
                    bestScore = score
                    best.clear()
                    best.add(i)
                } else if (score == bestScore) {
                    best.add(i)
                }
            }
            check(best.isNotEmpty())
            return best.toIntArray()
        }

        private fun applyCallLocally(hand: PlayerState, choice: Choice, discarded: Int): Boolean {
            when (choice.kind) {
                ChoiceKind.Pung -> {
                    val tile = choice.tile()
                    if (tile != discarded || hand.concealed[tile] < 2) return false
                    hand.concealed[tile] -= 2
                    hand.recordMeldExposure(
                        PlayerState.MELD_SET,
                        tile,
                        setState = PlayerState.PK_OPEN_PUNG,
                    )
                    hand.activeConcealedTileCount = -1
                    return true
                }
                ChoiceKind.Chow -> {
                    val first = choice.chowFirstTile()
                    val second = first + 1
                    val third = first + 2
                    if (discarded !in first..third || first < 0 || third >= 27) {
                        return false
                    }
                    if (first != discarded) {
                        if (hand.concealed[first] <= 0) return false
                        --hand.concealed[first]
                    }
                    if (second != discarded) {
                        if (hand.concealed[second] <= 0) return false
                        --hand.concealed[second]
                    }
                    if (third != discarded) {
                        if (hand.concealed[third] <= 0) return false
                        --hand.concealed[third]
                    }
                    val chowIndex = (first / 9) * 7 + (first % 9)
                    hand.recordMeldExposure(PlayerState.MELD_CHOW, chowIndex, claimTile = discarded)
                    hand.activeConcealedTileCount = -1
                    return true
                }
                else -> return false
            }
        }

        private fun rankReact(
            hand: PlayerState,
            discarded: Int,
            choices: ChoiceList,
            trialHand: PlayerState,
            scratch: IntArray,
        ): IntArray {
            var passIndex = 0
            var hasPass = false
            for ((i, element) in choices.withIndex()) {
                if (element.kind == ChoiceKind.Pass) {
                    hasPass = true
                    passIndex = i
                    break
                }
            }
            if (!hasPass) return intArrayOf(0)

            val passMelds = unpackMelds(greedyEvaluate(hand, scratch))
            var bestScore = Int.MIN_VALUE
            val bestIndices = ArrayList<Int>()

            for ((i, element) in choices.withIndex()) {
                if (element.kind != ChoiceKind.Pung && element.kind != ChoiceKind.Chow) continue
                trialHand.copyFrom(hand)
                if (!applyCallLocally(trialHand, element, discarded)) continue

                val eval = greedyEvaluate(trialHand, scratch)
                if (unpackMelds(eval) <= passMelds) continue

                val score = unpackScore(eval)
                if (score > bestScore) {
                    bestScore = score
                    bestIndices.clear()
                    bestIndices.add(i)
                } else if (score == bestScore) {
                    bestIndices.add(i)
                }
            }

            if (bestIndices.isEmpty()) return intArrayOf(passIndex)
            return bestIndices.toIntArray()
        }

        private fun bestDiscardScore(
            hand: PlayerState,
            trialHand: PlayerState,
            scratch: IntArray,
        ): Int {
            hand.ensureActiveConcealedTiles()
            var best = Int.MIN_VALUE
            for (idx in 0 until hand.activeConcealedTileCount) {
                val tile = hand.activeConcealedTiles[idx]
                if (hand.concealed[tile] <= 0) continue
                trialHand.copyFrom(hand)
                --trialHand.concealed[tile]
                trialHand.activeConcealedTileCount = -1
                val score = unpackScore(greedyEvaluate(trialHand, scratch))
                if (score > best) best = score
            }
            return best
        }

        private fun rankActOnHand(
            hand: PlayerState,
            choices: ChoiceList,
            trialHand: PlayerState,
            scratch: IntArray,
        ): IntArray {
            var bestScore = Int.MIN_VALUE
            val bestIndices = ArrayList<Int>()

            for ((i, element) in choices.withIndex()) {
                val score = when (element.kind) {
                    ChoiceKind.Discard -> {
                        val tile = element.tile()
                        check(tile in 0 until NUM_TILE_TYPES)
                        check(hand.concealed[tile] > 0)
                        trialHand.copyFrom(hand)
                        --trialHand.concealed[tile]
                        trialHand.activeConcealedTileCount = -1
                        unpackScore(greedyEvaluate(trialHand, scratch))
                    }
                    ChoiceKind.Keep -> bestDiscardScore(hand, trialHand, scratch)
                    // Pattern-blind beginner does not go out of their way to kong.
                    else -> continue
                }

                if (score > bestScore) {
                    bestScore = score
                    bestIndices.clear()
                    bestIndices.add(i)
                } else if (score == bestScore) {
                    bestIndices.add(i)
                }
            }

            if (bestIndices.isEmpty()) return intArrayOf(0)
            return bestIndices.toIntArray()
        }
    }
}
