package inego.zungjunggame.features.ratinghistory

import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.RatingHistoryFailure
import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * Pure display copy for the rating chart: the title and the failure line.
 *
 * The values are formatted from E2's publication/failure only; nothing here
 * decides a state or touches the network. All copy lives in
 * `assets/i18n/ui.properties`.
 */
internal object RatingHistoryText {

    /**
     * The surface's title: the fetched display name when the publication named
     * one, else the target's generic screen title. A blank name is absence, not a
     * title, so it falls through rather than drawing an empty header.
     */
    fun title(target: RatingHistoryTarget?, displayName: String?): String {
        val name = displayName?.trim()
        if (!name.isNullOrEmpty()) return name
        return when (target) {
            RatingHistoryTarget.Self -> L10n.get("ratingHistory.open")
            is RatingHistoryTarget.Player, null -> L10n.get("ratingHistory.title")
        }
    }

    /** The failure line; kinds whose answer cannot change read differently. */
    fun error(failure: RatingHistoryFailure): String = when (failure) {
        is RatingHistoryFailure.Unauthorized -> L10n.get("ratingHistory.error.unauthorized")
        is RatingHistoryFailure.NotFound -> L10n.get("ratingHistory.error.not_found")
        is RatingHistoryFailure.Network,
        is RatingHistoryFailure.Timeout,
        -> L10n.get("ratingHistory.error.network")

        else -> L10n.get("ratingHistory.error")
    }
}
