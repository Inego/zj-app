package inego.zungjunggame.engine

/**
 * Zung Jung payment settlement for one finished hand.
 *
 * Base rule: each of the three non-winners pays the hand value to the winner
 * (winner nets `3 × handValue`).
 *
 * Exception (win on discard only): when [handValue] is greater than
 * [NON_DISCARDER_CAP], the two non-discarder losers pay only [NON_DISCARDER_CAP]
 * each, and the discarder pays their own full share plus the excess that those
 * two do not pay — so the winner still nets `3 × handValue`.
 *
 * Self-draw: always full share from each of the other three, regardless of size.
 */
object ZjSettlement {
    /** Cap paid by non-discarders when the discarder is liable for the excess. */
    const val NON_DISCARDER_CAP = 25

    /**
     * Zero-allocation payment delta for a single [seat] on one finished hand.
     *
     * @param handValue pattern score of the winning hand (`0` = exhaustive draw)
     * @param winnerSeat absolute seat of the winner, or `-1` on a draw
     * @param discarderSeat seat that discarded the winning tile, or `-1` if
     *   self-draw / exhaustive draw
     * @param seat absolute seat whose signed payment delta to return
     */
    fun computeDeltaForSeat(
        handValue: Int,
        winnerSeat: Int,
        discarderSeat: Int = -1,
        seat: Int,
    ): Int {
        if (handValue <= 0 || winnerSeat !in 0 until NUM_PLAYERS) return 0
        if (seat == winnerSeat) return 3 * handValue
        val winOnDiscard =
            discarderSeat in 0 until NUM_PLAYERS && discarderSeat != winnerSeat
        if (!winOnDiscard || handValue <= NON_DISCARDER_CAP) return -handValue
        val excess = handValue - NON_DISCARDER_CAP
        return if (seat == discarderSeat) {
            // Own full share + excess for each of the other two losers.
            -(handValue + 2 * excess)
        } else {
            -NON_DISCARDER_CAP
        }
    }

    /**
     * @param handValue pattern score of the winning hand (`0` = exhaustive draw)
     * @param winnerSeat absolute seat of the winner, or `-1` on a draw
     * @param discarderSeat seat that discarded the winning tile, or `-1` if
     *   self-draw / exhaustive draw
     */
    fun computeDeltas(
        handValue: Int,
        winnerSeat: Int,
        discarderSeat: Int = -1,
    ): IntArray {
        val deltas = IntArray(NUM_PLAYERS)
        if (handValue <= 0 || winnerSeat !in 0 until NUM_PLAYERS) return deltas
        for (player in 0 until NUM_PLAYERS) {
            deltas[player] = computeDeltaForSeat(handValue, winnerSeat, discarderSeat, player)
        }
        return deltas
    }
}
