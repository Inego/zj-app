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
     * Tutorial shows a fixed label.
     *
     * The session detail — the online ping, or the restart clock once a shutdown
     * is announced — and the counter share **one line**, so the whole badge sits
     * in the free strip above the Opposite hand. It is right-aligned to
     * [rightBandInnerX], the Right seat's nominal tile band's inner face, not to
     * the menu button: the button is density-scaled chrome in the corner, while
     * the column is a short-side proportion, and anchoring to the button put the
     * digits on the outermost meld of the Right hand.
     *
     * The rule badge ([PatternlessRule]) is the counter's mirror image. In
     * landscape the Left seat is on the short edge, so its hand runs the full
     * height through the top-left corner: the badge sits on the felt right of it,
     * left-aligned to [leftBandInnerX], and the counter sits left of the Right
     * band. In portrait the table publishes a dedicated [PlayTableView.HudBand]
     * under the menu ([hud]): the whole line hangs below the button, the rule
     * badge at the window's left safe edge and the counter at its right safe
     * edge, and the Opposite seat's label and row start below the band. Either
     * way the row anchors come from nominal geometry, so a claim rescaling a row
     * never drags the badges.
     *
     * The detail comes **first** and the counter last, flush against the anchor.
     * The ping is volatile (2↔3 digits), the hand number is not; putting the
     * counter on the right keeps it from sliding horizontally as the ping
     * changes.
     *
     * [labelScale] is the table's shared felt-label scale (see
     * [inego.zungjunggame.render.table.PlayTableView.feltChrome]). Every HUD
     * line uses it, so the rule badge, hand counter and detail shrink with the
     * tiles and the seat labels instead of keeping their desktop size in a
     * narrow window.
     */
    fun renderHandStatusBadge(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        leftBandInnerX: Float,
        rightBandInnerX: Float,
        hud: PlayTableView.HudBand? = null,
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
        // The portrait band places the whole line under the menu, edge to edge.
        // Landscape has no band: stop a gap short of the Left column on the felt
        // side, like the counter at the other end.
        val hudLeft = hud?.leftX ?: if (screenW > screenH) {
            leftBandInnerX + Fonts.px(10f)
        } else {
            kotlin.math.max(pad, safe.left + pad)
        }
        val hudRight = hud?.rightX ?: (rightBandInnerX - Fonts.px(10f))
        val handBaselineY = hud?.topY ?: (screenH - topPad)

        // Top rule badge: "Patternless OK" or "Min. 5 pts".
        val allowChicken = playHost?.allowChickenHands ?: session.gameConfig.rules.allowChickenHands
        val ruleText = PatternlessRule.standalone(allowChicken)

        val label = formatHandStatusLabel(
            sessionKind = sessionKind,
            handNumber = playHost?.currentHandNumber ?: session.currentHandNumber(),
            maxHands = playHost?.maxHands ?: session.gameConfig.rules.maxHands,
        )

        // The restart clock outranks the ping: both share the detail slot, and an
        // announced restart is the one the player must see.
        var detailText: String? = null
        var detailColor: Color? = null
        val shutdownPhase = playHost?.shutdownPhase
        val shutdownRemainingMs = playHost?.shutdownRemainingMs
        if (sessionKind == SessionKind.Online) {
            if (shutdownPhase != null && shutdownRemainingMs != null) {
                val totalSeconds = (shutdownRemainingMs / 1000L).coerceAtLeast(0L)
                val timeStr = "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
                detailText = L10n.format("online.shutdown_label", timeStr)
                detailColor = if (shutdownPhase == "final") {
                    AppPalette.danger
                } else {
                    AppPalette.warning
                }
            } else {
                // No sample is a dash in the same slot, not a vanished line: the
                // detail keeps its place while presence is between readings.
                detailText = LatencyLabel.text(latencyMs)
                detailColor = LatencyLabel.color(latencyMs)
            }
        }

        Fonts.withFaceScale(font, labelScale) {
            ruleGlyph.setText(font, ruleText)
            Fonts.drawText(batch, font, ruleText, hudLeft, handBaselineY, font.color)

            statusGlyph.setText(font, label)
        }
        val labelW = statusGlyph.width
        val ruleRight = hudLeft + ruleGlyph.width + Fonts.px(12f)

        val detail = detailText
        val detailFont = Fonts.small
        val lineGap = Fonts.px(8f)
        val detailW = if (detail != null) {
            Fonts.withFaceScale(detailFont, labelScale) {
                Fonts.textWidth(detailFont, detail)
            }
        } else {
            0f
        }
        // Drop the detail before it crowds the rule badge; the counter is the
        // primary readout. With the band anchor this only happens on a pane too
        // narrow for the whole line to fit.
        val detailLine = detail?.takeIf {
            detailW + lineGap + labelW <= hudRight - ruleRight
        }
        val lineW = if (detailLine != null) detailW + lineGap + labelW else labelW
        val lineLeft = (hudRight - lineW).coerceAtLeast(ruleRight)
        // The counter is last and flush with the anchor, so the volatile ping
        // changing digit count moves the detail's left edge, never the counter.
        val statusLeft = lineLeft + lineW - labelW

        Fonts.withFaceScale(font, labelScale) {
            Fonts.drawText(batch, font, label, statusLeft, handBaselineY, font.color)
        }
        if (detailLine != null) {
            // The detail face is smaller; share the counter's baseline (the draw
            // y is the cap top, so the difference of cap heights is the offset).
            val detailY = handBaselineY - (font.capHeight - detailFont.capHeight) * labelScale
            Fonts.withFaceScale(detailFont, labelScale) {
                Fonts.drawText(
                    batch,
                    detailFont,
                    detailLine,
                    lineLeft,
                    detailY,
                    detailColor ?: font.color,
                )
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
