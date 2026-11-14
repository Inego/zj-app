package inego.zungjunggame.render.online

import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.net.PlayerRatingUpdateWire
import inego.zungjunggame.render.seats.PlayerLabels
import kotlin.math.roundToInt

/**
 * One player's rating line in the post-game rating table: name, rating before
 * the match, delta, and new rating after.
 */
data class RatingSeatLine(
    val name: String,
    val before: Int,
    val delta: Int,
    val after: Int,
    /** True for the local human seat — row is highlighted. */
    val isHuman: Boolean,
    val isBot: Boolean = false,
    val relativeSeat: Int = 0,
    /**
     * True when the server has not yet sent a rating update — show "Rating pending"
     * rather than fabricated numbers.
     */
    val pending: Boolean = false,
    val cumScore: Int? = null,
) {
    val oldRating: Int get() = before
    val newRating: Int get() = after

    companion object {
        val COMPARATOR: Comparator<RatingSeatLine> =
            compareByDescending<RatingSeatLine> { it.cumScore ?: it.after }
                .thenBy { it.relativeSeat }

        /**
         * Builds the sorted rating lines for the post-game rating table.
         *
         * Ordered uniformly with the hand score deltas table:
         * 1. Cumulative score (or rating if score is absent) decreasing.
         * 2. Counterclockwise position relative to the player, starting from the player
         *    (0 = You, 1 = Right, 2 = Opposite, 3 = Left).
         */
        fun build(
            updates: List<PlayerRatingUpdateWire>,
            humanSeat: Int,
            runningTotals: IntArray? = null,
        ): List<RatingSeatLine> {
            val mine = updates.firstOrNull { it.seat == humanSeat }
            val humanLine = if (mine != null) {
                fromWire(mine, humanSeat, runningTotals?.getOrNull(mine.seat))
            } else {
                RatingSeatLine(
                    name = PlayerLabels.nameForAbsoluteSeat(humanSeat, humanSeat),
                    before = 0,
                    delta = 0,
                    after = 0,
                    isHuman = true,
                    isBot = false,
                    relativeSeat = 0,
                    pending = true,
                    cumScore = runningTotals?.getOrNull(humanSeat),
                )
            }

            val others = updates
                .filter { it.seat != humanSeat && !PlayerLabels.isBot(it.seat) }
                .map { update ->
                    fromWire(update, humanSeat, runningTotals?.getOrNull(update.seat))
                }

            return (listOf(humanLine) + others).sortedWith(COMPARATOR)
        }

        fun build(
            updates: List<PlayerRatingUpdateWire>,
            humanSeat: Int,
            runningTotals: List<Int>?,
        ): List<RatingSeatLine> =
            build(updates, humanSeat, runningTotals?.toIntArray())

        private fun fromWire(
            update: PlayerRatingUpdateWire,
            humanSeat: Int,
            cumScore: Int?,
        ): RatingSeatLine {
            val oldRounded = update.oldRating.roundToInt()
            val newRounded = update.newRating.roundToInt()
            val rel = (update.seat - humanSeat + NUM_PLAYERS) % NUM_PLAYERS
            return RatingSeatLine(
                name = PlayerLabels.nameForAbsoluteSeat(update.seat, humanSeat),
                before = oldRounded,
                // The server's delta, rounded once. Subtracting the two rounded
                // ratings instead reported +9 for the +8.25 this match awarded.
                delta = update.delta.roundToInt(),
                after = newRounded,
                isHuman = update.seat == humanSeat,
                isBot = PlayerLabels.isBot(update.seat),
                relativeSeat = rel,
                pending = false,
                cumScore = cumScore,
            )
        }
    }
}

typealias RatingLine = RatingSeatLine
