package inego.zungjunggame.features.confirm

import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.Route

/**
 * The questions the shared confirm card asks.
 *
 * The card keeps no state of its own: **the question is the layer**. It travels
 * in the confirm route's args ([ARG]), which the stack persists with the entry, so
 * the one record of what is up carries the card and what its Yes means together —
 * a card restored from a snapshot asks the same question and answers it the same
 * way. Each question's wording ([titleKey]) is derived here rather than stored
 * beside it, so a question and its text cannot disagree.
 *
 * The constant's name is the value written into the arg, which makes it part of
 * the stored stack: renaming one leaves a stored card unreadable, and an
 * unreadable entry is the landing the boot accepts — it keeps the root floor
 * rather than migrating anything local.
 */
enum class ConfirmPrompt(
    /** L10n key of the question this card shows. */
    val titleKey: String,
) {
    /** Abandon the running hand. */
    AbandonHand(
        titleKey = "confirm.abandon_hand",
    ),
    /** Start the running lesson over from its first step. */
    RestartLesson(
        titleKey = "confirm.restart_lesson",
    ),
    /** Leave the signed-in account. */
    LogOut(
        titleKey = "confirm.log_out",
    ),
    /** Leave the online table for good. */
    Forfeit(
        titleKey = "confirm.forfeit",
    ),
    ;

    /** The route that asks this question. */
    fun route(): Route.Modal = Route.Modal(RouteIds.CONFIRM, mapOf(ARG to name))

    companion object {
        /** Route arg holding the question; written by [route], read by [of]. */
        const val ARG: String = "prompt"

        /** The question [route] asks, or null when it carries none. */
        fun of(route: Route): ConfirmPrompt? =
            route.args[ARG]?.let { value -> entries.firstOrNull { it.name == value } }
    }
}
