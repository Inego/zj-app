package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind

/**
 * Matches an engine [Choice] for wait_choice steps and scripted AI.
 * [payload] null means “any payload of this kind”.
 */
data class ChoiceMatch(
    val kind: ChoiceKind,
    val payload: Int? = null,
) {
    fun matches(choice: Choice): Boolean =
        choice.kind == kind && (payload == null || choice.payload == payload)

    companion object {
        fun pass(): ChoiceMatch = ChoiceMatch(ChoiceKind.Pass, payload = 0)
        fun discard(tile: Int): ChoiceMatch = ChoiceMatch(ChoiceKind.Discard, tile)
        fun pung(tile: Int? = null): ChoiceMatch = ChoiceMatch(ChoiceKind.Pung, tile)
        fun chow(firstTile: Int? = null): ChoiceMatch = ChoiceMatch(ChoiceKind.Chow, firstTile)
        fun kong(tile: Int? = null): ChoiceMatch = ChoiceMatch(ChoiceKind.Kong, tile)
        fun concealedKong(tile: Int? = null): ChoiceMatch = ChoiceMatch(ChoiceKind.ConcealedKong, tile)
        fun promoteKong(tile: Int? = null): ChoiceMatch = ChoiceMatch(ChoiceKind.PromoteKong, tile)
        fun hu(): ChoiceMatch = ChoiceMatch(ChoiceKind.Hu, payload = null)
        fun kind(kind: ChoiceKind): ChoiceMatch = ChoiceMatch(kind, payload = null)
    }
}
