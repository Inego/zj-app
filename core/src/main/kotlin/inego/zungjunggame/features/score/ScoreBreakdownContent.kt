package inego.zungjunggame.features.score

import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.seats.NameTone

/**
 * The hand-end card's content: one value so the headline, settlement slots,
 * CTAs, and rating lines cannot disagree.
 *
 * Projected onto [inego.zungjunggame.runtime.AppModel] from session settlement;
 * [ScoreBreakdownComponent] applies it. Fold / park / scroll stay on `ui.prefs`.
 */
data class ScoreBreakdownContent(
    val details: HandScoreDetails,
    /**
     * The header's verdict and the collapsed bar's title: the winner's seat name,
     * or the draw label. Painted bright, in the header's name column.
     */
    val headline: String,
    /**
     * The muted label before [headline] ("Winner:"), ending on the header's shared
     * label axis. Empty on a draw, whose [headline] is the whole line.
     */
    val headlineLabel: String = "",
    /** How the header paints [headline] — green when the human won the hand. */
    val headlineTone: NameTone = NameTone.Neutral,
    /**
     * The header's second line: the seat the winning tile came from. Empty on a
     * self-draw, whose whole phrase is [headlineDetailLabel], and on a draw.
     */
    val headlineDetail: String = "",
    /**
     * The muted label before [headlineDetail] ("From:"), or the whole self-draw
     * phrase — a label with no name, so it is right-aligned in the label column and
     * muted. Empty on a draw.
     */
    val headlineDetailLabel: String = "",
    /** How the "From:" name is painted — red when the human discarded to the winner. */
    val headlineDetailTone: NameTone = NameTone.Neutral,
    /**
     * How the winning tile arrived, as the collapsed bar's small second line
     * ("from Left", "self-draw"). Null on a draw.
     */
    val winSourceLabel: String? = null,
    val settlement: List<SettlementSeatLine>,
    val primaryAction: HandEndAction,
    val primaryLabel: String,
    val showActions: Boolean = true,
    val primaryEnabled: Boolean = true,
    val primaryTimeLeftMs: Long? = null,
    /** False hides the Scores chip (the reopened attempt card has no session ledger). */
    val showScoreTable: Boolean = true,
    val ratingLines: List<RatingLine> = emptyList(),
    val secondaryAction: HandEndAction? = null,
    val secondaryLabel: String? = null,
    val gameResult: String? = null,
    /**
     * How long the finished match or Shadow Ranked attempt took, as `mm:ss`. Null
     * while the game continues or when the server sent no usable stamps.
     */
    val durationLabel: String? = null,
    /**
     * The Shadow Ranked finish. Null for every other hand-end card. The card
     * paints it as its own table, below the ratings.
     */
    val comparison: ShadowRankedComparison? = null,
)
