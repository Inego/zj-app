package inego.zungjunggame.features.board

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceList
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.Scoring
import inego.zungjunggame.engines.PlayingEngine
import inego.zungjunggame.hints.UcbFlatMcHintSearch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.PatternlessRule
import inego.zungjunggame.platform.McHints
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.hint.HintUi
import inego.zungjunggame.render.online.LatencyLabel
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.seats.NameTone
import inego.zungjunggame.features.score.ScoreBreakdownContent
import inego.zungjunggame.features.score.SettlementSeatLine
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.hosts.FreePlayBreakdownPort
import inego.zungjunggame.session.GameSession
import inego.zungjunggame.session.snapshots.HandSnapshot
import inego.zungjunggame.session.LiveHand
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.snapshots.formatHandStatusLabel
import inego.zungjunggame.render.ui.AppPalette

/**
 * Presentation side of free play: top-right status badge, hand-end score
 * breakdown, and the desktop MC hint lifecycle.
 *
 * Lifted out of `session.FreePlayHost` so `session/` stays free of render
 * imports. Hand-end content is projected onto
 * [inego.zungjunggame.runtime.AppModel]; this presenter announces the
 * breakdown route. Session state remains on [GameSession] / [LiveHand].
 */
class FreePlayPresenter(
    private val session: GameSession,
    private val table: PlayTableView,
    private val breakdown: FreePlayBreakdownPort,
    private val board: BoardController,
    private val engine: PlayingEngine,
    private val sessionKind: () -> SessionKind,
    private val playHintChoice: (Choice) -> Boolean,
) {
    /**
     * Background sequential UCB flat MC for the HINT button. Started only where
     * [McHints] allows it; never on the render thread.
     */
    private val mcHintSearch = UcbFlatMcHintSearch(engine = engine)

    /** Accumulator for ~1 s MC label / highlight refresh. */
    private var mcHintUiRefreshSec: Float = 0f

    private val statusGlyph = GlyphLayout()
    private val ruleGlyph = GlyphLayout()

    private companion object {
        const val MC_HINT_UI_REFRESH_SEC = 1.0f
    }

    /**
     * Hand-end content for a terminal board — a win or an exhaustive draw — or
     * null when the card should not be up. Both outcomes own the same card: a
     * draw is simply the zero-pattern, zero-payment case, so its settlement still
     * shows every seat's running total instead of a bare center banner. Free-play:
     * the primary CTA flips to New game after the last hand is recorded. Tutorial:
     * same pattern card + settlement (no Scores / Next hand — coach Next
     * continues).
     */
    fun buildPresentation(hand: HandSnapshot): ScoreBreakdownContent? {
        val kind = sessionKind()
        if (kind != SessionKind.FreePlay && kind != SessionKind.Tutorial) return null
        val state = hand.state
        if (!hand.active || state == null) return null
        if (!state.isTerminal()) return null
        val human = hand.humanSeat
        val winnerSeat = if (state.handScore > 0) state.currentPlayer else null
        val discarder = if (winnerSeat != null &&
            state.decisionType == DecisionType.ReactToDiscard &&
            state.lastDiscarder in 0 until NUM_PLAYERS
        ) {
            state.lastDiscarder
        } else {
            null
        }
        // The card header labels the verdict separately from the name, so the
        // name can be painted bright and the label muted. A draw's verdict is its
        // own label. The collapsed bar shows the same winner name (or draw label).
        val winTitle = winnerSeat?.let { PlayerLabels.winTitle(it, human) }
        val winSource = winnerSeat?.let { PlayerLabels.winSourceTitle(discarder, human) }
        val headline = winTitle?.name ?: L10n.get("hand.draw")
        val headlineLabel = winTitle?.label.orEmpty()
        val headlineTone = winTitle?.tone ?: NameTone.Neutral
        val headlineDetail = winSource?.name.orEmpty()
        val headlineDetailLabel = winSource?.label.orEmpty()
        val headlineDetailTone = winSource?.tone ?: NameTone.Neutral
        val winSourceLabel = winnerSeat?.let { PlayerLabels.winSourceLabel(discarder, human) }
        if (kind == SessionKind.Tutorial) {
            val deltas = state.computeScoreDeltas()
            return ScoreBreakdownContent(
                details = Scoring.computeHandScoreDetails(state),
                headline = headline,
                headlineLabel = headlineLabel,
                headlineTone = headlineTone,
                headlineDetail = headlineDetail,
                headlineDetailLabel = headlineDetailLabel,
                headlineDetailTone = headlineDetailTone,
                winSourceLabel = winSourceLabel,
                settlement = SettlementSeatLine.build(deltas, deltas, human),
                primaryAction = HandEndAction.NextHand,
                primaryLabel = "",
                showActions = false,
            )
        }
        val (primaryAction, primaryLabel) =
            if (session.isGameComplete() || session.isLastHandOfGame()) {
                HandEndAction.NewGame to L10n.get("hand.new_game")
            } else {
                HandEndAction.NextHand to L10n.get("hand.next")
            }
        return ScoreBreakdownContent(
            details = Scoring.computeHandScoreDetails(state),
            headline = headline,
            headlineLabel = headlineLabel,
            headlineTone = headlineTone,
            headlineDetail = headlineDetail,
            headlineDetailLabel = headlineDetailLabel,
            headlineDetailTone = headlineDetailTone,
            winSourceLabel = winSourceLabel,
            settlement = SettlementSeatLine.build(session.scores.rows().lastOrNull(), human),
            primaryAction = primaryAction,
            primaryLabel = primaryLabel,
        )
    }

    /**
     * Announce the breakdown route for a terminal board (win or exhaustive draw).
     */
    fun syncHandEndBreakdown(liveHand: LiveHand) {
        if (buildPresentation(HandSnapshot.from(liveHand)) != null) {
            breakdown.show()
        } else {
            breakdown.hide()
        }
    }

    /**
     * Top-right status: "N/M" when finite (the template carries the numbers
     * alone — no "Hand"/"Раздача" — so the mobile HUD stays narrow), "Hand N"
     * when unlimited, where a lone number has no denominator to explain it.
     * Tutorial shows a fixed label. Online adds a ping line under the badge from
     * existing heartbeat RTT.
     *
     * [labelScale] is the table's shared felt-label scale (see
     * [inego.zungjunggame.render.table.PlayTableView.chromeScaleFor]). Every HUD
     * line uses it, so the rule badge, hand counter, ping and shutdown clock
     * shrink with the tiles and the seat labels instead of keeping their desktop
     * size in a narrow window.
     */
    fun renderHandStatusBadge(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        sessionKind: SessionKind,
        boardHosted: Boolean,
        labelScale: Float,
        playHost: PlayHost? = null,
        latencyMs: Int? = null,
    ) {
        if (!boardHosted || sessionKind == SessionKind.Tutorial) return
        Fonts.ensureCreated()
        val font = Fonts.label
        val pad = Fonts.px(16f)
        val safe = SafeArea.current()
        val topPad = kotlin.math.max(pad, safe.top + pad)
        val leftPad = kotlin.math.max(pad, safe.left + pad)
        // The board menu button owns the far top-right; text stacks left of it.
        val hudRight = BoardMenuChrome.hudRightEdge(screenW, screenH, safe)
        val handBaselineY = screenH - topPad

        // Top-left rule badge: "Patternless OK" or "Min. 5 pts"
        val allowChicken = playHost?.allowChickenHands ?: session.gameConfig.allowChickenHands
        val ruleText = PatternlessRule.standalone(allowChicken)

        val label = formatHandStatusLabel(
            sessionKind = sessionKind,
            handNumber = playHost?.currentHandNumber ?: session.currentHandNumber(),
            maxHands = playHost?.maxHands ?: session.gameConfig.maxHands,
        )

        Fonts.withFaceScale(font, labelScale) {
            ruleGlyph.setText(font, ruleText)
            Fonts.drawText(batch, font, ruleText, leftPad, handBaselineY, font.color)

            statusGlyph.setText(font, label)
            val statusX = hudRight - statusGlyph.width
            Fonts.drawText(batch, font, label, statusX, handBaselineY, font.color)
        }

        var nextBaselineY = handBaselineY - statusGlyph.height
        val gapPx = Fonts.px(4f)
        if (sessionKind == SessionKind.Online && latencyMs != null) {
            val ping = LatencyLabel.text(latencyMs)
            val pingFont = Fonts.small
            val pingBaselineY = nextBaselineY - gapPx
            val pingH = Fonts.withFaceScale(pingFont, labelScale) {
                val pingW = Fonts.textWidth(pingFont, ping)
                val left = (hudRight - pingW).coerceAtLeast(pad)
                Fonts.drawText(batch, pingFont, ping, left, pingBaselineY, LatencyLabel.color(latencyMs))
                Fonts.textHeight(pingFont, ping)
            }
            nextBaselineY = pingBaselineY - pingH
        }

        val phase = playHost?.shutdownPhase
        val remainingMs = playHost?.shutdownRemainingMs
        if (sessionKind == SessionKind.Online && phase != null && remainingMs != null) {
            val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
            val mm = totalSeconds / 60L
            val ss = totalSeconds % 60L
            val timeStr = "%d:%02d".format(mm, ss)
            val shutdownText = L10n.format("online.shutdown_label", timeStr)
            val shutdownFont = Fonts.small
            val shutdownBaselineY = nextBaselineY - gapPx
            val color = if (phase == "final") {
                AppPalette.danger
            } else {
                AppPalette.warning
            }
            Fonts.withFaceScale(shutdownFont, labelScale) {
                val shutdownW = Fonts.textWidth(shutdownFont, shutdownText)
                val left = (hudRight - shutdownW).coerceAtLeast(pad)
                Fonts.drawText(batch, shutdownFont, shutdownText, left, shutdownBaselineY, color)
            }
        }
    }

    /**
     * HINT / H lifecycle:
     * - Off → classic [PlayingEngine.rank] co-best highlight immediately, and
     *   start background MC for every legal choice where the platform runs it
     *   ([McHints]: desktop only)
     * - After the first 1 s refresh (and each later 1 s): show avgΔ / n labels and
     *   switch highlight to best average score-delta arm(s)
     * - unique highlight → [HintUi.Active]; second press plays it
     * - multi highlight → [HintUi.Exhausted] (button greys; player chooses)
     * - Exhausted → further presses ignored until the turn clears the hint
     *
     * Where MC is off (Android, WasmGC) the classic co-best highlight is the whole
     * hint: no labels, no worker thread.
     */
    fun highlightEngineHint(liveHand: LiveHand) {
        // MC hints are free-play only.
        if (sessionKind() != SessionKind.FreePlay) return
        val state = liveHand.state ?: return
        if (!TableViewState.fromGameState(state, liveHand.humanSeat).isHumanInteractiveTurn) return
        // Hint surface follows the same gate as human commits.
        val allowed = board.allowedChoices(state)
        if (allowed.isEmpty()) return
        val allowedList = BoardController.toChoiceList(allowed)

        when (val hint = table.hintUi) {
            is HintUi.Exhausted -> return
            is HintUi.Active -> {
                val rec = hint.choices[0]
                if (allowed.any { BoardController.matches(it, rec) }) {
                    playHintChoice(rec)
                }
            }
            HintUi.Off -> {
                startMcHintSearch(liveHand, state, allowedList)
            }
        }
    }

    private fun startMcHintSearch(liveHand: LiveHand, state: GameState, choices: ChoiceList) {
        if (sessionKind() != SessionKind.FreePlay) return
        // Classic DefaultEngine co-best: instant, and the whole hint wherever MC
        // playouts are off ([McHints]).
        val indices = engine.rank(state, choices)
        val classic = ArrayList<Choice>(indices.size)
        for (i in indices) {
            if (i in 0 until choices.size) classic.add(choices[i])
        }
        table.setMcHintArms(emptyList())
        table.activateHint(classic)
        if (!McHints.enabled) return
        mcHintSearch.start(
            liveState = state,
            rootChoices = choices.asList(),
            humanSeat = liveHand.humanSeat,
            rngSeed = liveHand.choiceRng.nextLong(),
        )
        mcHintUiRefreshSec = 0f
    }

    fun stopMcHintSearch() {
        mcHintSearch.stop()
        mcHintSearch.clearSnapshot()
        mcHintUiRefreshSec = 0f
        table.clearRecommendation()
    }

    /**
     * Pull published MC stats onto the table and switch highlights to best avgΔ.
     * Called only on the 1 s UI refresh cadence (not on Hint press).
     */
    private fun applyMcHintSnapshotToTable() {
        val snap = mcHintSearch.snapshot()
        if (snap == null) {
            table.setMcHintArms(emptyList())
            return
        }
        table.setMcHintArms(snap.arms)
        val best = snap.bestChoices()
        if (best.isNotEmpty()) {
            table.activateHint(best)
        }
    }

    /**
     * While MC is running, refresh labels / highlights every [MC_HINT_UI_REFRESH_SEC].
     * Also stops the worker if the turn is no longer interactive.
     */
    fun tickMcHintUi(deltaSec: Float, liveHand: LiveHand) {
        if (!mcHintSearch.isRunning && table.mcHintArms.isEmpty() && table.hintUi is HintUi.Off) {
            return
        }
        val state = liveHand.state
        if (state == null || !liveHand.active ||
            !TableViewState.fromGameState(state, liveHand.humanSeat).isHumanInteractiveTurn
        ) {
            if (mcHintSearch.isRunning || table.mcHintArms.isNotEmpty() || table.hintUi !is HintUi.Off) {
                stopMcHintSearch()
            }
            return
        }
        if (!mcHintSearch.isRunning) return
        mcHintUiRefreshSec += deltaSec
        if (mcHintUiRefreshSec >= MC_HINT_UI_REFRESH_SEC) {
            mcHintUiRefreshSec = 0f
            applyMcHintSnapshotToTable()
        }
    }
}
