package inego.zungjunggame.engine

/**
 * One finished hand in a multi-hand session, with ZJ payment [deltas] by absolute seat.
 *
 * [runningTotals] are cumulative scores **after** this hand (same seat order).
 */
data class HandScoreRow(
    val handNumber: Int,
    val handValue: Int,
    val winnerSeat: Int,
    val discarderSeat: Int,
    val deltas: IntArray,
    val runningTotals: IntArray,
    /** The winning hand's scored patterns, or empty for a draw. */
    val patterns: List<HandScoreComponent> = emptyList(),
) {
    val isDraw: Boolean get() = handValue <= 0 || winnerSeat < 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandScoreRow) return false
        return handNumber == other.handNumber &&
            handValue == other.handValue &&
            winnerSeat == other.winnerSeat &&
            discarderSeat == other.discarderSeat &&
            deltas.contentEquals(other.deltas) &&
            runningTotals.contentEquals(other.runningTotals) &&
            patterns == other.patterns
    }

    override fun hashCode(): Int {
        var r = handNumber
        r = 31 * r + handValue
        r = 31 * r + winnerSeat
        r = 31 * r + discarderSeat
        r = 31 * r + deltas.contentHashCode()
        r = 31 * r + runningTotals.contentHashCode()
        r = 31 * r + patterns.hashCode()
        return r
    }
}

/**
 * Accumulates finished-hand settlements across a game (Next hand deals again).
 * Seat indices are absolute table seats (0..3).
 */
class SessionScoreTable {
    private val rows = ArrayList<HandScoreRow>()
    private val totals = IntArray(NUM_PLAYERS)

    val handCount: Int get() = rows.size

    fun rows(): List<HandScoreRow> = rows

    /** Live cumulative totals (copy). */
    fun runningTotals(): IntArray = totals.copyOf()

    fun clear() {
        rows.clear()
        totals.fill(0)
    }

    /**
     * Replace history with a deep copy of [restored]. Totals become the last
     * row's [HandScoreRow.runningTotals], or zero when empty.
     */
    fun restore(restored: List<HandScoreRow>) {
        rows.clear()
        for ((handNumber, handValue, winnerSeat, discarderSeat, deltas, runningTotals, patterns) in restored) {
            rows += HandScoreRow(
                handNumber = handNumber,
                handValue = handValue,
                winnerSeat = winnerSeat,
                discarderSeat = discarderSeat,
                deltas = deltas.copyOf(),
                runningTotals = runningTotals.copyOf(),
                patterns = patterns,
            )
        }
        if (rows.isEmpty()) {
            totals.fill(0)
        } else {
            System.arraycopy(rows.last().runningTotals, 0, totals, 0, NUM_PLAYERS)
        }
    }

    /**
     * Append settlement for a terminal [state]. No-op if not terminal.
     * @return the new row, or null if skipped
     */
    fun recordTerminal(state: GameState): HandScoreRow? {
        if (!state.isTerminal()) return null
        val handValue = state.handScore
        val winnerSeat = if (handValue > 0) {
            if (state.winnerSeat >= 0) state.winnerSeat else state.currentPlayer
        } else {
            -1
        }
        val discarderSeat = if (handValue > 0) {
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
        val deltas = state.computeScoreDeltas()
        for (p in 0 until NUM_PLAYERS) {
            totals[p] += deltas[p]
        }
        val patterns = if (handValue > 0) {
            Scoring.computeHandScoreDetails(state).components.toList()
        } else {
            emptyList()
        }
        val row = HandScoreRow(
            handNumber = rows.size + 1,
            handValue = handValue,
            winnerSeat = winnerSeat,
            discarderSeat = discarderSeat,
            deltas = deltas,
            runningTotals = totals.copyOf(),
            patterns = patterns,
        )
        rows += row
        return row
    }

    /**
     * Append an explicit settlement row (e.g. authoritative from server).
     * Updates internal totals and appends a new [HandScoreRow].
     */
    fun recordHand(
        handNumber: Int,
        handValue: Int,
        winnerSeat: Int,
        discarderSeat: Int,
        deltas: IntArray,
        runningTotals: IntArray? = null,
        patterns: List<HandScoreComponent> = emptyList(),
    ): HandScoreRow {
        val finalRunningTotals = if (runningTotals != null) {
            System.arraycopy(runningTotals, 0, totals, 0, NUM_PLAYERS)
            totals.copyOf()
        } else {
            for (p in 0 until NUM_PLAYERS) {
                totals[p] += deltas[p]
            }
            totals.copyOf()
        }
        val row = HandScoreRow(
            handNumber = handNumber,
            handValue = handValue,
            winnerSeat = winnerSeat,
            discarderSeat = discarderSeat,
            deltas = deltas.copyOf(),
            runningTotals = finalRunningTotals,
            patterns = patterns,
        )
        rows += row
        return row
    }
}
