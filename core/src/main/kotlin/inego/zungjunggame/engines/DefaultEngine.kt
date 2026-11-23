package inego.zungjunggame.engines

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.Deficiency
import inego.zungjunggame.engine.DeficiencyFast
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_CHOW_TYPES
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import kotlin.math.max
import kotlin.math.min

/** Tunable knobs for the default playing engine (local call eval + ZJ pattern floor). */
data class DefaultEngineConfig(
    /** Closed + early wall: require this deficiency gain vs Pass to call. */
    val minDefGainToOpen: Int = 1,
    /** live_wall_size <= this → free call policy (race mode). */
    val lateWallLiveTiles: Int = 12,
    /**
     * utility = acceptanceWeight * acceptance
     *         + patternWeight * patternValue
     *         + V_shape
     */
    val acceptanceWeight: Int = 1,
    val suitWeight: Int = 6,
    val offsuitPenalty: Int = 6,
    val honorWeight: Int = 4,
    /**
     * Weight on patternValue = strictLockedL + closedHandCredit.
     * Strict L: open/declared-only pattern points with series max
     * (3.1, 3.2.2, 3.3.2/4, 4.2 floor from concealed kongs, 4.3, 5.1.x,
     * 6.1, 6.2.2, 7.1, 7.2.x). Concealed Hand 5 is eligibility while closed.
     */
    val patternWeight: Int = 1,
    /**
     * When closed + early wall: allow equal-def calls that bank at least one
     * new value-honor set (+10 on 3.1). Default on.
     */
    val allowValueHonorOpenOnTie: Boolean = true,
) {
    fun valueBiasEnabled(): Boolean =
        suitWeight != 0 || offsuitPenalty != 0 || honorWeight != 0

    companion object {
        const val VALUE_HONOR_POINTS = 10
        const val CONCEALED_HAND_POINTS = 5

        fun defaults(): DefaultEngineConfig = DefaultEngineConfig()
    }
}

/** Strict L floor: open melds + declared kongs only (series-aware). For tests/tooling. */
fun strictLockedPatternPoints(ps: PlayerState, seatWind: Int = Tiles.EAST): Int =
    DefaultEngine.strictLockedPatternPoints(ps, seatWind)

/** Strict L + Concealed Hand eligibility credit. For tests/tooling. */
fun patternFloorPoints(ps: PlayerState, seatWind: Int = Tiles.EAST): Int =
    DefaultEngine.patternFloorPoints(ps, seatWind)

/**
 * Default playing policy for table AI, auto-play, and hints:
 * - Local post-meld [PlayerState] for claim evaluation (not multi-seat apply)
 * - ZJ Strict L floor from open melds + declared kongs (series-aware)
 * - Concealed Hand eligibility credit while closed
 * - Call gate, with equal-def value-honor open when enabled
 * - Shape bias among finalists
 *
 * [rank] returns every co-best index (deterministic). [select] (interface
 * default) breaks ties deterministically with no RNG. HINT uses [rank] only.
 *
 * Logic is kept in sync with the heuristic playing engines in zj-self-play;
 * class names here stay stable across upstream revisions.
 */
class DefaultEngine(
    private val config: DefaultEngineConfig = DefaultEngineConfig.defaults(),
) : PlayingEngine {

    private val engineName: String = rebuildName(config)

    /**
     * Reused across [rank] calls on this instance (MC playouts call rank every
     * decision). Cleared at the start of each [rank]. One engine instance must
     * not be shared across threads — [inego.zungjunggame.hints.UcbFlatMcHintSearch] clones for its worker.
     */
    private val rankCache = HashMap<Long, Int>(256)

    /** Local trial hand for discard / claim scoring (no per-call [PlayerState.copy]). */
    private val trialHand = PlayerState()

    /** Shape-value suit / honor tallies. */
    private val suitCounts = IntArray(3)
    private val honorCounts = IntArray(7)

    /** [extrasAfterDiscard] writes value / pattern / locked here. */
    private val extrasOut = IntArray(3)

    /** Kong / promote scoring without allocating a fresh [GameState] each rank. */
    private val kongWork = GameState()

    override fun name(): String = engineName

    override fun rank(state: GameState, choices: ChoiceList): IntArray {
        check(!choices.isEmpty())

        for ((kind) in choices) {
            if (kind == ChoiceKind.Hu) {
                return allBestHuIndices(choices)
            }
        }

        rankCache.clear()

        if (state.decisionType == DecisionType.ReactToDiscard) {
            return rankReact(
                hand = state.players[state.currentPlayer],
                remaining = state.unknownTileCountsByPlayer[state.currentPlayer],
                discarded = state.lastDiscarded,
                seatWind = state.seatWindTile(state.currentPlayer),
                liveWallSize = state.liveWallSize,
                choices = choices,
                cache = rankCache,
                cfg = config,
                trialHand = trialHand,
                suitCounts = suitCounts,
                honorCounts = honorCounts,
            )
        }

        return rankActOnHand(
            state, state.currentPlayer, choices, rankCache, config,
            trialHand, suitCounts, honorCounts, extrasOut, kongWork,
        )
    }

    /** Observation-based ranking for online play (additive, not on PlayingEngine). */
    fun rank(obs: SeatObservation, decisionType: DecisionType, choices: ChoiceList): IntArray {
        if (choices.isEmpty()) return intArrayOf(0)

        for ((kind) in choices) {
            if (kind == ChoiceKind.Hu) {
                return allBestHuIndices(choices)
            }
        }

        rankCache.clear()

        if (decisionType == DecisionType.ReactToDiscard) {
            return rankReact(
                hand = obs.player,
                remaining = obs.remainingTiles,
                discarded = obs.lastDiscarded,
                seatWind = obs.seatWind,
                liveWallSize = obs.liveWallSize,
                choices = choices,
                cache = rankCache,
                cfg = config,
                trialHand = trialHand,
                suitCounts = suitCounts,
                honorCounts = honorCounts,
            )
        }

        return rankActOnHandObservation(
            obs = obs,
            choices = choices,
            cache = rankCache,
            cfg = config,
            trialHand = trialHand,
            suitCounts = suitCounts,
            honorCounts = honorCounts,
            extrasOut = extrasOut,
        )
    }

    override fun clone(): PlayingEngine = DefaultEngine(config)

    private data class ScoredIndex(
        var index: Int = 0,
        var deficiency: Int = 0,
        var acceptance: Int = 0,
        var value: Int = 0,
        var patternValue: Int = 0,
        var lockedPoints: Int = 0,
    )

    companion object {
        private fun rebuildName(config: DefaultEngineConfig): String {
            val d = DefaultEngineConfig.defaults()
            if (config.minDefGainToOpen == d.minDefGainToOpen &&
                config.lateWallLiveTiles == d.lateWallLiveTiles &&
                config.suitWeight == d.suitWeight &&
                config.offsuitPenalty == d.offsuitPenalty &&
                config.honorWeight == d.honorWeight &&
                config.acceptanceWeight == d.acceptanceWeight &&
                config.patternWeight == d.patternWeight &&
                config.allowValueHonorOpenOnTie == d.allowValueHonorOpenOnTie
            ) {
                return "default"
            }
            return "default:${config.minDefGainToOpen}:${config.lateWallLiveTiles}:" +
                "${config.suitWeight}:${config.offsuitPenalty}:" +
                "${config.honorWeight}:${config.acceptanceWeight}:" +
                "${config.patternWeight}"
        }

        /**
         * Declared value-honor sets × 10 (ZJ 3.1). Open pungs/kongs + concealed kongs.
         * Used for the equal-def call-gate exception (not the full Strict L sum).
         */
        internal fun lockedValueHonorPoints(ps: PlayerState, seatWind: Int): Int {
            var sets = 0
            if (ps.pungsKongs[seatWind] != PlayerState.PK_NONE) ++sets
            for (t in Tiles.RED..Tiles.WHITE) {
                if (ps.pungsKongs[t] != PlayerState.PK_NONE) ++sets
            }
            return sets * DefaultEngineConfig.VALUE_HONOR_POINTS
        }

        /** Concealed Hand (1.2) eligibility while still closed — not Strict L. */
        internal fun closedHandCredit(ps: PlayerState): Int =
            if (Deficiency.countExposedMelds(ps) == 0) DefaultEngineConfig.CONCEALED_HAND_POINTS else 0

        // --- Strict L helpers (open melds + declared kongs only; series max within each) ---

        private fun identicalSequencesLocked(chowCounts: IntArray): Int {
            var maxIdentical = 0
            var pairGroups = 0
            for (count in chowCounts) {
                maxIdentical = max(maxIdentical, count)
                pairGroups += count / 2
            }
            return when {
                maxIdentical >= 4 -> 480
                maxIdentical == 3 -> 120
                pairGroups >= 2 -> 60
                maxIdentical == 2 -> 10
                else -> 0
            }
        }

        private fun hasThreeSimilarSequencesLocked(chowCounts: IntArray): Boolean {
            for (suitPos in 0 until 7) {
                if (chowCounts[suitPos] > 0 &&
                    chowCounts[7 + suitPos] > 0 &&
                    chowCounts[14 + suitPos] > 0
                ) {
                    return true
                }
            }
            return false
        }

        private fun hasNineTileStraightLocked(chowCounts: IntArray): Boolean {
            for (suit in 0 until 3) {
                val base = suit * 7
                if (chowCounts[base + 0] > 0 &&
                    chowCounts[base + 3] > 0 &&
                    chowCounts[base + 6] > 0
                ) {
                    return true
                }
            }
            return false
        }

        private fun threeSimilarTripletsLocked(tripletPresent: IntArray): Int {
            for (rank in 0 until 9) {
                if (tripletPresent[rank] > 0 &&
                    tripletPresent[9 + rank] > 0 &&
                    tripletPresent[18 + rank] > 0
                ) {
                    return 120
                }
            }
            return 0
        }

        private fun consecutiveTripletsLocked(tripletPresent: IntArray): Int {
            var maxConsecutive = 0
            for (suit in 0 until 3) {
                var run = 0
                for (rank in 0 until 9) {
                    if (tripletPresent[suit * 9 + rank] > 0) {
                        ++run
                        maxConsecutive = max(maxConsecutive, run)
                    } else {
                        run = 0
                    }
                }
            }
            return when {
                maxConsecutive >= 4 -> 200
                maxConsecutive == 3 -> 100
                else -> 0
            }
        }

        private fun kongsSeriesLocked(kongCount: Int): Int = when {
            kongCount >= 4 -> 480
            kongCount == 3 -> 120
            kongCount == 2 -> 20
            kongCount == 1 -> 5
            else -> 0
        }

        /** Strict L: only declared concealed kongs (hand triplets are not locked). */
        private fun concealedTripletsSeriesLocked(concealedKongCount: Int): Int = when {
            concealedKongCount >= 4 -> 125
            concealedKongCount == 3 -> 30
            concealedKongCount == 2 -> 5
            else -> 0
        }

        /**
         * Strict L floor: pattern points guaranteed by open melds + declared kongs.
         * Series-aware (one score per series; 3.1 stacks per set). No eyes-dependent
         * patterns (Small Three Dragons/Winds, Small Similar Triplets, etc.).
         */
        internal fun strictLockedPatternPoints(ps: PlayerState, seatWind: Int): Int {
            var total = 0

            // 3.1 Value Honor (stackable)
            total += lockedValueHonorPoints(ps, seatWind)

            // 3.2.2 Big Three Dragons (open/declared dragon sets only)
            var dragonSets = 0
            for (t in Tiles.RED..Tiles.WHITE) {
                if (ps.pungsKongs[t] != PlayerState.PK_NONE) ++dragonSets
            }
            if (dragonSets >= 3) total += 130

            // 3.3.2 Big Three Winds / 3.3.4 Big Four Winds (no eyes → skip Small *)
            var windSets = 0
            for (t in Tiles.EAST..Tiles.NORTH) {
                if (ps.pungsKongs[t] != PlayerState.PK_NONE) ++windSets
            }
            total += when {
                windSets >= 4 -> 400
                windSets == 3 -> 120
                else -> 0
            }

            var kongCount = 0
            var concealedKongCount = 0
            val suitedTripletPresent = IntArray(27)
            for (t in 0 until NUM_TILE_TYPES) {
                val pk = ps.pungsKongs[t]
                if (pk == PlayerState.PK_NONE) continue
                if (pk == PlayerState.PK_OPEN_KONG || pk == PlayerState.PK_CONCEALED_KONG) {
                    ++kongCount
                    if (pk == PlayerState.PK_CONCEALED_KONG) ++concealedKongCount
                }
                if (t < 27) suitedTripletPresent[t] = 1
            }

            // 4.2 Concealed triplets floor (concealed kongs only)
            total += concealedTripletsSeriesLocked(concealedKongCount)
            // 4.3 Kongs
            total += kongsSeriesLocked(kongCount)

            // 5.1 Identical sequences (open chows)
            total += identicalSequencesLocked(ps.openChows)
            // 6.1 Three Similar Sequences
            if (hasThreeSimilarSequencesLocked(ps.openChows)) total += 35
            // 7.1 Nine-Tile Straight
            if (hasNineTileStraightLocked(ps.openChows)) total += 40

            // 6.2.2 Three Similar Triplets (no 6.2.1 — needs eyes)
            total += threeSimilarTripletsLocked(suitedTripletPresent)
            // 7.2 Consecutive Triplets
            total += consecutiveTripletsLocked(suitedTripletPresent)

            return total
        }

        /** Ranking floor: Strict L + Concealed Hand eligibility credit. */
        internal fun patternFloorPoints(ps: PlayerState, seatWind: Int): Int =
            strictLockedPatternPoints(ps, seatWind) + closedHandCredit(ps)

        private fun accumulateTilePresence(
            ps: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
        ) {
            suitCounts[0] = 0
            suitCounts[1] = 0
            suitCounts[2] = 0
            honorCounts.fill(0)

            for (t in 0 until NUM_TILE_TYPES) {
                val n = ps.concealed[t]
                if (n <= 0) continue
                if (t < 27) suitCounts[t / 9] += n
                else honorCounts[t - 27] += n
            }

            for (chow in 0 until NUM_CHOW_TYPES) {
                val n = ps.openChows[chow]
                if (n <= 0) continue
                suitCounts[chow / 7] += 3 * n
            }

            for (t in 0 until NUM_TILE_TYPES) {
                val pk = ps.pungsKongs[t]
                if (pk == PlayerState.PK_NONE) continue
                val size =
                    if (pk == PlayerState.PK_OPEN_KONG || pk == PlayerState.PK_CONCEALED_KONG) 4
                    else 3
                if (t < 27) suitCounts[t / 9] += size
                else honorCounts[t - 27] += size
            }
        }

        /**
         * Extra penalty for keeping a singleton honor when few copies remain unknown.
         * Acceptance often ignores 1→2 honor draws (no def gain), so without this,
         * isolated R and W look identical even when one is half-dead in the river.
         * Scale 0..3: rem≥3 → 0, rem 2 → 1, rem 1 → 2, rem 0 → 3.
         */
        private fun honorSingletonScarcityPenalty(remaining: IntArray?, tile: Int): Int {
            if (remaining == null) return 0
            val rem = remaining[tile].coerceIn(0, 3)
            return 3 - rem
        }

        private fun shapeValue(
            ps: PlayerState,
            seatWind: Int,
            cfg: DefaultEngineConfig,
            remaining: IntArray? = null,
            suitCounts: IntArray = IntArray(3),
            honorCounts: IntArray = IntArray(7),
        ): Int {
            if (!cfg.valueBiasEnabled()) return 0

            accumulateTilePresence(ps, suitCounts, honorCounts)

            val maxSuit = max(suitCounts[0], max(suitCounts[1], suitCounts[2]))
            val suitedTotal = suitCounts[0] + suitCounts[1] + suitCounts[2]
            val otherSuited = suitedTotal - maxSuit

            var value = cfg.suitWeight * maxSuit - cfg.offsuitPenalty * otherSuited

            if (cfg.honorWeight == 0) return value

            val hw = cfg.honorWeight

            for (d in 0 until 3) {
                val tile = Tiles.RED + d
                val n = honorCounts[4 + d]
                when {
                    n >= 2 -> value += hw * n
                    n == 1 -> value -= hw + honorSingletonScarcityPenalty(remaining, tile)
                }
            }

            for (w in 0 until 4) {
                val tile = Tiles.EAST + w
                val n = honorCounts[w]
                if (n <= 0) continue
                value += if (tile == seatWind) {
                    if (n >= 2) hw * n
                    else hw / 2 - honorSingletonScarcityPenalty(remaining, tile)
                } else {
                    when {
                        n >= 3 -> hw * 2
                        n == 2 -> hw
                        else -> -hw - honorSingletonScarcityPenalty(remaining, tile)
                    }
                }
            }

            return value
        }

        private fun utility(s: ScoredIndex, cfg: DefaultEngineConfig): Int =
            cfg.acceptanceWeight * s.acceptance +
                cfg.patternWeight * s.patternValue +
                s.value

        /** All max-[Choice.huScore] Hu indices (ascending). */
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

        /**
         * Co-best scored indices: min deficiency, then max [utility].
         * Returns choice indices (not positions in [scored]), ascending.
         */
        private fun allBestScored(
            scored: List<ScoredIndex>,
            cfg: DefaultEngineConfig,
        ): IntArray {
            check(scored.isNotEmpty())
            var bestDef = scored[0].deficiency
            var bestUtil = utility(scored[0], cfg)
            val ties = ArrayList<Int>()
            ties.add(0)

            for (i in 1 until scored.size) {
                val s = scored[i]
                val u = utility(s, cfg)
                if (s.deficiency < bestDef || (s.deficiency == bestDef && u > bestUtil)) {
                    bestDef = s.deficiency
                    bestUtil = u
                    ties.clear()
                    ties.add(i)
                } else if (s.deficiency == bestDef && u == bestUtil) {
                    ties.add(i)
                }
            }

            val out = IntArray(ties.size)
            for (i in ties.indices) out[i] = scored[ties[i]].index
            // Stable ascending choice-index order for HINT / tests.
            out.sort()
            return out
        }

        /**
         * Apply a claim meld to a local copy of the actor's hand (no multi-seat resolve).
         * [discarded] is the tile claimed from the river.
         */
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
                ChoiceKind.Kong -> {
                    val tile = choice.tile()
                    if (tile != discarded || hand.concealed[tile] < 3) return false
                    hand.concealed[tile] -= 3
                    hand.recordMeldExposure(
                        PlayerState.MELD_SET,
                        tile,
                        setState = PlayerState.PK_OPEN_KONG,
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

        private fun scorePlayerState(
            hand: PlayerState,
            choiceIndex: Int,
            seatWind: Int,
            remaining: IntArray,
            withAcceptance: Boolean,
            withExtras: Boolean,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            suitCounts: IntArray,
            honorCounts: IntArray,
        ): ScoredIndex {
            val scored = ScoredIndex()
            scored.index = choiceIndex
            scored.deficiency = DeficiencyFast.handDeficiencyCached(hand, cache)
            scored.acceptance = if (withAcceptance) {
                DeficiencyFast.acceptanceCountFast(hand, remaining, scored.deficiency, cache)
            } else {
                0
            }
            // lockedPoints is always 3.1 only (call-gate). patternValue is full floor.
            scored.lockedPoints = lockedValueHonorPoints(hand, seatWind)
            if (withExtras) {
                scored.value = shapeValue(hand, seatWind, cfg, remaining, suitCounts, honorCounts)
                scored.patternValue = patternFloorPoints(hand, seatWind)
            } else {
                scored.value = 0
                scored.patternValue = 0
            }
            return scored
        }

        private fun scoreReactionChoice(
            baseHand: PlayerState,
            choiceIndex: Int,
            choice: Choice,
            discarded: Int,
            seatWind: Int,
            remaining: IntArray,
            withAcceptance: Boolean,
            withExtras: Boolean,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
        ): ScoredIndex {
            if (choice.kind == ChoiceKind.Pass) {
                return scorePlayerState(
                    baseHand, choiceIndex, seatWind, remaining,
                    withAcceptance, withExtras, cache, cfg, suitCounts, honorCounts,
                )
            }

            trialHand.copyFrom(baseHand)
            if (!applyCallLocally(trialHand, choice, discarded)) {
                // Illegal under local rules: treat as extremely bad so it loses.
                return ScoredIndex(index = choiceIndex, deficiency = 99)
            }
            return scorePlayerState(
                trialHand, choiceIndex, seatWind, remaining,
                withAcceptance, withExtras, cache, cfg, suitCounts, honorCounts,
            )
        }

        private fun deficiencyAfterDiscard(
            hand: PlayerState,
            tile: Int,
            cache: MutableMap<Long, Int>,
            trialHand: PlayerState,
        ): Int {
            check(tile in 0 until NUM_TILE_TYPES)
            check(hand.concealed[tile] > 0)
            trialHand.copyFrom(hand)
            --trialHand.concealed[tile]
            trialHand.activeConcealedTileCount = -1
            return DeficiencyFast.handDeficiencyCached(trialHand, cache)
        }

        private fun acceptanceAfterDiscard(
            hand: PlayerState,
            tile: Int,
            remaining: IntArray,
            baseDef: Int,
            cache: MutableMap<Long, Int>,
            trialHand: PlayerState,
        ): Int {
            trialHand.copyFrom(hand)
            --trialHand.concealed[tile]
            trialHand.activeConcealedTileCount = -1
            return DeficiencyFast.acceptanceCountFast(trialHand, remaining, baseDef, cache)
        }

        /** Writes value, patternValue, lockedPoints into [out] (length ≥ 3). */
        private fun extrasAfterDiscard(
            hand: PlayerState,
            tile: Int,
            seatWind: Int,
            remaining: IntArray,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
            out: IntArray,
        ) {
            trialHand.copyFrom(hand)
            --trialHand.concealed[tile]
            trialHand.activeConcealedTileCount = -1
            out[0] = shapeValue(trialHand, seatWind, cfg, remaining, suitCounts, honorCounts)
            out[1] = patternFloorPoints(trialHand, seatWind)
            out[2] = lockedValueHonorPoints(trialHand, seatWind)
        }

        /** Kongs on own turn still use full state apply (single-actor transition). */
        private fun scoreViaApply(
            work: GameState,
            actor: Int,
            choiceIndex: Int,
            choice: Choice,
            withAcceptance: Boolean,
            withExtras: Boolean,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            suitCounts: IntArray,
            honorCounts: IntArray,
        ): ScoredIndex {
            val seatWind = work.seatWindTile(actor)
            val undo = work.applyChoiceWithUndo(choice)
            val scored = scorePlayerState(
                work.players[actor],
                choiceIndex,
                seatWind,
                work.unknownTileCountsByPlayer[actor],
                withAcceptance,
                withExtras,
                cache,
                cfg,
                suitCounts,
                honorCounts,
            )
            work.undoChoice(undo)
            return scored
        }

        private fun callAllowedVsPass(
            passDef: Int,
            callDef: Int,
            passLocked: Int,
            callLocked: Int,
            freeCallPolicy: Boolean,
            cfg: DefaultEngineConfig,
        ): Boolean {
            if (freeCallPolicy) return callDef <= passDef
            if (callDef <= passDef - cfg.minDefGainToOpen) return true
            // Bank a value-honor set without worsening deficiency (ZJ 10 vs closed 5).
            if (cfg.allowValueHonorOpenOnTie &&
                callDef <= passDef &&
                callLocked >= passLocked + DefaultEngineConfig.VALUE_HONOR_POINTS
            ) {
                return true
            }
            return false
        }

        /** Lexicographic improvement vs pass: def, then acceptance, then pattern, then shape. */
        private fun strictlyNotWorseThanPass(scored: ScoredIndex, pass: ScoredIndex): Boolean {
            if (scored.deficiency < pass.deficiency) return true
            if (scored.deficiency > pass.deficiency) return false
            if (scored.acceptance > pass.acceptance) return true
            if (scored.acceptance < pass.acceptance) return false
            if (scored.patternValue > pass.patternValue) return true
            if (scored.patternValue < pass.patternValue) return false
            return scored.value > pass.value
        }

        private fun rankReact(
            hand: PlayerState,
            remaining: IntArray,
            discarded: Int,
            seatWind: Int,
            liveWallSize: Int,
            choices: ChoiceList,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
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

            val pass = scoreReactionChoice(
                hand, passIndex, choices[passIndex], discarded, seatWind, remaining,
                withAcceptance = true, withExtras = true, cache = cache, cfg = cfg,
                trialHand = trialHand, suitCounts = suitCounts, honorCounts = honorCounts,
            )

            val alreadyOpen = Deficiency.countOpenMelds(hand) > 0
            val lateWall = liveWallSize <= cfg.lateWallLiveTiles
            val freeCallPolicy = alreadyOpen || lateWall

            data class CallDef(val index: Int, val deficiency: Int, val lockedPoints: Int)
            val improving = ArrayList<CallDef>()

            for ((i, element) in choices.withIndex()) {
                val kind = element.kind
                if (kind != ChoiceKind.Chow && kind != ChoiceKind.Pung && kind != ChoiceKind.Kong) {
                    continue
                }
                val defOnly = scoreReactionChoice(
                    hand, i, element, discarded, seatWind, remaining,
                    withAcceptance = false, withExtras = false, cache = cache, cfg = cfg,
                    trialHand = trialHand, suitCounts = suitCounts, honorCounts = honorCounts,
                )
                if (callAllowedVsPass(
                        pass.deficiency,
                        defOnly.deficiency,
                        pass.lockedPoints,
                        defOnly.lockedPoints,
                        freeCallPolicy,
                        cfg,
                    )
                ) {
                    improving.add(CallDef(i, defOnly.deficiency, defOnly.lockedPoints))
                }
            }

            if (improving.isEmpty()) return intArrayOf(passIndex)

            var minDef = pass.deficiency
            for ((_, deficiency) in improving) minDef = min(minDef, deficiency)

            val candidates = ArrayList<ScoredIndex>()
            if (minDef == pass.deficiency) candidates.add(pass)

            for ((index, deficiency) in improving) {
                if (deficiency != minDef) continue
                val scored = scoreReactionChoice(
                    hand, index, choices[index], discarded, seatWind, remaining,
                    withAcceptance = true, withExtras = true, cache = cache, cfg = cfg,
                    trialHand = trialHand, suitCounts = suitCounts, honorCounts = honorCounts,
                )
                if (strictlyNotWorseThanPass(scored, pass)) {
                    candidates.add(scored)
                }
            }

            if (candidates.isEmpty()) return intArrayOf(passIndex)
            return allBestScored(candidates, cfg)
        }

        private fun rankActOnHand(
            state: GameState,
            actor: Int,
            choices: ChoiceList,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
            extrasOut: IntArray,
            kongWork: GameState,
        ): IntArray {
            val remaining = state.unknownTileCountsByPlayer[actor]
            val hand = state.players[actor]
            val seatWind = state.seatWindTile(actor)

            val phase1 = ArrayList<ScoredIndex>()

            var needsWork = false
            for ((kind) in choices) {
                if (kind == ChoiceKind.ConcealedKong ||
                    kind == ChoiceKind.PromoteKong ||
                    kind == ChoiceKind.Keep
                ) {
                    needsWork = true
                }
            }

            val work = if (needsWork) {
                kongWork.copyFrom(state)
                kongWork
            } else {
                null
            }

            for ((i, element) in choices.withIndex()) {
                when (element.kind) {
                    ChoiceKind.Discard -> {
                        val s = ScoredIndex()
                        s.index = i
                        s.deficiency = deficiencyAfterDiscard(hand, element.tile(), cache, trialHand)
                        phase1.add(s)
                    }
                    ChoiceKind.ConcealedKong, ChoiceKind.PromoteKong -> {
                        phase1.add(
                            scoreViaApply(
                                work!!, actor, i, element,
                                withAcceptance = false, withExtras = false, cache = cache, cfg = cfg,
                                suitCounts = suitCounts, honorCounts = honorCounts,
                            ),
                        )
                    }
                    ChoiceKind.Keep -> {
                        // Keep = decline this concealed kong; score as best discard from current hand
                        // (further sequential kong gates are resolved on later decisions).
                        phase1.add(
                            scoreKeepAsBestDiscard(
                                hand = hand,
                                choiceIndex = i,
                                remaining = remaining,
                                seatWind = seatWind,
                                withAcceptance = false,
                                withExtras = false,
                                cache = cache,
                                cfg = cfg,
                                trialHand = trialHand,
                                suitCounts = suitCounts,
                                honorCounts = honorCounts,
                                extrasOut = extrasOut,
                            ),
                        )
                    }
                    else -> Unit
                }
            }

            if (phase1.isEmpty()) return intArrayOf(0)

            var minDef = phase1[0].deficiency
            for (i in 1 until phase1.size) minDef = min(minDef, phase1[i].deficiency)

            val finalists = ArrayList<ScoredIndex>()
            for ((index, deficiency) in phase1) {
                if (deficiency != minDef) continue
                val kind = choices[index].kind
                when (kind) {
                    ChoiceKind.Discard -> {
                        val tile = choices[index].tile()
                        val out = ScoredIndex(
                            index = index,
                            deficiency = deficiency,
                        )
                        out.acceptance = acceptanceAfterDiscard(
                            hand, tile, remaining, minDef, cache, trialHand,
                        )
                        extrasAfterDiscard(
                            hand, tile, seatWind, remaining, cfg,
                            trialHand, suitCounts, honorCounts, extrasOut,
                        )
                        out.value = extrasOut[0]
                        out.patternValue = extrasOut[1]
                        out.lockedPoints = extrasOut[2]
                        finalists.add(out)
                    }
                    ChoiceKind.Keep -> {
                        finalists.add(
                            scoreKeepAsBestDiscard(
                                hand = hand,
                                choiceIndex = index,
                                remaining = remaining,
                                seatWind = seatWind,
                                withAcceptance = true,
                                withExtras = true,
                                cache = cache,
                                cfg = cfg,
                                trialHand = trialHand,
                                suitCounts = suitCounts,
                                honorCounts = honorCounts,
                                extrasOut = extrasOut,
                            ),
                        )
                    }
                    else -> {
                        finalists.add(
                            scoreViaApply(
                                work!!, actor, index, choices[index],
                                withAcceptance = true, withExtras = true, cache = cache, cfg = cfg,
                                suitCounts = suitCounts, honorCounts = honorCounts,
                            ),
                        )
                    }
                }
            }

            return allBestScored(finalists, cfg)
        }

        /**
         * Score [ChoiceKind.Keep] as the best available discard utility from [hand]
         * (declining the concealed kong leaves the four tiles in hand for a normal discard).
         */
        private fun scoreKeepAsBestDiscard(
            hand: PlayerState,
            choiceIndex: Int,
            remaining: IntArray,
            seatWind: Int,
            withAcceptance: Boolean,
            withExtras: Boolean,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
            extrasOut: IntArray,
        ): ScoredIndex {
            hand.ensureActiveConcealedTiles()
            var best: ScoredIndex? = null
            for (idx in 0 until hand.activeConcealedTileCount) {
                val tile = hand.activeConcealedTiles[idx]
                if (hand.concealed[tile] <= 0) continue
                val def = deficiencyAfterDiscard(hand, tile, cache, trialHand)
                val cand = ScoredIndex(index = choiceIndex, deficiency = def)
                if (withAcceptance) {
                    cand.acceptance = acceptanceAfterDiscard(
                        hand, tile, remaining, def, cache, trialHand,
                    )
                }
                if (withExtras) {
                    extrasAfterDiscard(
                        hand, tile, seatWind, remaining, cfg,
                        trialHand, suitCounts, honorCounts, extrasOut,
                    )
                    cand.value = extrasOut[0]
                    cand.patternValue = extrasOut[1]
                    cand.lockedPoints = extrasOut[2]
                }
                if (best == null) {
                    best = cand
                    continue
                }
                val b = best
                if (cand.deficiency < b.deficiency ||
                    (cand.deficiency == b.deficiency && utility(cand, cfg) > utility(b, cfg))
                ) {
                    best = cand
                }
            }
            return best ?: ScoredIndex(index = choiceIndex, deficiency = 99)
        }

        private fun rankActOnHandObservation(
            obs: SeatObservation,
            choices: ChoiceList,
            cache: MutableMap<Long, Int>,
            cfg: DefaultEngineConfig,
            trialHand: PlayerState,
            suitCounts: IntArray,
            honorCounts: IntArray,
            extrasOut: IntArray,
        ): IntArray {
            val hand = obs.player
            val remaining = obs.remainingTiles
            val seatWind = obs.seatWind

            var hasDiscards = false
            var hasKongs = false
            for ((k) in choices) {
                if (k == ChoiceKind.Discard) hasDiscards = true
                if (k == ChoiceKind.ConcealedKong || k == ChoiceKind.PromoteKong) hasKongs = true
            }

            // Kong gate (no Discard in the list)
            if (!hasDiscards) {
                val kongs = ArrayList<Int>()
                for ((i, element) in choices.withIndex()) {
                    val k = element.kind
                    if (k == ChoiceKind.ConcealedKong || k == ChoiceKind.PromoteKong) {
                        kongs.add(i)
                    }
                }
                if (kongs.isNotEmpty()) {
                    return kongs.toIntArray()
                }
                return intArrayOf(0)
            }

            // Mixed with discards: Phase 1 min deficiency of Discard and Keep
            val phase1 = ArrayList<ScoredIndex>()
            for ((i, element) in choices.withIndex()) {
                when (element.kind) {
                    ChoiceKind.Discard -> {
                        val s = ScoredIndex()
                        s.index = i
                        s.deficiency = deficiencyAfterDiscard(hand, element.tile(), cache, trialHand)
                        phase1.add(s)
                    }
                    ChoiceKind.Keep -> {
                        phase1.add(
                            scoreKeepAsBestDiscard(
                                hand = hand,
                                choiceIndex = i,
                                remaining = remaining,
                                seatWind = seatWind,
                                withAcceptance = false,
                                withExtras = false,
                                cache = cache,
                                cfg = cfg,
                                trialHand = trialHand,
                                suitCounts = suitCounts,
                                honorCounts = honorCounts,
                                extrasOut = extrasOut,
                            ),
                        )
                    }
                    else -> Unit
                }
            }

            if (phase1.isEmpty()) return intArrayOf(0)

            var baseline = Int.MAX_VALUE
            val keepItem = phase1.firstOrNull { choices[it.index].kind == ChoiceKind.Keep }
            if (keepItem != null) {
                baseline = keepItem.deficiency
            } else {
                for ((_, deficiency) in phase1) baseline = min(baseline, deficiency)
            }

            // Evaluate kongs against baseline
            val qualifyingKongs = ArrayList<Int>()
            if (hasKongs) {
                for ((i, choice) in choices.withIndex()) {
                    val k = choice.kind
                    if (k == ChoiceKind.ConcealedKong || k == ChoiceKind.PromoteKong) {
                        trialHand.copyFrom(hand)
                        val tile = choice.tile()
                        if (k == ChoiceKind.ConcealedKong) {
                            trialHand.concealed[tile] -= 4
                            trialHand.recordMeldExposure(
                                PlayerState.MELD_SET,
                                tile,
                                setState = PlayerState.PK_CONCEALED_KONG,
                            )
                        } else {
                            trialHand.concealed[tile] -= 1
                            // Promote in place: the pung's slot and position stay.
                            trialHand.promoteMeldToKong(tile)
                        }
                        trialHand.activeConcealedTileCount = -1
                        val trialDef = DeficiencyFast.handDeficiencyCached(trialHand, cache)
                        if (trialDef <= baseline) {
                            qualifyingKongs.add(i)
                        }
                    }
                }
            }

            if (qualifyingKongs.isNotEmpty()) {
                qualifyingKongs.sort()
                return qualifyingKongs.toIntArray()
            }

            var minDef = phase1[0].deficiency
            for (i in 1 until phase1.size) minDef = min(minDef, phase1[i].deficiency)

            val finalists = ArrayList<ScoredIndex>()
            for ((index, deficiency) in phase1) {
                if (deficiency != minDef) continue
                val kind = choices[index].kind
                when (kind) {
                    ChoiceKind.Discard -> {
                        val tile = choices[index].tile()
                        val out = ScoredIndex(
                            index = index,
                            deficiency = deficiency,
                        )
                        out.acceptance = acceptanceAfterDiscard(
                            hand, tile, remaining, minDef, cache, trialHand,
                        )
                        extrasAfterDiscard(
                            hand, tile, seatWind, remaining, cfg,
                            trialHand, suitCounts, honorCounts, extrasOut,
                        )
                        out.value = extrasOut[0]
                        out.patternValue = extrasOut[1]
                        out.lockedPoints = extrasOut[2]
                        finalists.add(out)
                    }
                    ChoiceKind.Keep -> {
                        finalists.add(
                            scoreKeepAsBestDiscard(
                                hand = hand,
                                choiceIndex = index,
                                remaining = remaining,
                                seatWind = seatWind,
                                withAcceptance = true,
                                withExtras = true,
                                cache = cache,
                                cfg = cfg,
                                trialHand = trialHand,
                                suitCounts = suitCounts,
                                honorCounts = honorCounts,
                                extrasOut = extrasOut,
                            ),
                        )
                    }
                    else -> Unit
                }
            }

            return allBestScored(finalists, cfg)
        }
    }
}
