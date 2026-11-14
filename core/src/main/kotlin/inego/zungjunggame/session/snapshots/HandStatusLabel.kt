package inego.zungjunggame.session.snapshots

import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.i18n.L10n

/**
 * The center status badge's label for the hand in progress.
 *
 * Pure read-model formatting over values, so it lives in `session/` beside the
 * snapshots rather than on `FreePlayHost`: the board presenter (a feature) can
 * format the badge without importing a concrete host.
 */
fun formatHandStatusLabel(
    sessionKind: SessionKind,
    handNumber: Int,
    maxHands: Int?,
): String {
    if (sessionKind == SessionKind.Tutorial) {
        return L10n.get("status.tutorial")
    }
    return if (maxHands != null) {
        L10n.format("status.hand_of", handNumber, maxHands)
    } else {
        L10n.format("status.hand", handNumber)
    }
}
