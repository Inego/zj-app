package inego.zungjunggame.engines

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.Deficiency
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState

/** Tunable knobs for [PairHunterEngine]. */
data class PairHunterConfig(
    /** Take every legal call even when it does not raise the meld count. */
    val eagerCalls: Boolean = true,
    /** Take every Concealed/Promote Kong immediately instead of discarding. */
    val clickKongs: Boolean = true,
    /** Value of a leftover adjacent 2-tile suited connector (0 = blind to them). */
    val adjacentBonus: Int = 12,
    /** Value of each pair after the first (0 = only the head pair is understood). */
    val extraPairBonus: Int = 0,
    /** Per-tile value of a loose honour (positive = hoard them). */
    val honourKeep: Int = 0,
    /** Per-tile value of a loose terminal (positive = hoard 1s/9s). */
    val terminalKeep: Int = 0,
) {
    companion object {
        fun defaults(): PairHunterConfig = PairHunterConfig()
    }
}

/**
 * Deliberately naive beginner policy. Every lever is a real novice habit:
 * - **Pair-and-meld tunnel vision.** A hand is read as complete triplets, then
 *   complete left-to-right sequences, then **one** pair. Extra pairs are just
 *   loose tiles, so the engine throws away shapes a stronger player would keep.
 * - **Loose connector awareness.** Adjacent 2-tile suited shapes (`45`, `78`)
 *   get a small bonus but no gap-1 (`46`) awareness; the weight is tunable.
 * - **Honour/terminal hoarding** (optional, off by default). When enabled, lone
 *   honours and terminals look "special" and are kept while middle tiles are
 *   discarded.
 * - **Shiny-button calling.** Any legal Chow or Pung is taken immediately,
 *   even when it exposes the hand early or breaks a useful shape; Kongs are
 *   clicked the moment they appear.
 * - Always takes a legal [ChoiceKind.Hu] (highest score).
 *
 * [rank] returns every co-best index deterministically; [select] (interface
 * default) breaks ties deterministically with no RNG.
 */
class PairHunterEngine(
    private val config: PairHunterConfig = PairHunterConfig.defaults(),
) : PlayingEngine {

    /** Local trial hand and scratch counts (not thread-safe; [clone] per worker). */
    private val trialHand = PlayerState()
    private val scratch = IntArray(NUM_TILE_TYPES)

    override fun name(): String = "pair-hunter"

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
                config = config,
            )
        }

        return rankActOnHand(
            hand = state.players[state.currentPlayer],
            choices = choices,
            trialHand = trialHand,
            scratch = scratch,
            config = config,
        )
    }

    override fun clone(): PlayingEngine = PairHunterEngine(config)

    companion object {
        private const val MELD_SCORE = 100
        private const val FIRST_PAIR_SCORE = 25

        /**
         * Pack `(totalMelds, score)` into a single `Long` to keep evaluation
         * allocation-free inside tight self-play loops.
         */
        private fun naiveEvaluate(
            hand: PlayerState,
            rem: IntArray,
            config: PairHunterConfig,
        ): Long {
            val c = hand.concealed
            for (t in 0 until NUM_TILE_TYPES) rem[t] = c[t]

            var concealedMelds = 0
            // 1. Complete triplets first (matched tiles are the only thing a
            // beginner sees reliably).
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

            // 3. Pairs. Exactly one pair is understood as the head; extra
            // pairs can be discounted or ignored.
            var pairs = 0
            for (t in 0 until NUM_TILE_TYPES) {
                if (rem[t] >= 2) {
                    rem[t] -= 2
                    pairs++
                }
            }
            val pairScore = if (pairs > 0) {
                FIRST_PAIR_SCORE + (pairs - 1) * config.extraPairBonus
            } else {
                0
            }

            // 4. Remaining adjacent 2-tile suited connectors (no gap-1
            // awareness). Discounted or ignored by novice variants.
            var adj = 0
            if (config.adjacentBonus > 0) {
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
            }

            // 5. Loose tiles: a novice may prefer keeping honours and
            // terminals over plain middle tiles.
            var keepScore = 0
            for (t in 0 until NUM_TILE_TYPES) {
                val n = rem[t]
                if (n <= 0) continue
                if (t >= 27) {
                    keepScore += config.honourKeep * n
                } else {
                    when (t % 9) {
                        0, 8 -> keepScore += config.terminalKeep * n
                        else -> Unit
                    }
                }
            }

            val score = totalMelds * MELD_SCORE +
                pairScore +
                adj * config.adjacentBonus +
                keepScore
            return (totalMelds.toLong() shl 32) or (score.toLong() and 0xFFFF_FFFFL)
        }

        private fun unpackScore(packed: Long): Int = packed.toInt()
        private fun unpackMelds(packed: Long): Int = (packed ushr 32).toInt()

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
            config: PairHunterConfig,
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

            val passMelds = unpackMelds(naiveEvaluate(hand, scratch, config))
            var bestScore = Int.MIN_VALUE
            val bestIndices = ArrayList<Int>()

            for ((i, element) in choices.withIndex()) {
                if (element.kind != ChoiceKind.Pung && element.kind != ChoiceKind.Chow) continue
                trialHand.copyFrom(hand)
                if (!applyCallLocally(trialHand, element, discarded)) continue

                val eval = naiveEvaluate(trialHand, scratch, config)
                if (!config.eagerCalls && unpackMelds(eval) <= passMelds) continue

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
            config: PairHunterConfig,
        ): Int {
            hand.ensureActiveConcealedTiles()
            var best = Int.MIN_VALUE
            for (idx in 0 until hand.activeConcealedTileCount) {
                val tile = hand.activeConcealedTiles[idx]
                if (hand.concealed[tile] <= 0) continue
                trialHand.copyFrom(hand)
                --trialHand.concealed[tile]
                trialHand.activeConcealedTileCount = -1
                val score = unpackScore(naiveEvaluate(trialHand, scratch, config))
                if (score > best) best = score
            }
            return best
        }

        private fun rankActOnHand(
            hand: PlayerState,
            choices: ChoiceList,
            trialHand: PlayerState,
            scratch: IntArray,
            config: PairHunterConfig,
        ): IntArray {
            // Shiny-button Kongs: if a Kong is on offer, take it instead of
            // ever considering the discard.
            if (config.clickKongs) {
                val kongs = ArrayList<Int>()
                for ((i, element) in choices.withIndex()) {
                    if (element.kind == ChoiceKind.ConcealedKong ||
                        element.kind == ChoiceKind.PromoteKong
                    ) {
                        kongs.add(i)
                    }
                }
                if (kongs.isNotEmpty()) return kongs.toIntArray()
            }

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
                        unpackScore(naiveEvaluate(trialHand, scratch, config))
                    }
                    ChoiceKind.Keep ->
                        bestDiscardScore(hand, trialHand, scratch, config)
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
