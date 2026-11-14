package inego.zungjunggame.session.table

import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState

/**
 * When this seat just drew from the wall mid-hand and is about to act, park
 * [GameState.lastDrawnTile] on the right (with glow). Cleared after discard
 * so the remaining hand is fully sorted. Not used for the dealer's opening
 * 14 — those are all dealt tiles, sorted with no special slot.
 */
internal fun drawnTileForDisplay(state: GameState, seat: Int): Int {
    if (state.decisionType != DecisionType.ActOnHand) return -1
    if (state.currentPlayer != seat) return -1
    if (state.lastDrawnPlayer != seat) return -1
    val tile = state.lastDrawnTile
    if (tile < 0) return -1
    if (state.players[seat].concealed[tile] <= 0) return -1
    return tile
}

/**
 * Finalized river plus the pending discard (still open for claims) so the
 * just-played tile is visible at the end of the discarder's river.
 * After Hu on discard, that pending tile stays here only (orange glow) —
 * it is not also added to the winner's hand.
 */
internal fun buildRiverForDisplay(state: GameState, seat: Int): List<Int> {
    val base = state.discards[seat]
    val pending =
        state.decisionType == DecisionType.ReactToDiscard &&
            state.lastDiscarder == seat &&
            state.lastDiscarded >= 0
    if (!pending) return base
    val withPending = ArrayList<Int>(base.size + 1)
    withPending.addAll(base)
    withPending.add(state.lastDiscarded)
    return withPending
}
