package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.NonHumanPicker

/**
 * Queue of forced choices for non-human seats (tier A scripted opponents).
 * Matches against current legal list by kind+payload, then applies the engine object.
 */
class ScriptedChoicePicker : NonHumanPicker {
    private val queue = ArrayDeque<Choice>()

    fun clear() {
        queue.clear()
    }

    fun enqueue(choice: Choice) {
        queue.addLast(choice)
    }

    val pending: Int get() = queue.size

    override fun applyOne(state: GameState): Boolean {
        val requested = queue.firstOrNull() ?: return false
        val legal = state.enumerateChoices().asList()
        val match = legal.firstOrNull { BoardController.matches(it, requested) } ?: return false
        queue.removeFirst()
        state.applyChoice(match)
        return true
    }
}
