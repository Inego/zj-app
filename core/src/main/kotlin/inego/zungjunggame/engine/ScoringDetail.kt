package inego.zungjunggame.engine

import kotlin.math.max
import kotlin.math.min

/** Internal scoring helpers (patterns, regular decomposition, contextual bonuses). */
internal object ScoringDetail {

    class ScoreBuildState {
        var additiveScore: Int = 0
        var maxOverCapScore: Int = 0
        var maxOverCapId: String = ""
        var maxOverCapArgs: List<Any> = emptyList()
        val components = mutableListOf<HandScoreComponent>()

        fun applyPattern(id: String, patternScore: Int, vararg formatArgs: Any) {
            if (patternScore <= 0) return
            val args = if (formatArgs.isEmpty()) emptyList() else formatArgs.toList()

            if (patternScore > 320) {
                if (patternScore > maxOverCapScore) {
                    maxOverCapScore = patternScore
                    maxOverCapId = id
                    maxOverCapArgs = args
                }
                return
            }

            additiveScore += patternScore
            components.add(HandScoreComponent(id, patternScore, args))
        }

        fun build(): HandScoreDetails {
            val details = HandScoreDetails()
            if (maxOverCapScore > 0) {
                details.totalScore = maxOverCapScore
                details.uncappedTotal = maxOverCapScore
                details.components.add(
                    HandScoreComponent(maxOverCapId, maxOverCapScore, maxOverCapArgs),
                )
                return details
            }

            details.totalScore = min(additiveScore, 320)
            details.uncappedTotal = additiveScore
            details.components.addAll(components)
            return details
        }
    }

    class HandFacts {
        var meldedPungsKongs: Int = 0
        var meldedChows: Int = 0
        var totalMeldedSets: Int = 0

        /**
         * Sets exposed before the win (open pungs/kongs, open chows). A concealed kong
         * is a melded set but is NOT exposed, so it does not break Concealed Hand (1.2).
         */
        var openMelds: Int = 0
        var singleSuit: Int = -1
        var kongCount: Int = 0
        var isConcealed: Boolean = false
        var noTerminals: Boolean = true
        var hasHonor: Boolean = false
        var mixedOneSuit: Boolean = false
        var pureOneSuit: Boolean = false
        var allHonors: Boolean = true
        var onlyTerminalsOrHonors: Boolean = true
        var hasTerminals: Boolean = false
    }

    fun analyzeHand(ps: PlayerState): HandFacts {
        val facts = HandFacts()
        var sawSuitedTile = false
        var mixedSuits = false

        for (tile in 0 until NUM_TILE_TYPES) {
            val concealedCount = ps.concealed[tile]
            val pungOrKong = ps.pungsKongs[tile]

            if (pungOrKong > 0) {
                ++facts.meldedPungsKongs
                if (pungOrKong == PlayerState.PK_OPEN_PUNG ||
                    pungOrKong == PlayerState.PK_OPEN_KONG
                ) {
                    ++facts.openMelds
                }
                if (pungOrKong >= PlayerState.PK_OPEN_KONG) ++facts.kongCount
            }

            if (concealedCount <= 0 && pungOrKong <= 0) continue

            if (tile >= 27) {
                facts.hasHonor = true
                facts.noTerminals = false
                continue
            }

            facts.allHonors = false
            val suit = tile / 9
            if (!sawSuitedTile) {
                facts.singleSuit = suit
                sawSuitedTile = true
            } else if (facts.singleSuit != suit) {
                mixedSuits = true
            }

            if (!Tiles.isSimple(tile)) facts.noTerminals = false
            if (isTerminalTile(tile)) {
                facts.hasTerminals = true
            } else {
                facts.onlyTerminalsOrHonors = false
            }
        }

        for (chowIndex in 0 until NUM_CHOW_TYPES) {
            val chowCount = ps.openChows[chowIndex]
            if (chowCount <= 0) continue

            facts.meldedChows += chowCount
            facts.openMelds += chowCount
            facts.allHonors = false
            facts.onlyTerminalsOrHonors = false

            val suit = chowIndex / 7
            if (!sawSuitedTile) {
                facts.singleSuit = suit
                sawSuitedTile = true
            } else if (facts.singleSuit != suit) {
                mixedSuits = true
            }

            val suitPos = chowIndex % 7
            if (suitPos == 0 || suitPos == 6) facts.noTerminals = false
        }

        if (mixedSuits) facts.singleSuit = -1

        facts.totalMeldedSets = facts.meldedPungsKongs + facts.meldedChows
        facts.isConcealed = facts.openMelds == 0
        facts.mixedOneSuit = facts.singleSuit != -1 && facts.hasHonor
        facts.pureOneSuit = facts.singleSuit != -1 && !facts.hasHonor

        return facts
    }

    fun computeThirteenTerminalsDetails(): HandScoreDetails {
        val score = ScoreBuildState()
        score.applyPattern(PatternIds.THIRTEEN_TERMINALS, 160)
        return score.build()
    }

    fun computeNineGatesDetails(): HandScoreDetails {
        val score = ScoreBuildState()
        score.applyPattern(PatternIds.NINE_GATES, 480)
        return score.build()
    }

    fun computeSevenPairsDetails(facts: HandFacts): HandScoreDetails {
        val score = ScoreBuildState()
        score.applyPattern(PatternIds.SEVEN_PAIRS, 30)

        if (facts.noTerminals) score.applyPattern(PatternIds.NO_TERMINALS, 5)
        if (facts.allHonors) score.applyPattern(PatternIds.ALL_HONORS, 320)
        if (facts.mixedOneSuit) score.applyPattern(PatternIds.MIXED_ONE_SUIT, 40)
        if (facts.pureOneSuit) score.applyPattern(PatternIds.PURE_ONE_SUIT, 80)

        val terminalsScore = greaterTerminalsGroupScore(facts)
        if (terminalsScore > 0) {
            score.applyPattern(greaterTerminalsGroupId(facts), terminalsScore)
        }

        return score.build()
    }

    fun scoreRegularDecompositionDetails(
        ps: PlayerState,
        facts: HandFacts,
        sets: List<HuCheck.ConcealedSet>,
        pairTile: Int,
        seatWindTile: Int,
        chowCounts: IntArray,
        tripletCounts: IntArray,
        claimedDiscard: Int,
    ): HandScoreDetails {
        val score = ScoreBuildState()

        if (facts.isConcealed) score.applyPattern(PatternIds.CONCEALED_HAND, 5)

        var allSequences = facts.meldedPungsKongs == 0
        if (allSequences) {
            for ((type) in sets) {
                if (type != HuCheck.SetType.Chow) {
                    allSequences = false
                    break
                }
            }
        }
        if (allSequences) score.applyPattern(PatternIds.ALL_SEQUENCES, 5)

        var allTriplets = facts.meldedChows == 0
        if (allTriplets) {
            for ((type) in sets) {
                if (type != HuCheck.SetType.Pung) {
                    allTriplets = false
                    break
                }
            }
        }
        if (allTriplets) score.applyPattern(PatternIds.ALL_TRIPLETS, 30)

        if (facts.noTerminals) score.applyPattern(PatternIds.NO_TERMINALS, 5)
        if (facts.allHonors) score.applyPattern(PatternIds.ALL_HONORS, 320)
        if (facts.mixedOneSuit) score.applyPattern(PatternIds.MIXED_ONE_SUIT, 40)
        if (facts.pureOneSuit) score.applyPattern(PatternIds.PURE_ONE_SUIT, 80)

        val valueHonorSets = countValueHonorSets(ps, sets, seatWindTile)
        if (valueHonorSets > 0) {
            score.applyPattern(
                PatternIds.VALUE_HONOR_SETS,
                10 * valueHonorSets,
                valueHonorSets,
            )
        }

        val dragonSets = countDragonSets(ps, sets)
        when (dragonSets) {
            3 -> score.applyPattern(dragonGroupId(130), 130)
            2 if hasDragonPairEyes(pairTile) ->
                score.applyPattern(dragonGroupId(40), 40)
        }

        val windSets = countWindSets(ps, sets)
        val windPairEyes = hasWindPairEyes(pairTile)
        when (windSets) {
            4 -> score.applyPattern(windGroupId(400), 400)
            3 if windPairEyes -> score.applyPattern(windGroupId(320), 320)
            3 -> score.applyPattern(windGroupId(120), 120)
            2 if windPairEyes -> score.applyPattern(windGroupId(30), 30)
        }

        when (facts.kongCount) {
            4 -> score.applyPattern(kongsGroupId(480), 480)
            3 -> score.applyPattern(kongsGroupId(120), 120)
            2 -> score.applyPattern(kongsGroupId(20), 20)
            1 -> score.applyPattern(kongsGroupId(5), 5)
        }

        val identicalSequencesScore = identicalSequencesGroupScore(chowCounts)
        if (identicalSequencesScore > 0) {
            score.applyPattern(
                identicalSequencesGroupId(identicalSequencesScore),
                identicalSequencesScore,
            )
        }

        if (hasThreeSimilarSequences(chowCounts)) {
            score.applyPattern(PatternIds.THREE_SIMILAR_SEQUENCES, 35)
        }
        if (hasNineTileStraight(chowCounts)) {
            score.applyPattern(PatternIds.NINE_TILE_STRAIGHT, 40)
        }

        val terminalsScore = terminalsGroupScore(ps, sets, pairTile)
        if (terminalsScore > 0) {
            score.applyPattern(terminalsGroupId(terminalsScore), terminalsScore)
        }
        val similarTripletsScore = similarTripletsGroupScore(tripletCounts, pairTile)
        if (similarTripletsScore > 0) {
            score.applyPattern(similarTripletsGroupId(similarTripletsScore), similarTripletsScore)
        }
        val consecutiveTriplets = consecutiveTripletsScore(tripletCounts)
        if (consecutiveTriplets > 0) {
            score.applyPattern(consecutiveTripletsGroupId(consecutiveTriplets), consecutiveTriplets)
        }

        val concealedTriplets = countConcealedTriplets(ps, sets, claimedDiscard)
        when (concealedTriplets) {
            4 -> score.applyPattern(concealedTripletsGroupId(125), 125)
            3 -> score.applyPattern(concealedTripletsGroupId(30), 30)
            2 -> score.applyPattern(concealedTripletsGroupId(5), 5)
        }

        return score.build()
    }

    fun computeBestRegularHandDetails(
        ps: PlayerState,
        facts: HandFacts,
        seatWindTile: Int,
        allowChickenHands: Boolean = true,
        claimedDiscard: Int = -1,
    ): HandScoreDetails {
        val chowCounts = ps.openChows.copyOf()
        val tripletCounts = seedSuitedTripletCounts(ps)
        var maxScore = 0
        var bestDetails = HandScoreDetails()

        val traversal = HuCheck.DecompositionTraversal(
            onSetEnter = { set ->
                applySetToRunningSummaries(set, chowCounts, tripletCounts, +1)
            },
            onSetExit = { set ->
                applySetToRunningSummaries(set, chowCounts, tripletCounts, -1)
            },
            onLeaf = { sets, pairTile ->
                val currentDetails = scoreRegularDecompositionDetails(
                    ps,
                    facts,
                    sets,
                    pairTile,
                    seatWindTile,
                    chowCounts,
                    tripletCounts,
                    claimedDiscard,
                )
                val currentScore = currentDetails.totalScore
                if (currentScore > maxScore) maxScore = currentScore
                if (currentScore >= maxScore) bestDetails = currentDetails
            },
        )

        val setsNeeded = 4 - facts.totalMeldedSets
        val found = HuCheck.traverseDecompositions(ps.concealed, setsNeeded, traversal)
        if (!found) return HandScoreDetails()

        if (maxScore == 0) {
            // Pure chicken: complete shape with no scoring patterns.
            if (!allowChickenHands) return HandScoreDetails()
            return HandScoreDetails(
                totalScore = 1,
                uncappedTotal = 1,
                components = mutableListOf(HandScoreComponent(PatternIds.CHICKEN_HAND, 1)),
            )
        }

        return bestDetails
    }

    fun buildScoreFromDetails(details: HandScoreDetails): ScoreBuildState {
        val score = ScoreBuildState()
        for ((id, points, formatArgs) in details.components) {
            if (formatArgs.isEmpty()) {
                score.applyPattern(id, points)
            } else {
                score.applyPattern(
                    id,
                    points,
                    *formatArgs.toTypedArray(),
                )
            }
        }
        return score
    }

    fun applyContextualBonusPatterns(
        state: GameState,
        winner: Int,
        includeClaimedDiscard: Boolean,
        score: ScoreBuildState,
    ) {
        if (isFinalDrawContext(state, winner, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.FINAL_DRAW, 10)
        }
        if (isFinalDiscardContext(state, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.FINAL_DISCARD, 10)
        }
        if (isWinOnKongContext(state, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.WIN_ON_KONG, 10)
        }
        if (isRobbingKongContext(state, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.ROBBING_A_KONG, 10)
        }
        if (isBlessingOfHeavenContext(state, winner, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.BLESSING_OF_HEAVEN, 155)
        }
        if (isBlessingOfEarthContext(state, winner, includeClaimedDiscard)) {
            score.applyPattern(PatternIds.BLESSING_OF_EARTH, 155)
        }
    }

    // --- private helpers -------------------------------------------------

    private fun isTerminalTile(tile: Int): Boolean =
        tile < 27 && (tile % 9 == 0 || tile % 9 == 8)

    private fun isDragonTile(tile: Int): Boolean =
        tile in Tiles.RED..Tiles.WHITE

    private fun isWindTile(tile: Int): Boolean =
        tile in Tiles.EAST..Tiles.NORTH

    private fun countValueHonorSets(
        ps: PlayerState,
        sets: List<HuCheck.ConcealedSet>,
        seatWindTile: Int,
    ): Int {
        var count = 0
        if (ps.pungsKongs[seatWindTile] > 0) ++count
        for (tile in Tiles.RED..Tiles.WHITE) {
            if (ps.pungsKongs[tile] > 0) ++count
        }
        for ((type, baseTile) in sets) {
            if (type != HuCheck.SetType.Pung) continue
            if (baseTile == seatWindTile || isDragonTile(baseTile)) ++count
        }
        return count
    }

    private fun countDragonSets(ps: PlayerState, sets: List<HuCheck.ConcealedSet>): Int {
        var count = 0
        for (tile in Tiles.RED..Tiles.WHITE) {
            if (ps.pungsKongs[tile] > 0) ++count
        }
        for ((type, baseTile) in sets) {
            if (type == HuCheck.SetType.Pung && isDragonTile(baseTile)) ++count
        }
        return count
    }

    private fun hasDragonPairEyes(pairTile: Int): Boolean = isDragonTile(pairTile)

    private fun countWindSets(ps: PlayerState, sets: List<HuCheck.ConcealedSet>): Int {
        var count = 0
        for (tile in Tiles.EAST..Tiles.NORTH) {
            if (ps.pungsKongs[tile] > 0) ++count
        }
        for ((type, baseTile) in sets) {
            if (type == HuCheck.SetType.Pung && isWindTile(baseTile)) ++count
        }
        return count
    }

    private fun hasWindPairEyes(pairTile: Int): Boolean = isWindTile(pairTile)

    /**
     * Counts the triplets that score for series 4.2.
     *
     * A pung is concealed only when all three of its tiles came from the wall. The claimed
     * winning tile is already folded into [PlayerState.concealed] by the caller, so a pung it
     * completed has to give that one tile back before it can be counted. A discard claim makes
     * the triplet exposed even though the tile finished the hand.
     */
    private fun countConcealedTriplets(
        ps: PlayerState,
        sets: List<HuCheck.ConcealedSet>,
        claimedDiscard: Int,
    ): Int {
        var count = 0
        for ((type, baseTile) in sets) {
            if (type != HuCheck.SetType.Pung) continue
            val fromWall = ps.concealed[baseTile] - if (claimedDiscard == baseTile) 1 else 0
            if (fromWall >= 3) ++count
        }
        for (pk in ps.pungsKongs) {
            if (pk == PlayerState.PK_CONCEALED_KONG) ++count
        }
        return count
    }

    private fun chowIndexForBaseTile(baseTile: Int): Int {
        val suitIndex = baseTile / 9
        val suitPos = baseTile % 9
        return suitIndex * 7 + suitPos
    }

    private fun identicalSequencesGroupScore(chowCounts: IntArray): Int {
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

    private fun hasThreeSimilarSequences(chowCounts: IntArray): Boolean {
        for (suitPos in 0 until 7) {
            val chars = chowCounts[suitPos]
            val dots = chowCounts[7 + suitPos]
            val bamboos = chowCounts[14 + suitPos]
            if (chars > 0 && dots > 0 && bamboos > 0) return true
        }
        return false
    }

    private fun hasNineTileStraight(chowCounts: IntArray): Boolean {
        for (suit in 0 until 3) {
            val base = suit * 7
            if (chowCounts[base] > 0 && chowCounts[base + 3] > 0 && chowCounts[base + 6] > 0) {
                return true
            }
        }
        return false
    }

    private fun applySetToRunningSummaries(
        set: HuCheck.ConcealedSet,
        chowCounts: IntArray,
        tripletCounts: IntArray,
        delta: Int,
    ) {
        if (set.type == HuCheck.SetType.Chow) {
            chowCounts[chowIndexForBaseTile(set.baseTile)] += delta
            return
        }
        if (set.baseTile < NUM_SUITED_TILES) {
            tripletCounts[set.baseTile] += delta
        }
    }

    private fun similarTripletsGroupScore(tripletCounts: IntArray, pairTile: Int): Int {
        for (rank in 0 until 9) {
            val suitsWithTriplet =
                (if (tripletCounts[rank] > 0) 1 else 0) +
                    (if (tripletCounts[9 + rank] > 0) 1 else 0) +
                    (if (tripletCounts[18 + rank] > 0) 1 else 0)
            if (suitsWithTriplet == 3) return 120
        }

        if (pairTile in 0 until 27) {
            val pairSuit = pairTile / 9
            val pairRank = pairTile % 9
            var otherSuitsWithTriplet = 0
            for (suit in 0 until 3) {
                if (suit == pairSuit) continue
                if (tripletCounts[suit * 9 + pairRank] > 0) ++otherSuitsWithTriplet
            }
            if (otherSuitsWithTriplet == 2) return 30
        }
        return 0
    }

    private fun consecutiveTripletsScore(tripletCounts: IntArray): Int {
        var maxConsecutive = 0
        for (suit in 0 until 3) {
            var current = 0
            for (rank in 0 until 9) {
                if (tripletCounts[suit * 9 + rank] > 0) {
                    ++current
                    maxConsecutive = max(maxConsecutive, current)
                } else {
                    current = 0
                }
            }
        }
        return when {
            maxConsecutive >= 4 -> 200
            maxConsecutive == 3 -> 100
            else -> 0
        }
    }

    private fun greaterTerminalsGroupScore(facts: HandFacts): Int {
        if (!facts.onlyTerminalsOrHonors || !facts.hasTerminals) return 0
        return if (facts.hasHonor) 100 else 400
    }

    private fun greaterTerminalsGroupId(facts: HandFacts): String =
        if (facts.hasHonor) PatternIds.MIXED_GREATER_TERMINALS else PatternIds.PURE_GREATER_TERMINALS

    private fun terminalsGroupId(score: Int): String = when (score) {
        400 -> PatternIds.PURE_GREATER_TERMINALS
        100 -> PatternIds.MIXED_GREATER_TERMINALS
        50 -> PatternIds.PURE_LESSER_TERMINALS
        40 -> PatternIds.MIXED_LESSER_TERMINALS
        else -> PatternIds.TERMINALS_GROUP
    }

    private fun identicalSequencesGroupId(score: Int): String = when (score) {
        480 -> PatternIds.FOUR_IDENTICAL_SEQUENCES
        120 -> PatternIds.THREE_IDENTICAL_SEQUENCES
        60 -> PatternIds.TWO_IDENTICAL_SEQUENCES_TWICE
        10 -> PatternIds.TWO_IDENTICAL_SEQUENCES
        else -> PatternIds.IDENTICAL_SEQUENCES
    }

    private fun similarTripletsGroupId(score: Int): String = when (score) {
        120 -> PatternIds.THREE_SIMILAR_TRIPLETS
        30 -> PatternIds.SMALL_THREE_SIMILAR_TRIPLETS
        else -> PatternIds.SIMILAR_TRIPLETS
    }

    private fun consecutiveTripletsGroupId(score: Int): String = when (score) {
        200 -> PatternIds.FOUR_CONSECUTIVE_TRIPLETS
        100 -> PatternIds.THREE_CONSECUTIVE_TRIPLETS
        else -> PatternIds.CONSECUTIVE_TRIPLETS
    }

    private fun kongsGroupId(score: Int): String = when (score) {
        480 -> PatternIds.FOUR_KONGS
        120 -> PatternIds.THREE_KONGS
        20 -> PatternIds.TWO_KONGS
        5 -> PatternIds.ONE_KONG
        else -> PatternIds.KONGS
    }

    private fun concealedTripletsGroupId(score: Int): String = when (score) {
        125 -> PatternIds.FOUR_CONCEALED_TRIPLETS
        30 -> PatternIds.THREE_CONCEALED_TRIPLETS
        5 -> PatternIds.TWO_CONCEALED_TRIPLETS
        else -> PatternIds.CONCEALED_TRIPLETS
    }

    private fun windGroupId(score: Int): String = when (score) {
        400 -> PatternIds.BIG_FOUR_WINDS
        320 -> PatternIds.SMALL_FOUR_WINDS
        120 -> PatternIds.BIG_THREE_WINDS
        30 -> PatternIds.SMALL_THREE_WINDS
        else -> PatternIds.WINDS
    }

    private fun dragonGroupId(score: Int): String = when (score) {
        130 -> PatternIds.BIG_THREE_DRAGONS
        40 -> PatternIds.SMALL_THREE_DRAGONS
        else -> PatternIds.DRAGONS
    }

    private fun terminalsGroupScore(
        ps: PlayerState,
        sets: List<HuCheck.ConcealedSet>,
        pairTile: Int,
    ): Int {
        var hasChows = false
        for (count in ps.openChows) {
            if (count > 0) hasChows = true
        }
        for ((type) in sets) {
            if (type == HuCheck.SetType.Chow) hasChows = true
        }

        var onlyTerminalsOrHonors = true
        var hasHonors = false
        var hasTerminals = false

        for (tile in 0 until NUM_TILE_TYPES) {
            if (ps.concealed[tile] > 0 || ps.pungsKongs[tile] > 0) {
                when {
                    tile >= 27 -> hasHonors = true
                    isTerminalTile(tile) -> hasTerminals = true
                    else -> onlyTerminalsOrHonors = false
                }
            }
        }
        if (hasChows) onlyTerminalsOrHonors = false

        if (onlyTerminalsOrHonors) {
            if (hasTerminals && !hasHonors) return 400
            if (hasTerminals) return 100
        }

        var allElementsPass = true

        fun elementPass(type: HuCheck.SetType, baseTile: Int): Boolean {
            if (type == HuCheck.SetType.Pung) return Tiles.isTerminalOrHonor(baseTile)
            val rank = baseTile % 9
            return rank == 0 || rank == 6
        }

        for (tile in 0 until NUM_TILE_TYPES) {
            if (ps.pungsKongs[tile] > 0) {
                if (!elementPass(HuCheck.SetType.Pung, tile)) allElementsPass = false
            }
        }
        for (chowIndex in 0 until NUM_CHOW_TYPES) {
            if (ps.openChows[chowIndex] > 0) {
                val suit = chowIndex / 7
                val rank = chowIndex % 7
                if (!elementPass(HuCheck.SetType.Chow, suit * 9 + rank)) allElementsPass = false
            }
        }
        for ((type, baseTile) in sets) {
            if (!elementPass(type, baseTile)) allElementsPass = false
        }
        if (!Tiles.isTerminalOrHonor(pairTile)) allElementsPass = false

        if (allElementsPass) return if (hasHonors) 40 else 50
        return 0
    }

    private fun seedSuitedTripletCounts(ps: PlayerState): IntArray {
        val tripletCounts = IntArray(NUM_SUITED_TILES)
        for (tile in 0 until NUM_SUITED_TILES) {
            if (ps.pungsKongs[tile] > 0) ++tripletCounts[tile]
        }
        return tripletCounts
    }

    /**
     * Self-draw on the **seabed**: the last live wall tile, won by the seat that drew
     * it. Requires the wall to run out on that draw and [winner] to still hold the
     * draw — a claim leaves [GameState.lastDrawnPlayer] unset, so a claimer cannot
     * claim this pattern.
     */
    private fun isFinalDrawContext(
        state: GameState,
        winner: Int,
        includeClaimedDiscard: Boolean,
    ): Boolean =
        !includeClaimedDiscard &&
            state.decisionType == DecisionType.ActOnHand &&
            state.liveWallSize == 0 &&
            state.lastDrawnPlayer == winner

    /**
     * Win on the **riverbed**: the discard of the seat that drew the seabed.
     *
     * Keys on [GameState.seabedPlayer], not on the empty wall alone: once the riverbed
     * is claimed for pung/chow the hand continues, and that claimer's own discard is
     * equally "a discard with an empty wall" but is **not** the riverbed.
     */
    private fun isFinalDiscardContext(state: GameState, includeClaimedDiscard: Boolean): Boolean =
        includeClaimedDiscard &&
            state.decisionType == DecisionType.ReactToDiscard &&
            !state.kongRobWindow &&
            state.liveWallSize == 0 &&
            state.lastDiscarded in 0 until NUM_TILE_TYPES &&
            state.seabedPlayer in 0 until NUM_PLAYERS &&
            state.lastDiscarder == state.seabedPlayer

    private fun isWinOnKongContext(state: GameState, includeClaimedDiscard: Boolean): Boolean =
        !includeClaimedDiscard &&
            state.decisionType == DecisionType.ActOnHand &&
            state.lastDrawWasKongSupplement

    private fun isRobbingKongContext(state: GameState, includeClaimedDiscard: Boolean): Boolean =
        includeClaimedDiscard &&
            state.decisionType == DecisionType.ReactToDiscard &&
            state.kongRobWindow &&
            state.lastDiscarded in 0 until NUM_TILE_TYPES

    private fun isBlessingOfHeavenContext(
        state: GameState,
        winner: Int,
        includeClaimedDiscard: Boolean,
    ): Boolean =
        !includeClaimedDiscard &&
            winner == 0 &&
            state.decisionType == DecisionType.ActOnHand &&
            state.lastDiscarder == -1 &&
            state.lastDiscarded == -1 &&
            state.liveWallSize == INITIAL_DECISION_LIVE_WALL_SIZE

    private fun isBlessingOfEarthContext(
        state: GameState,
        winner: Int,
        includeClaimedDiscard: Boolean,
    ): Boolean =
        includeClaimedDiscard &&
            winner != 0 &&
            state.decisionType == DecisionType.ReactToDiscard &&
            !state.kongRobWindow &&
            state.lastDiscarder == 0 &&
            state.lastDiscarded in 0 until NUM_TILE_TYPES &&
            state.liveWallSize == INITIAL_DECISION_LIVE_WALL_SIZE
}
