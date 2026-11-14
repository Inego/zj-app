package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.ChoiceKind

/**
 * Where a board-overlay callout parks.
 *
 * [TutorialFocus.targetId] is the source of truth (`tile.N`, `river.last`,
 * `wall.live`, …). Claim-wait preambles override to the matching claim button
 * — that is the control the learner clicks. Discard preambles keep [targetId]
 * (the prompted hand tile). No [calloutKey] still falls back to a hand prompt
 * so hover-cursor can follow the outline.
 */
object TutorialLiveFocus {
    sealed class Park {
        data class ClaimButton(val kind: ChoiceKind, val payload: Int?) : Park()
        /** Park on the live-table rect named by [targetId]. */
        data class Target(val targetId: String) : Park()
        /** Prompted You copies (legacy / no-callout outline cursor). */
        data object HandPrompt : Park()
        data object None : Park()
    }

    fun resolve(
        targetId: String?,
        calloutKey: String?,
        waitKind: ChoiceKind?,
        waitPayload: Int? = null,
    ): Park {
        if (waitKind != null && waitKind != ChoiceKind.Discard) {
            return Park.ClaimButton(waitKind, waitPayload)
        }
        if (!calloutKey.isNullOrEmpty() && !targetId.isNullOrEmpty()) {
            return Park.Target(targetId)
        }
        return Park.HandPrompt
    }
}
