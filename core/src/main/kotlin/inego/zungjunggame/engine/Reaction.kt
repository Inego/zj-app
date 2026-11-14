package inego.zungjunggame.engine

/**
 * Origin of a reaction decision (discard or promoted kong).
 */
sealed class ReactionSource {
    abstract val seat: Int
    abstract val tile: Int

    data class Discard(override val seat: Int, override val tile: Int) : ReactionSource()
    data class PromotedKong(override val seat: Int, override val tile: Int) : ReactionSource()
}

/**
 * Frozen source context for reacting to a discard or small exposed kong.
 *
 * Recording a bid against this context does not mutate the board or execute transitions.
 */
data class ReactionContext(
    val source: ReactionSource,
    val kongRobWindow: Boolean,
    val lastDrawWasKongSupplement: Boolean,
    val liveWallSize: Int,
) {
    val sourceSeat: Int get() = source.seat
    val tile: Int get() = source.tile

    companion object {
        fun fromState(state: GameState): ReactionContext? {
            if (state.decisionType != DecisionType.ReactToDiscard) return null
            val source = if (state.kongRobWindow) {
                ReactionSource.PromotedKong(state.lastDiscarder, state.lastDiscarded)
            } else {
                ReactionSource.Discard(state.lastDiscarder, state.lastDiscarded)
            }
            return ReactionContext(
                source = source,
                kongRobWindow = state.kongRobWindow,
                lastDrawWasKongSupplement = state.lastDrawWasKongSupplement,
                liveWallSize = state.liveWallSize,
            )
        }
    }
}

/**
 * Outcome of resolving submitted bids for a reaction window.
 */
sealed interface ReactionResolution {
    data object AllPass : ReactionResolution
    data class Winning(val seat: Int, val choice: Choice) : ReactionResolution
}

/**
 * Priority rank for reaction bids:
 * Hu (3) > Pung / Kong (2) > Chow (1) > Pass / other (0).
 */
fun reactionBidRank(kind: ChoiceKind): Int = when (kind) {
    ChoiceKind.Hu -> 3
    ChoiceKind.Pung, ChoiceKind.Kong -> 2
    ChoiceKind.Chow -> 1
    else -> 0
}

/**
 * Pure resolver: given [sourceSeat] and submitted choices for each seat, determine the winning bid.
 *
 * Priority order is Hu > Pung/Kong > Chow > Pass. Ties between equal ranks are broken by seating
 * order immediately after [sourceSeat] (first match wins). Arrival order or map iteration order
 * does not affect the outcome.
 */
fun resolveReactions(sourceSeat: Int, submittedChoices: Array<Choice>): ReactionResolution {
    var bestPlayer = -1
    var bestRank = 0
    var bestChoice = Choice.pass()

    for (step in 1 until NUM_PLAYERS) {
        val player = (sourceSeat + step) % NUM_PLAYERS
        if (player >= submittedChoices.size) continue
        val choice = submittedChoices[player]
        val rank = reactionBidRank(choice.kind)
        if (rank > bestRank) {
            bestRank = rank
            bestPlayer = player
            bestChoice = choice
        }
    }

    return if (bestPlayer < 0 || bestChoice.kind == ChoiceKind.Pass) {
        ReactionResolution.AllPass
    } else {
        ReactionResolution.Winning(bestPlayer, bestChoice)
    }
}

/**
 * Map overload for [resolveReactions] to accept arbitrary collection/arrival orderings.
 */
fun resolveReactions(sourceSeat: Int, submittedChoices: Map<Int, Choice>): ReactionResolution {
    var bestPlayer = -1
    var bestRank = 0
    var bestChoice = Choice.pass()

    for (step in 1 until NUM_PLAYERS) {
        val player = (sourceSeat + step) % NUM_PLAYERS
        val choice = submittedChoices[player] ?: Choice.pass()
        val rank = reactionBidRank(choice.kind)
        if (rank > bestRank) {
            bestRank = rank
            bestPlayer = player
            bestChoice = choice
        }
    }

    return if (bestPlayer < 0 || bestChoice.kind == ChoiceKind.Pass) {
        ReactionResolution.AllPass
    } else {
        ReactionResolution.Winning(bestPlayer, bestChoice)
    }
}

/**
 * Enumerate legal reaction choices for [seat] given a frozen [reactionContext] (Pass, Hu, Pung, Kong, Chow).
 */
fun enumerateReactions(
    state: GameState,
    reactionContext: ReactionContext,
    seat: Int,
    out: ChoiceList = ChoiceList(),
): ChoiceList = state.enumerateReactions(reactionContext, seat, out)
