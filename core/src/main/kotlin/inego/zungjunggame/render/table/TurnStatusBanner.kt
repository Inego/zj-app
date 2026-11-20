package inego.zungjunggame.render.table

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.table.TableViewState
import kotlin.math.ceil
import kotlin.math.min

/**
 * Center-table turn status line above the live-wall marker.
 *
 * The copy is **bottom-anchored** to the top of the remaining-count text
 * (`wallCountTopY`) and fitted to the felt above it. The block may descend over
 * the card backs behind the count — they sit above it — but never past the
 * number, so the remaining-tile count stays readable. The face keeps its size
 * while the band has room and shrinks together with its fallback when it does
 * not — a fixed offset from the pane centre only ever fit one line, and the
 * Spanish claim copy is three on a phone. On top of that fit, the face takes the
 * table's shared label scale, reduced by [STATUS_FACE_FACTOR]: this is status
 * copy rather than a felt counter, and the live-wall number keeps the full
 * shared size.
 *
 * Terminal hands (a win or an exhaustive draw) leave the center free for
 * [ScoreBreakdownOverlay], which owns the whole hand-end summary.
 * Tutorial board-overlay coaches skip this via [PlayTableView.render]
 * (`showTurnStatus = false`) so coach copy owns the center.
 */
object TurnStatusBanner {

    /** Air between the banner and the opposite hand. */
    private const val FELT_AIR_DP = 8f

    /**
     * Air between the banner and the wall count. Smaller than [FELT_AIR_DP]:
     * the banner may sit right on the card backs, with only enough room that
     * the number's ink (and its baked shadow) never meets the copy.
     */
    private const val COUNT_AIR_DP = 4f

    /**
     * The status line is copy, not a felt counter: it paints this much smaller
     * than the shared felt-label scale, while the live-wall number keeps the full
     * size. Applies to the whole block — status, decision clock and feedback.
     */
    private const val STATUS_FACE_FACTOR = 0.7f

    fun formatClock(remainingMs: Long, slackMs: Long?, baseMs: Long? = null): String {
        val remaining = remainingMs.coerceAtLeast(0L)
        if (remaining == 0L) return L10n.get("turn.time_expired")
        if (slackMs == null) {
            val seconds = ceil(remaining / 1000.0).toInt()
            return L10n.format("turn.time_left", seconds)
        }
        val slack = slackMs.coerceAtLeast(0L)
        val uncappedBase = (remaining - slack).coerceAtLeast(0L)
        val baseLeft = if (baseMs != null) uncappedBase.coerceAtMost(baseMs.coerceAtLeast(0L)) else uncappedBase
        val slackLeft = remaining.coerceAtMost(slack)
        val baseSec = ceil(baseLeft / 1000.0).toInt()
        val slackSec = ceil(slackLeft / 1000.0).toInt()
        return L10n.format("turn.time_left_slack", baseSec, slackSec)
    }

    /**
     * The clock line's copy for the current phase: the prep cue while the
     * presentation grace is still running, otherwise the countdown. [preparing]
     * is false whenever there is no clock at all.
     */
    fun clockText(
        preparing: Boolean,
        remainingMs: Long,
        slackMs: Long?,
        baseMs: Long? = null,
    ): String = if (preparing) {
        L10n.get("turn.preparing")
    } else {
        formatClock(remainingMs, slackMs, baseMs)
    }

    /**
     * Banner copy for [view] as seen by [humanSeat].
     *
     * GL-free so tests can assert the wording ([draw] owns only the layout).
     *
     * A seat is named only while its turn is *public*: on [DecisionType.ActOnHand]
     * the whole table watches that seat draw and discard. A claim window is the
     * opposite — the engine asks the three other seats one at a time, so naming
     * [TableViewState.currentPlayer] would show the table who is currently
     * deliberating and, by elimination, who has already answered, while a claim
     * has to be decided blind. The viewer's own claim is announced by
     * [humanClaiming] instead, so nothing is lost by staying anonymous.
     */
    fun statusText(
        view: TableViewState,
        humanSeat: Int,
        humanDiscarding: Boolean,
        humanClaiming: Boolean,
        connectionStatus: ConnectionStatus = ConnectionStatus.Local,
        feedback: String? = null,
        shutdownPhase: String? = null,
        shutdownMessage: String? = null,
    ): String = when {
        connectionStatus == ConnectionStatus.ReconnectRequired -> L10n.get("online.reconnect_required")
        connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Reconnecting ||
            connectionStatus == ConnectionStatus.Disconnected -> L10n.get("online.reconnecting_banner")
        feedback != null -> L10n.format("online.choice_rejected", feedback)
        shutdownPhase == "warning" -> {
            if (shutdownMessage.isNullOrBlank()) {
                L10n.get("online.shutdown_warning")
            } else {
                L10n.format("online.shutdown_warning_reason", shutdownMessage)
            }
        }
        view.isWaitingForOthers -> L10n.get("turn.waiting_for_others")
        humanClaiming -> L10n.get("turn.your_call")
        humanDiscarding -> L10n.get("turn.your_discard")
        // Relative seat (Right / Opposite / Left), not seat wind — winds stay on
        // the edge labels.
        view.decisionType == DecisionType.ActOnHand ->
            L10n.format("turn.act", PlayerLabels.nameForAbsoluteSeat(view.currentPlayer, humanSeat))
        else ->
            L10n.get("turn.claims_open")
    }

    /**
     * The banner's full text for this frame, including the second clock line when
     * one applies. GL-free; [draw] measures exactly this string, so the painted
     * line cannot disagree with the fitted block.
     */
    fun bannerText(
        view: TableViewState,
        humanSeat: Int,
        humanDiscarding: Boolean,
        humanClaiming: Boolean,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
        decisionPreparing: Boolean = false,
        connectionStatus: ConnectionStatus = ConnectionStatus.Local,
        feedback: String? = null,
        shutdownPhase: String? = null,
        shutdownRemainingMs: Long? = null,
        shutdownMessage: String? = null,
    ): String {
        val status = statusText(
            view = view,
            humanSeat = humanSeat,
            humanDiscarding = humanDiscarding,
            humanClaiming = humanClaiming,
            connectionStatus = connectionStatus,
            feedback = feedback,
            shutdownPhase = shutdownPhase,
            shutdownMessage = shutdownMessage,
        )
        return when {
            decisionTimeLeftMs != null &&
                !view.isWaitingForOthers &&
                (humanDiscarding || humanClaiming || view.isHumanActOnHandPanel) -> {
                "$status\n${clockText(decisionPreparing, decisionTimeLeftMs, decisionSlackMs, decisionBaseMs)}"
            }
            shutdownPhase == "warning" && shutdownRemainingMs != null -> {
                "$status\n${formatClock(shutdownRemainingMs, null)}"
            }
            else -> status
        }
    }

    /** Height of [text] wrapped to [wrapW] on [font], in world px. */
    private fun wrappedHeight(font: BitmapFont, text: String, wrapW: Float): Float =
        Fonts.wrappedHeight(font, text, wrapW, Align.center)

    /**
     * Paint the banner in the band between the live-wall count and the opposite
     * hand, shrinking the face when the copy is taller than the band.
     *
     * The block is bottom-anchored just above [wallCountTopY]: it may cover the
     * card backs that rise behind the count, but the remaining-tile number on
     * the front card stays clear whatever the copy length. [feltTopY] caps the
     * growth; a band too short for the natural height shrinks the face and its
     * fallback together, down to [Fonts.MIN_FIT_SCALE] rather than below a
     * readable size.
     *
     * [labelScale] is the table's shared felt-label scale (see
     * [inego.zungjunggame.render.seats.SeatLabelDrawer.tableLabelScale]). The
     * banner paints at [STATUS_FACE_FACTOR] of it, so it shrinks with the seats
     * instead of staying at the desktop size while staying subordinate to the
     * live-wall number, which keeps the full shared scale.
     *
     * @param wallCountTopY world Y of the top of the remaining-count text
     * @param feltTopY world Y of the opposite hand's inner edge
     * @param labelScale shared felt-label scale for this table
     */
    fun draw(
        batch: SpriteBatch,
        font: BitmapFont,
        view: TableViewState,
        screenW: Float,
        humanSeat: Int,
        humanDiscarding: Boolean,
        humanClaiming: Boolean,
        wallCountTopY: Float,
        feltTopY: Float,
        labelScale: Float = 1f,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
        decisionPreparing: Boolean = false,
        connectionStatus: ConnectionStatus = ConnectionStatus.Local,
        feedback: String? = null,
        shutdownPhase: String? = null,
        shutdownRemainingMs: Long? = null,
        shutdownMessage: String? = null,
    ) {
        // Terminal hands (win or exhaustive draw): the score breakdown owns the
        // summary + Scores / Next hand buttons.
        if (view.isTerminal) return

        val lines = bannerText(
            view = view,
            humanSeat = humanSeat,
            humanDiscarding = humanDiscarding,
            humanClaiming = humanClaiming,
            decisionTimeLeftMs = decisionTimeLeftMs,
            decisionSlackMs = decisionSlackMs,
            decisionBaseMs = decisionBaseMs,
            decisionPreparing = decisionPreparing,
            connectionStatus = connectionStatus,
            feedback = feedback,
            shutdownPhase = shutdownPhase,
            shutdownRemainingMs = shutdownRemainingMs,
            shutdownMessage = shutdownMessage,
        )
        // WHITE lives on the layout so draw ignores leftover [font.color] from coach fades.
        // [Align.center] already offsets glyphs inside [wrapW]; draw from the wrap-box left.
        val wrapW = screenW * 0.42f
        val cx = screenW * 0.5f
        val textBottom = wallCountTopY + Fonts.px(COUNT_AIR_DP)
        val bandH =
            (feltTopY - Fonts.px(FELT_AIR_DP) - textBottom).coerceAtLeast(1f)
        val bandFit = Fonts.fitScale(
            naturalW = wrappedHeight(font, lines, wrapW) * STATUS_FACE_FACTOR,
            availableW = bandH,
            min = Fonts.MIN_FIT_SCALE,
        )
        val scale = min(labelScale * STATUS_FACE_FACTOR, bandFit)
        Fonts.withFaceScale(font, scale) {
            val blockH = wrappedHeight(font, lines, wrapW)
            Fonts.drawWrapped(
                batch, font, lines,
                cx - wrapW * 0.5f, textBottom + blockH,
                wrapW, Align.center, Color.WHITE,
            )
        }
    }
}
