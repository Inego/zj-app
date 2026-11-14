package inego.zungjunggame.engine

/**
 * One scored pattern line on a winning hand.
 *
 * @param id stable reference page id / L10n key ([PatternIds]); never a display string
 * @param points points contributed by this pattern
 * @param formatArgs optional MessageFormat args (e.g. value-honor set count)
 */
data class HandScoreComponent(
    val id: String,
    val points: Int,
    val formatArgs: List<Any> = emptyList(),
)

data class HandScoreDetails(
    var totalScore: Int = 0,
    var uncappedTotal: Int = 0,
    val components: MutableList<HandScoreComponent> = mutableListOf(),
)

/**
 * Zung Jung hand scoring.
 *
 * Ported from zj-self-play `engine/scoring.*`.
 */
object Scoring {
    /**
     * Scores one hand.
     *
     * [claimedDiscard] is the winning tile when the hand was completed by a discard, already
     * folded into `ps.concealed` by the caller, and `-1` on a self-draw. It only matters to
     * series 4.2, where a pung counts as concealed only when all three of its tiles came from
     * the wall.
     */
    fun computeHandScoreDetails(
        ps: PlayerState,
        playerIndex: Int,
        eastSeat: Int = 0,
        allowChickenHands: Boolean = true,
        claimedDiscard: Int = -1,
    ): HandScoreDetails {
        // Irregular fast path: Thirteen Terminals.
        if (HuCheck.isThirteenTerminalsHand(ps.concealed)) {
            return ScoringDetail.computeThirteenTerminalsDetails()
        }

        val facts = ScoringDetail.analyzeHand(ps)

        // Nine Gates listed-limit fast path.
        if (facts.isConcealed && facts.pureOneSuit &&
            HuCheck.isNineGatesSuit(ps.concealed, facts.singleSuit)
        ) {
            return ScoringDetail.computeNineGatesDetails()
        }

        if (facts.isConcealed && HuCheck.isSevenPairsHand(ps.concealed)) {
            return ScoringDetail.computeSevenPairsDetails(facts)
        }

        return ScoringDetail.computeBestRegularHandDetails(
            ps,
            facts,
            SeatWinds.tileForPlayer(playerIndex, eastSeat),
            allowChickenHands = allowChickenHands,
            claimedDiscard = claimedDiscard,
        )
    }

    /**
     * True if [ps] forms a complete winning shape with 0 pattern points (chicken hand).
     */
    fun isPatternlessHand(
        ps: PlayerState,
        playerIndex: Int,
        eastSeat: Int = 0,
    ): Boolean {
        val patterned = computeHandScoreDetails(ps, playerIndex, eastSeat, allowChickenHands = false)
        if (patterned.totalScore > 0) return false
        val chicken = computeHandScoreDetails(ps, playerIndex, eastSeat, allowChickenHands = true)
        return chicken.components.any { it.id == PatternIds.CHICKEN_HAND }
    }

    fun computeHandScoreDetails(
        state: GameState,
        winner: Int,
        includeClaimedDiscard: Boolean,
    ): HandScoreDetails {
        if (winner !in 0 until NUM_PLAYERS) return HandScoreDetails()

        val scoredHand = state.players[winner].copy()
        var claimedDiscard = -1
        if (includeClaimedDiscard) {
            if (state.lastDiscarded !in 0 until NUM_TILE_TYPES) return HandScoreDetails()
            ++scoredHand.concealed[state.lastDiscarded]
            claimedDiscard = state.lastDiscarded
        }

        // Score patterns with chicken off first so a 9.x incidental (win on kong,
        // rob, final draw/discard, blessing) is not blocked by the 5-pt minimum,
        // and is not stacked on top of the 1-pt chicken residual.
        val patterned = computeHandScoreDetails(
            scoredHand,
            winner,
            state.eastSeat,
            allowChickenHands = false,
            claimedDiscard = claimedDiscard,
        )
        if (patterned.totalScore > 320) return patterned

        val chickenDetails =
            if (patterned.totalScore > 0) {
                null
            } else {
                computeHandScoreDetails(
                    scoredHand,
                    winner,
                    state.eastSeat,
                    allowChickenHands = true,
                    claimedDiscard = claimedDiscard,
                )
            }
        val winningShape = patterned.totalScore > 0 || (chickenDetails?.totalScore ?: 0) > 0
        if (!winningShape) return HandScoreDetails()

        val score =
            if (patterned.totalScore > 0) {
                ScoringDetail.buildScoreFromDetails(patterned)
            } else {
                ScoringDetail.ScoreBuildState()
            }
        ScoringDetail.applyContextualBonusPatterns(state, winner, includeClaimedDiscard, score)
        val built = score.build()
        if (built.totalScore > 0) return built
        if (state.allowChickenHands && chickenDetails != null && chickenDetails.totalScore > 0) {
            return chickenDetails
        }
        return HandScoreDetails()
    }

    fun computeHandScoreDetails(state: GameState): HandScoreDetails {
        if (state.handScore <= 0) return HandScoreDetails()

        val winner = if (state.winnerSeat in 0 until NUM_PLAYERS) state.winnerSeat else state.currentPlayer
        if (winner !in 0 until NUM_PLAYERS) return HandScoreDetails()

        val includeClaimedDiscard =
            state.decisionType == DecisionType.ReactToDiscard &&
                state.lastDiscarded in 0 until NUM_TILE_TYPES
        return computeHandScoreDetails(state, winner, includeClaimedDiscard)
    }
}
