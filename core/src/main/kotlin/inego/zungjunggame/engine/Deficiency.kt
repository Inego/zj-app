package inego.zungjunggame.engine

/**
 * Hand deficiency metrics for efficient-family engines.
 *
 * Ported from zj-self-play `engine/deficiency.*`.
 */
object Deficiency {
    /** Tiles in a complete Seven Pairs hand. */
    private const val SEVEN_PAIRS_TILES = 14

    private val TERMINAL_HONOR_TILES = intArrayOf(
        0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33,
    )

    /** Open melds already locked in (chows + pungs/kongs). Regular hands need 4 total. */
    fun countOpenMelds(player: PlayerState): Int {
        var groups = 0
        for (chowCount in player.openChows) groups += chowCount
        for (value in player.pungsKongs) {
            if (value != PlayerState.PK_NONE) ++groups
        }
        return groups
    }

    /**
     * Sets exposed before the win: open chows plus open pungs/kongs.
     *
     * Unlike [countOpenMelds] a concealed kong is excluded, because it is never exposed
     * to the other players and so does not break Concealed Hand (1.2).
     */
    fun countExposedMelds(player: PlayerState): Int {
        var groups = 0
        for (chowCount in player.openChows) groups += chowCount
        for (value in player.pungsKongs) {
            if (value == PlayerState.PK_OPEN_PUNG || value == PlayerState.PK_OPEN_KONG) ++groups
        }
        return groups
    }


    /**
     * Deficiency toward Seven Pairs (closed only). -1 if complete.
     *
     * Zung Jung counts four identical tiles as two pairs, so a hand is complete
     * exactly when every count is even: distinctness is not required. A 13-tile
     * hand is tenpai when exactly one type has an odd count, and each further
     * pair of odd types costs one turn, because a draw and a discard flip the
     * parity of two types. That makes the deficiency `(oddTypes - 1) / 2`.
     */
    fun sevenPairsDeficiency(counts: IntArray): Int {
        var total = 0
        var oddTypes = 0
        for (t in 0 until NUM_TILE_TYPES) {
            val n = counts[t]
            total += n
            if (n and 1 == 1) ++oddTypes
        }

        if (oddTypes == 0) {
            if (total == SEVEN_PAIRS_TILES) return -1
            // Not a position a closed hand can hold (a kong is a meld), but keep
            // the scale meaningful for a direct caller.
            return (SEVEN_PAIRS_TILES - total + 1) / 2
        }
        return (oddTypes - 1) / 2
    }

    /** Deficiency toward Thirteen Terminals (closed only). -1 if complete. */
    fun thirteenTerminalsDeficiency(counts: IntArray): Int {
        if (HuCheck.isThirteenTerminalsHand(counts)) return -1

        var unique = 0
        var hasPair = false
        for (tile in TERMINAL_HONOR_TILES) {
            val n = counts[tile]
            if (n <= 0) continue
            ++unique
            if (n >= 2) hasPair = true
        }

        return 13 - unique - if (hasPair) 1 else 0
    }

}
