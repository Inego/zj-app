package inego.zungjunggame.features.score

import inego.zungjunggame.render.ui.AppPalette

/**
 * The Shadow Ranked finish on the hand-end card.
 *
 * Each [ShadowRankedComparisonRow] is one opponent: their rating, their match
 * total, and what that comparison did to the finisher's rating. [playerScore] is
 * the finisher's own match total, painted once on the You row. [ratingBefore] is
 * that row's rating. [ratingDelta] is that row's Δ, the finish total the server
 * sent. [ratingAfter] is the line under the row. The card measures and paints
 * these fields.
 *
 * The live finish always carries every field. A comparison rebuilt from the
 * attempts list does not: [ratingBefore], [ratingAfter] and each row's
 * `opponentRating`/`delta` are null and the card renders only what exists.
 */
data class ShadowRankedComparison(
    /** Display id, e.g. `7K3H-M9PQ`. The table's caption. */
    val publicId: String,
    /** The finisher's match total. The table's score-column footer. */
    val playerScore: Int,
    val ratingBefore: Double?,
    val ratingAfter: Double?,
    /** The finisher's total rating change for this finish. The You row's Δ. */
    val ratingDelta: Double,
    val comparisons: List<ShadowRankedComparisonRow>,
)

/**
 * One opponent in a finished Shadow Ranked attempt.
 *
 * [opponentScore] is their match total. [delta] is this comparison's contribution
 * to the finisher's rating, absent when the comparison was rebuilt from the
 * attempts list, which only knows the opponents' totals.
 */
data class ShadowRankedComparisonRow(
    /** Opponent display name, or the localized bot name when the wire sent none. */
    val name: String,
    val isBot: Boolean,
    val opponentRating: Double?,
    val opponentScore: Int,
    /** Signed rating change, for [AppPalette.deltaInk]. */
    val delta: Double?,
)
