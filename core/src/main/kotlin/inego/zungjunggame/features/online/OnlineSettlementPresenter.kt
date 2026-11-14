package inego.zungjunggame.features.online

import inego.zungjunggame.features.score.ScoreBreakdownContent
import inego.zungjunggame.features.score.SettlementSeatLine
import inego.zungjunggame.features.score.ShadowRankedComparison
import inego.zungjunggame.features.score.ShadowRankedComparisonRow
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.online.RatingSeatLine
import inego.zungjunggame.render.seats.NameTone
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.session.hosts.FreePlayBreakdownPort
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.snapshots.TableSnapshot

/**
 * Maps online table settlement messages and player ratings to the hand-end UI.
 *
 * Reads the immutable [TableSnapshot] (see [TableSnapshot.settlement]) so it never
 * touches the concrete online host. The mapped [ScoreBreakdownContent] is
 * projected onto [inego.zungjunggame.runtime.AppModel]; this presenter only
 * announces the breakdown route.
 */
class OnlineSettlementPresenter(
    private val breakdown: FreePlayBreakdownPort,
) {
    /**
     * Builds [ScoreBreakdownContent] from [snapshot]'s settlement, or null if
     * no settlement is active.
     */
    fun buildPresentation(snapshot: TableSnapshot): ScoreBreakdownContent? {
        val settlement = snapshot.settlement ?: return null
        val winner = settlement.winnerSeat ?: -1
        val human = snapshot.humanSeat
        val lastRow = settlement.scoreRows.lastOrNull()
        val discarder = lastRow?.discarderSeat?.takeIf { it >= 0 }
        val gameFinished = snapshot.matchPhase == MatchPhase.Finished
        // A finished Shadow Ranked attempt replaces the live-game result. Its place
        // among three bots is meaningless; the comparison section owns the outcome and
        // the bot's match total.
        val comparison = snapshot.shadowRankedBreakdown?.let(::shadowRankedComparison)
        // The card is about the hand, so its header names the hand's winner even
        // on the last hand of a finished match. The match outcome is the line under
        // it ([gameResult]) — replacing the headline with it hid who won the hand.
        // The label is a separate run so the name paints bright, and a draw's
        // verdict is its own label.
        val winTitle = if (winner >= 0) PlayerLabels.winTitle(winner, human) else null
        val winSource = if (winner >= 0) PlayerLabels.winSourceTitle(discarder, human) else null
        val headline = winTitle?.name ?: L10n.get("hand.draw")
        val headlineLabel = winTitle?.label.orEmpty()
        val headlineTone = winTitle?.tone ?: NameTone.Neutral
        val headlineDetail = winSource?.name.orEmpty()
        val headlineDetailLabel = winSource?.label.orEmpty()
        val headlineDetailTone = winSource?.tone ?: NameTone.Neutral
        // The collapsed bar shows the same two facts bare; a draw keeps one line.
        val winSourceLabel = if (winner >= 0) {
            PlayerLabels.winSourceLabel(discarder, human)
        } else {
            null
        }
        val gameResult = if (gameFinished && comparison == null) {
            MatchHistoryFormat.placementText(settlement.runningTotals.toIntArray(), human)
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
        // The finished match's wall-clock span, from the two `GameFinished` stamps.
        // A Shadow Ranked attempt is a finished game too, so it gets the line as well.
        val durationLabel = if (gameFinished) {
            MatchHistoryFormat.durationLabel(settlement.startedAt, settlement.finishedAt)
        } else {
            null
        }
        val (primaryAction, primaryLabel) =
            if (gameFinished) {
                HandEndAction.PlayAgain to L10n.get("hand.play_again")
            } else {
                HandEndAction.NextHand to L10n.get("hand.next")
            }
        val secondaryAction = if (gameFinished) HandEndAction.ReturnHome else null
        val secondaryLabel = if (gameFinished) L10n.get("hand.back_home") else null
        val settlementLines = SettlementSeatLine.build(lastRow, human)
        // The comparison section carries the rating movement, so the live-game
        // rating table (and its "rating pending" placeholder) stays off.
        val ratingLines = if (gameFinished && comparison == null) ratingLinesFor(snapshot) else emptyList()
        return ScoreBreakdownContent(
            details = settlement.details,
            headline = headline,
            headlineLabel = headlineLabel,
            headlineTone = headlineTone,
            headlineDetail = headlineDetail,
            headlineDetailLabel = headlineDetailLabel,
            headlineDetailTone = headlineDetailTone,
            winSourceLabel = winSourceLabel,
            settlement = settlementLines,
            primaryAction = primaryAction,
            primaryLabel = primaryLabel,
            primaryEnabled = gameFinished || !settlement.readyForNextHand,
            primaryTimeLeftMs = if (!gameFinished && !settlement.readyForNextHand) {
                settlement.nextHandTimeLeftMs
            } else {
                null
            },
            ratingLines = ratingLines,
            secondaryAction = secondaryAction,
            secondaryLabel = secondaryLabel,
            gameResult = gameResult,
            durationLabel = durationLabel,
            comparison = comparison,
        )
    }

    /**
     * Content the paint snapshot may carry: [buildPresentation] only after the
     * presentation sequencer has caught up (same idle gate as decision prompts).
     */
    fun paintContent(snapshot: TableSnapshot): ScoreBreakdownContent? {
        val settlement = snapshot.settlement ?: return null
        if (!settlement.sequencerCaughtUp) return null
        return buildPresentation(snapshot)
    }

    /**
     * Announces the breakdown route from [snapshot]'s settlement.
     *
     * Settlement is latched as soon as the server message arrives, but the
     * card waits until the presentation sequencer has played through any
     * preceding table animations (same idle gate as decision prompts).
     */
    fun sync(snapshot: TableSnapshot) {
        val settlement = snapshot.settlement
        if (settlement != null && !settlement.sequencerCaughtUp) return
        if (buildPresentation(snapshot) != null) {
            breakdown.show()
        } else {
            breakdown.hide()
        }
    }

    private fun ratingLinesFor(snapshot: TableSnapshot): List<RatingLine> {
        val settlement = snapshot.settlement ?: return emptyList()
        return RatingSeatLine.build(
            updates = settlement.ratingUpdates,
            humanSeat = snapshot.humanSeat,
            runningTotals = settlement.runningTotals,
        )
    }

    /**
     * The server's Shadow Ranked breakdown as table rows. The bot
     * is the nameless comparison. Number strings are formatted later, where the
     * columns are measured, so the widths and the glyphs stay the same text.
     */
    private fun shadowRankedComparison(
        msg: ServerMessageWire.ChallengeScoreBreakdown,
    ): ShadowRankedComparison {
        val comparisons = msg.comparisons.map { c ->
            val named = c.opponentName?.takeIf { it.isNotBlank() }
            ShadowRankedComparisonRow(
                name = named ?: L10n.get("seat.bot"),
                isBot = named == null,
                opponentRating = c.opponentRating,
                opponentScore = c.opponentScore,
                delta = c.delta,
            )
        }
        return ShadowRankedComparison(
            publicId = msg.publicId,
            playerScore = msg.totalScore,
            ratingBefore = msg.ratingBefore,
            ratingAfter = msg.ratingAfter,
            ratingDelta = msg.ratingDelta,
            comparisons = comparisons,
        )
    }
}
