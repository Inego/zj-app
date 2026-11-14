package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.session.board.ChoiceGate

/**
 * Allows only legal choices whose kind is in [allowKinds].
 * Used by [TutorialStep.Gate] on human decision steps.
 */
class KindChoiceGate(
    private val allowKinds: Set<ChoiceKind>,
) : ChoiceGate {
    override fun filter(legal: List<Choice>): List<Choice> =
        legal.filter { it.kind in allowKinds }
}
