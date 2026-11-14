package inego.zungjunggame.engine

/**
 * Configuration for one Zung Jung **game** (a sequence of hands).
 *
 * A standard game is [DEFAULT_ROUNDS] rounds of [HANDS_PER_ROUND] hands each
 * (16 hands). Unlimited games have no hand cap.
 */
data class GameConfig(
    /**
     * Number of rounds when finite, or `null` for unlimited hands.
     * Each round is [HANDS_PER_ROUND] hands (dealer/East rotations).
     */
    val rounds: Int? = DEFAULT_ROUNDS,
    /** When false, pure chicken (1-point, no patterns) hands cannot declare Hu. */
    val allowChickenHands: Boolean = false,
) {
    init {
        require(rounds == null || rounds > 0) { "rounds must be positive or null" }
    }

    /** Total hands in a finite game, or `null` when unlimited. */
    val maxHands: Int?
        get() = rounds?.times(HANDS_PER_ROUND)

    companion object {
        const val DEFAULT_ROUNDS: Int = 4
        const val HANDS_PER_ROUND: Int = 4

        val DEFAULT: GameConfig = GameConfig()

        fun unlimited(allowChickenHands: Boolean = false): GameConfig =
            GameConfig(rounds = null, allowChickenHands = allowChickenHands)
    }
}
