package inego.zungjunggame.features.score

import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.ui.HandsTableText

/**
 * The score card's localized strings and the formatters the layout pass also
 * needs (column widths depend on the same strings the painter draws).
 *
 * Colours are deliberately absent: every painter reads its roles from
 * [inego.zungjunggame.render.ui.AppPalette], the UI's only colour authority.
 */
internal object ScoreBreakdownText {
    /** Plus only for positive deltas; absolute scores stay unsigned. */
    fun formatSignedDelta(v: Int): String = HandsTableText.formatSignedDelta(v)

    /** Resolve [HandScoreComponent.id] (+ optional MessageFormat args) for the active locale. */
    fun patternDisplayName(c: HandScoreComponent): String = HandsTableText.componentName(c)

    fun ratingDisplayText(line: RatingLine): String = when {
        line.pending -> L10n.get("history.rating_pending")
        line.isHuman -> L10n.format(
            "hand.rating_self",
            MatchHistoryFormat.ratingLabel(line.oldRating),
            MatchHistoryFormat.ratingLabel(line.newRating),
            formatSignedDelta(line.delta),
        )
        else -> L10n.format(
            "hand.rating_line",
            line.name,
            MatchHistoryFormat.ratingLabel(line.oldRating),
            MatchHistoryFormat.ratingLabel(line.newRating),
            formatSignedDelta(line.delta),
        )
    }
}
