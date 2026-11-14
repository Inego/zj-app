package inego.zungjunggame.session.board

import inego.zungjunggame.engine.Choice

/**
 * Filters the engine's legal [inego.zungjunggame.engine.Choice] list to the subset the UI / human path
 * may commit. Free play uses [Identity] (no filter). Tutorials install a
 * tighter gate on human decision steps so wrong gestures never call
 * [inego.zungjunggame.engine.GameState.applyChoice] with an illegal pick.
 *
 * Non-human seats should see [Identity] (or an equivalent pass-through) so
 * computer / scripted play is not accidentally constrained by a human lesson
 * gate — swap the gate when the director changes whose turn is gated.
 */
fun interface ChoiceGate {
    /** @return subset of [legal] that may be committed (must be elements from [legal]). */
    fun filter(legal: List<Choice>): List<Choice>

    companion object {
        /** Free-play default: every legal choice is allowed. */
        val Identity: ChoiceGate = ChoiceGate { legal -> legal }
    }
}
