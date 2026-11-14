package inego.zungjunggame.net

import inego.zungjunggame.i18n.L10n

/**
 * Every way the patternless-hand rule is put on screen, in one place.
 *
 * The rule has three audiences and each needs a different sentence:
 *
 * - **Settings rows** in the Online hub's Live and Shadow Ranked tabs put a
 *   [caption] on the left and a short answer beside it, exactly as the Rounds row
 *   beside them does. The answer is [settingValue] — `Yes` / `No` / `No pref.` —
 *   which only reads correctly next to the caption.
 * - **Self-contained lines and rows** — match history, Shadow Ranked attempts, the
 *   board HUD badge — have no caption to lean on, so
  *   they use [standalone], which names the rule outright. Only a game that was
  *   actually played qualifies: the server always seats a live game as either
  *   allowed or disallowed, so there is no third state to render.
 *
 * Call sites never choose a key. The distinction between the captioned answer and
 * the self-contained sentence is this object's whole job; spreading it back across
 * panels is what made the wording drift.
 */
object PatternlessRule {
    /** The settings-row caption: the question the row answers. */
    fun caption(): String = L10n.get("presence.chicken")

    /**
     * The short answer beside [caption], for a settings row.
     *
     * Delegates the two-state form to [settingValue]'s tri-state mapping so the
     * two-state and tri-state settings rows can never disagree about a key.
     */
    fun settingValue(allowChicken: Boolean): String =
        settingValue(if (allowChicken) QueueChicken.Allowed else QueueChicken.Disallowed)

    /** The short answer beside [caption], for a preference that may be unset. */
    fun settingValue(chicken: QueueChicken): String = when (chicken) {
        QueueChicken.Allowed -> L10n.get("presence.chicken_yes")
        QueueChicken.Disallowed -> L10n.get("presence.chicken_no")
        QueueChicken.Either -> L10n.get("presence.chicken_no_pref")
    }

    /**
     * The rule as a concrete game played it, with no caption needed.
     *
     * This is what every browsable list of games shows, and the board HUD badge.
     */
    fun standalone(allowChicken: Boolean): String =
            if (allowChicken) L10n.get("presence.chicken_allowed")
            else L10n.get("presence.chicken_disallowed")
    }
