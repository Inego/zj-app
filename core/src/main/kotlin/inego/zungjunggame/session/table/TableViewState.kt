package inego.zungjunggame.session.table

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Scoring

/**
 * Public and private state of a single seat as observed by [TableViewState.mySeat].
 *
 * For opponents during live play, [concealedTiles] is null and [drawnTile] is -1.
 * Only the count of concealed tiles [concealedCount], whether a drawn tile is parked
 * [hasDrawnTile], public [melds], and the discard [river] are visible.
 */
data class SeatView(
    val seat: Int,
    val name: String = "",
    val isBot: Boolean = false,
    val concealedCount: Int,
    /** Present only for own seat or when hands are revealed at terminal; null for hidden opponents. */
    val concealedTiles: List<Int>?,
    /** Parked drawn tile type if known (own seat or revealed); -1 if none or hidden. */
    val drawnTile: Int = -1,
    /** True if this seat currently has a 14th parked drawn tile. */
    val hasDrawnTile: Boolean = false,
    /** Public melds (open chows, pungs, kongs, and concealed kongs). */
    val melds: List<MeldGroup> = emptyList(),
    /** Finalized discards in this seat's river. */
    val river: List<Int> = emptyList(),
    /** Seat wind tile type (East/South/West/North tile index). */
    val seatWindTile: Int = -1,
)

/**
 * Privacy-preserving client projection of the game table for [mySeat].
 *
 * Project from a full [GameState] locally; later from a server SeatSnapshot.
 * Opponents' concealed tiles, wall order, and hidden draws are absent.
 */
data class TableViewState(
    val mySeat: Int,
    val eastSeat: Int,
    val liveWallSize: Int,
    val currentPlayer: Int,
    val decisionType: DecisionType,
    val lastDiscarder: Int,
    val lastDiscarded: Int,
    val isPendingDiscard: Boolean,
    val isKongRobWindow: Boolean,
    val handScore: Int,
    val winnerSeat: Int,
    val winSource: Int,
    val isTerminal: Boolean,
    val scoreDeltas: IntArray?,
    val seats: List<SeatView>,
    val myChoices: ChoiceList,
    val isHumanDiscardTurn: Boolean,
    val isHumanClaimTurn: Boolean,
    val isHumanActOnHandPanel: Boolean,
    val isWaitingForOthers: Boolean = false,
) {
    /**
     * True whenever the human owes the table an answer: a discard, a claim
     * reaction, or an ActOnHand panel action (Hu / concealed kong / promoted
     * kong / Keep).
     *
     * [isHumanActOnHandPanel] must be part of this. On the sequential
     * concealed-kong gate the rules offer only `ConcealedKong` + `Keep`, so the
     * online projection reports neither a discard turn nor a claim turn — the
     * row is on screen and the hand is parked until the player answers. While
     * the flag was missing from this predicate, every tap on that row was
     * dropped silently by the board commit gate in
     * `inego.zungjunggame.app.AppSessionEffects` (`commitChoice`) and the hand hung.
     * Local play masked it: [fromGameState] derives the discard turn from
     * `decisionType` alone, so it never had the gap.
     */
    val isHumanInteractiveTurn: Boolean
        get() = isHumanDiscardTurn || isHumanClaimTurn || isHumanActOnHandPanel

    /**
     * True when the human player has a drawn tile from the wall that can be discarded.
     * Must not work in East's first discard, when a Hu option (self-draw win) is available,
     * or when discards are gated by a concealed-kong decision.
     */
    val canDiscardDrawnTile: Boolean
        get() {
            if (!isHumanDiscardTurn) return false
            val ownSeatView = seats.getOrNull(mySeat) ?: return false
            if (!ownSeatView.hasDrawnTile || ownSeatView.drawnTile < 0) return false
            if (mySeat == eastSeat && ownSeatView.river.isEmpty()) return false
            var hasDiscard = false
            for ((kind) in myChoices) {
                if (kind == ChoiceKind.Hu) return false
                if (kind == ChoiceKind.Discard) hasDiscard = true
            }
            return hasDiscard
        }

    /**
     * True when the human player currently holds a drawn tile from the wall that completes
     * a patternless (chicken) hand (4 sets + 1 pair, 0 pattern points).
     */
    val isHumanDrawnPatternlessHand: Boolean
        get() {
            if (!isHumanDiscardTurn) return false
            val ownSeatView = seats.getOrNull(mySeat) ?: return false
            val isDrawnTurn = (ownSeatView.hasDrawnTile && ownSeatView.drawnTile >= 0) ||
                (ownSeatView.seat == eastSeat && ownSeatView.concealedCount == 14 && ownSeatView.river.isEmpty())
            if (!isDrawnTurn) return false
            val ps = ownSeatView.toPlayerState() ?: return false
            return Scoring.isPatternlessHand(ps, mySeat, eastSeat)
        }

    /**
     * The one affirmative action the open panel leaves the human, or null when it offers none
     * or several (then the player must pick). The keyboard's Enter accepts it.
     *
     * The count is over the buttons the panel actually shows
     * ([inego.zungjunggame.render.claim.ClaimActionPanel]): discards are hand hits,
     * not buttons, and Pass and Keep only decline. So a self-drawn win beside a rack of
     * discards is still one action, and a concealed kong beside Keep has one unambiguous
     * action (the kong), while two chows, or a concealed kong beside Hu, are a real choice
     * between multiple affirmative actions the player must make.
     */
    val unambiguousClaim: Choice?
        get() {
            if (!isHumanInteractiveTurn) return null
            var found: Choice? = null
            for (element in myChoices) {
                if (element.kind == ChoiceKind.Pass ||
                    element.kind == ChoiceKind.Keep ||
                    element.kind == ChoiceKind.Discard
                ) continue
                if (found != null) return null
                found = element
            }
            return found
        }

    /**
     * The decline action (Pass of an open claim reaction, or Keep of a concealed kong gate)
     * when no Hu is on offer, else null. The keyboard's Space declines only ordinary
     * claims and gates, so a win can never be thrown away by the space key.
     */
    val nonHuDecline: Choice?
        get() {
            if (!isHumanInteractiveTurn) return null
            var decline: Choice? = null
            for (element in myChoices) {
                when (element.kind) {
                    ChoiceKind.Hu -> return null
                    ChoiceKind.Pass, ChoiceKind.Keep -> decline = element
                    else -> Unit
                }
            }
            return decline
        }


    /**
     * Seat the table stays centred on: the seat whose turn it *is*, as opposed to
     * [currentPlayer], which is the seat that must act right now.
     *
     * The engine walks [currentPlayer] across the seats deciding on a claim
     * ([DecisionType.ReactToDiscard], e.g. [ChoiceKind.Chow] / [ChoiceKind.Pung] /
     * [ChoiceKind.Hu]), so the two differ while a discard is open. A seat merely
     * *reacting* must not take the emphasis from the seat that discarded — that
     * seat keeps it until the claim resolves and play actually moves on. Same for
     * the promoter during a rob-the-kong window.
     *
     * At hand end the winner keeps it: an outright win on a discard leaves
     * [decisionType] at [DecisionType.ReactToDiscard].
     */
    val activeSeat: Int
        get() = when {
            winnerSeat in 0 until NUM_PLAYERS -> winnerSeat
            decisionType == DecisionType.ReactToDiscard && lastDiscarder in 0 until NUM_PLAYERS ->
                lastDiscarder
            else -> currentPlayer
        }

    companion object {
        /**
         * Project a full authoritative [GameState] into a client [TableViewState]
         * seen by [humanSeat].
         */
        fun fromGameState(
            state: GameState,
            humanSeat: Int,
            revealedSeats: Set<Int> = emptySet(),
            choices: ChoiceList? = null,
        ): TableViewState {
            val myChoices = choices ?: if (state.currentPlayer == humanSeat && !state.isTerminal()) {
                state.enumerateChoices()
            } else {
                ChoiceList()
            }

            val isDiscardTurn = !state.isTerminal() &&
                state.decisionType == DecisionType.ActOnHand &&
                state.currentPlayer == humanSeat

            val isClaimTurn = !state.isTerminal() &&
                state.decisionType == DecisionType.ReactToDiscard &&
                state.currentPlayer == humanSeat &&
                !state.isTrivialPassOnlyReaction(myChoices)

            val isActOnHandPanel = isDiscardTurn && hasActOnHandSpecials(myChoices)

            val isPendingDiscard = state.decisionType == DecisionType.ReactToDiscard &&
                state.lastDiscarder in 0 until NUM_PLAYERS &&
                state.lastDiscarded in 0 until NUM_TILE_TYPES

            val winnerSeat = if (state.handScore > 0) {
                if (state.winnerSeat >= 0) state.winnerSeat else state.currentPlayer
            } else {
                -1
            }

            val winSource = if (state.handScore > 0) {
                if (state.winSource >= 0) {
                    state.winSource
                } else if (state.decisionType == DecisionType.ReactToDiscard) {
                    state.lastDiscarder
                } else {
                    -1
                }
            } else {
                -1
            }

            val seatViews = ArrayList<SeatView>(NUM_PLAYERS)
            for (seat in 0 until NUM_PLAYERS) {
                val player = state.players[seat]
                val showFaces = seat == humanSeat || seat == winnerSeat || seat in revealedSeats
                val drawnForSeat = drawnTileForDisplay(state, seat)
                val hasDrawn = drawnForSeat >= 0

                var count = 0
                for (t in 0 until NUM_TILE_TYPES) count += player.concealed[t]

                val concealedTiles: List<Int>? = if (showFaces) {
                    val list = ArrayList<Int>(count)
                    for (t in 0 until NUM_TILE_TYPES) {
                        repeat(player.concealed[t]) { list.add(t) }
                    }
                    if (hasDrawn) {
                        val drawnIdx = list.lastIndexOf(drawnForSeat)
                        if (drawnIdx >= 0) list.removeAt(drawnIdx)
                    }
                    list
                } else {
                    null
                }

                val melds = expandMelds(player, seat)
                val river = buildRiverForDisplay(state, seat)
                val windTile = state.seatWindTile(seat)

                seatViews += SeatView(
                    seat = seat,
                    concealedCount = count,
                    concealedTiles = concealedTiles,
                    drawnTile = if (showFaces) drawnForSeat else -1,
                    hasDrawnTile = hasDrawn,
                    melds = melds,
                    river = river,
                    seatWindTile = windTile,
                )
            }

            return TableViewState(
                mySeat = humanSeat,
                eastSeat = state.eastSeat,
                liveWallSize = state.liveWallSize,
                currentPlayer = state.currentPlayer,
                decisionType = state.decisionType,
                lastDiscarder = state.lastDiscarder,
                lastDiscarded = state.lastDiscarded,
                isPendingDiscard = isPendingDiscard,
                isKongRobWindow = state.kongRobWindow,
                handScore = state.handScore,
                winnerSeat = winnerSeat,
                winSource = winSource,
                isTerminal = state.isTerminal(),
                scoreDeltas = if (state.isTerminal()) state.computeScoreDeltas() else null,
                seats = seatViews,
                myChoices = myChoices,
                isHumanDiscardTurn = isDiscardTurn,
                isHumanClaimTurn = isClaimTurn,
                isHumanActOnHandPanel = isActOnHandPanel,
            )
        }

        private fun hasActOnHandSpecials(choices: ChoiceList): Boolean {
            for ((kind) in choices) {
                when (kind) {
                    ChoiceKind.Hu,
                    ChoiceKind.ConcealedKong,
                    ChoiceKind.PromoteKong,
                    ChoiceKind.Keep,
                    -> return true
                    else -> Unit
                }
            }
            return false
        }
    }
}
