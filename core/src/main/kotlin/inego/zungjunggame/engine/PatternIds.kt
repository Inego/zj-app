package inego.zungjunggame.engine

/**
 * Stable pattern / scoring-line ids used in [HandScoreComponent.id].
 *
 * Values are **reference page ids** (and matching L10n keys in
 * `assets/i18n/ui*.properties`). They match the ids in
 * `assets/reference/structure.json` so the hand-end score breakdown can
 * deep-link each line with no mapping.
 *
 * Engine state must never store English display labels.
 */
object PatternIds {
    /** Optional house rule (not a numbered v3.3 pattern). */
    const val CHICKEN_HAND = "scoring-chicken-hand"

    const val ALL_SEQUENCES = "pattern-1.1"
    const val CONCEALED_HAND = "pattern-1.2"
    const val NO_TERMINALS = "pattern-1.3"

    const val MIXED_ONE_SUIT = "pattern-2.1.1"
    const val PURE_ONE_SUIT = "pattern-2.1.2"
    const val NINE_GATES = "pattern-2.2"

    /** MessageFormat arg: number of value-honor sets. */
    const val VALUE_HONOR_SETS = "pattern-3.1"
    const val SMALL_THREE_DRAGONS = "pattern-3.2.1"
    const val BIG_THREE_DRAGONS = "pattern-3.2.2"
    const val SMALL_THREE_WINDS = "pattern-3.3.1"
    const val BIG_THREE_WINDS = "pattern-3.3.2"
    const val SMALL_FOUR_WINDS = "pattern-3.3.3"
    const val BIG_FOUR_WINDS = "pattern-3.3.4"
    const val ALL_HONORS = "pattern-3.4"

    const val ALL_TRIPLETS = "pattern-4.1"
    const val TWO_CONCEALED_TRIPLETS = "pattern-4.2.1"
    const val THREE_CONCEALED_TRIPLETS = "pattern-4.2.2"
    const val FOUR_CONCEALED_TRIPLETS = "pattern-4.2.3"
    const val ONE_KONG = "pattern-4.3.1"
    const val TWO_KONGS = "pattern-4.3.2"
    const val THREE_KONGS = "pattern-4.3.3"
    const val FOUR_KONGS = "pattern-4.3.4"

    const val TWO_IDENTICAL_SEQUENCES = "pattern-5.1.1"
    const val TWO_IDENTICAL_SEQUENCES_TWICE = "pattern-5.1.2"
    const val THREE_IDENTICAL_SEQUENCES = "pattern-5.1.3"
    const val FOUR_IDENTICAL_SEQUENCES = "pattern-5.1.4"

    const val THREE_SIMILAR_SEQUENCES = "pattern-6.1"
    const val SMALL_THREE_SIMILAR_TRIPLETS = "pattern-6.2.1"
    const val THREE_SIMILAR_TRIPLETS = "pattern-6.2.2"

    const val NINE_TILE_STRAIGHT = "pattern-7.1"
    const val THREE_CONSECUTIVE_TRIPLETS = "pattern-7.2.1"
    const val FOUR_CONSECUTIVE_TRIPLETS = "pattern-7.2.2"

    const val MIXED_LESSER_TERMINALS = "pattern-8.1.1"
    const val PURE_LESSER_TERMINALS = "pattern-8.1.2"
    const val MIXED_GREATER_TERMINALS = "pattern-8.1.3"
    const val PURE_GREATER_TERMINALS = "pattern-8.1.4"

    const val FINAL_DRAW = "pattern-9.1.1"
    const val FINAL_DISCARD = "pattern-9.1.2"
    const val WIN_ON_KONG = "pattern-9.2"
    const val ROBBING_A_KONG = "pattern-9.3"
    const val BLESSING_OF_HEAVEN = "pattern-9.4.1"
    const val BLESSING_OF_EARTH = "pattern-9.4.2"

    const val THIRTEEN_TERMINALS = "pattern-10.1"
    const val SEVEN_PAIRS = "pattern-10.2"

    /**
     * Defensive fallbacks if a group scorer returns an unexpected point total.
     * Ids point at the series parent page so the score UI can still deep-link.
     */
    const val TERMINALS_GROUP = "pattern-8.1"
    const val IDENTICAL_SEQUENCES = "pattern-5.1"
    const val SIMILAR_TRIPLETS = "pattern-6.2"
    const val CONSECUTIVE_TRIPLETS = "pattern-7.2"
    const val KONGS = "pattern-4.3"
    const val CONCEALED_TRIPLETS = "pattern-4.2"
    const val WINDS = "pattern-3.3"
    const val DRAGONS = "pattern-3.2"
}
