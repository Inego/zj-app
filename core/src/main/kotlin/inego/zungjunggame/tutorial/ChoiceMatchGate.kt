package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.session.board.ChoiceGate

/**
 * Allows only legal choices that satisfy [match] (kind + optional payload).
 * Installed while the director waits on [TutorialStep.WaitChoice] so Main's
 * shared [inego.zungjunggame.session.board.BoardController] rejects wrong targets.
 */
class ChoiceMatchGate(
    private val match: ChoiceMatch,
) : ChoiceGate {
    override fun filter(legal: List<Choice>): List<Choice> =
        legal.filter { match.matches(it) }
}
