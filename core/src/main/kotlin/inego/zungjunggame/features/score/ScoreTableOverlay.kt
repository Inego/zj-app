package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.HandsTableDoc
import inego.zungjunggame.render.ui.HandsTableModel
import inego.zungjunggame.render.ui.HandsTableText
import inego.zungjunggame.render.ui.HandsTablePlayer
import inego.zungjunggame.render.ui.HandsTableRenderer
import inego.zungjunggame.render.ui.HandsTableRow
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.VerticalScroll
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the score table's bounds after the last [ScoreTableOverlay.render]. */
data class ScoreTableGeometry(
    val panel: Rect?,
)

/**
 * The in-game Scores card: the session's hand history as the shared
 * [HandsTableRenderer] table. This overlay owns only the card chrome (the close
 * control) and the scroll/press machine; the table itself is the same code the
 * Shadow Ranked hand-tables card paints, so the two look identical.
 *
 * Player column order is relative to the human (see [PlayerLabels]).
 */
class ScoreTableOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val renderer = HandsTableRenderer()
    private val closeButton = CloseButton()

    private fun whitePixel(): Texture = WhitePixel.get()

    /** Transient scroll motion: the offset itself is the owner's. */
    private val scroll = VerticalScroll()

    /** The frame's offset and measured range, installed by [render]. */
    private var frameScrollY: Float = 0f
    private var frameMaxScroll: Float = 0f

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var pressTarget: PressTarget = PressTarget.None

    // The session model, cached while the table is unchanged so the shared
    // renderer's measurement memo survives consecutive frames.
    private var cachedTable: SessionScoreTable? = null
    private var cachedHumanSeat = -1
    private var cachedRowCount = -1
    private var cachedLastRow: HandScoreRow? = null
    private var cachedModel: HandsTableModel? = null

    private enum class PressTarget {
        None,
        Close,
        Content,
    }

    /**
     * The hand-list offset the player left the table at (0 = the top of the list),
     * reported to the [inego.zungjunggame.ui.GestureBridge]'s consumed-scroll
     * channel so the component can emit it as the route's state.
     */
    override fun scrollOffset(): Float = frameScrollY

    override fun scrollCoasting(): Boolean = scroll.isCoasting

    /** Abort an in-flight scroll gesture (OS cancel). */
    override fun cancelGesture() {
        scroll.cancel()
        pressTarget = PressTarget.None
    }

    /** True when the point is on the score panel (not the dimmed backdrop). */
    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** GL-free snapshot of the panel (null before the first render). */
    fun measureGeometry(): ScoreTableGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return ScoreTableGeometry(panel)
    }

    /**
     * Mouse-wheel / trackpad scroll.
     * libGDX [amountY]: positive when the wheel scrolls down (content moves up).
     * The offset is from the top, so it increases with positive [amountY].
     */
    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        frameScrollY = scroll.wheel(amountY, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return true
    }

    /**
     * Touch press on the overlay.
     * Drag only starts when the press is on the panel content (not close / backdrop).
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        scroll.cancel()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        pressTarget = PressTarget.Content
        scroll.beginDrag(y, frameScrollY)
        return true
    }

    /** Touch move while finger is held; content follows the finger. */
    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content) return null
        frameScrollY = scroll.dragTo(y, frameMaxScroll).coerceIn(0f, frameMaxScroll)
        // Each drag step reports the live offset, so the store coasts with the
        // finger instead of only learning the settle on release.
        return ModalVerdict(dragScrollOffset = frameScrollY)
    }

    /**
     * End gesture.
     * [ModalVerdict.close] is true when the close button was tapped; a content
     * drag reports the offset it settled at, so the component can emit it as the
     * route's state.
     */
    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Close -> {
                val hit =
                    !x.isNaN() &&
                        !y.isNaN() &&
                        closeButton.contains(x, y)
                scroll.cancel()
                ModalVerdict(close = hit)
            }
            PressTarget.Content -> {
                val wasDrag = scroll.endDrag()
                ModalVerdict(settledScrollOffset = if (wasDrag) frameScrollY else null)
            }
            PressTarget.None -> {
                scroll.cancel()
                ModalVerdict()
            }
        }
    }

    /**
     * @param scrollY the owner's stored offset, or null when the player never
     *   scrolled: a table with no stored place opens at the newest hand.
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        table: SessionScoreTable,
        humanSeat: Int,
        screenW: Float,
        screenH: Float,
        scrollY: Float?,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()

        val model = buildModel(table, humanSeat)
        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 14f else 20f)
        val frame = renderer.frame(model, avail, pad)

        val closeInset = CloseButton.cornerInset()
        val headerGap = Fonts.px(8f)
        val footerGap = Fonts.px(6f)
        val chromeH = closeInset + closeButton.side + headerGap + frame.headerH +
            footerGap + frame.footerH + pad
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, frame.bodyContentH)
        val panelH = fit.cardH
        val bodyH = fit.bodyViewH
        val (left, bottom) = OverlayViewport.placeCentered(frame.panelW, panelH, avail)
        panelLeft = left
        panelBottom = bottom
        panelW = frame.panelW
        this.panelH = panelH

        val px = whitePixel()
        batch.setColor(AppPalette.scrimDeep)
        batch.draw(px, 0f, 0f, screenW, screenH)
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val bodyLeft = panelLeft + pad
        val bodyW = frame.contentW
        val panelTop = panelBottom + panelH
        val closeRowTop = panelTop - closeInset
        val closeRowBottom = closeRowTop - closeButton.side
        val headerBottom = closeRowBottom - headerGap - frame.headerH
        val bodyTop = headerBottom
        val bodyBottom = bodyTop - bodyH
        val footerTop = bodyBottom - footerGap
        val footerBottom = footerTop - frame.footerH

        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        val ruleH = max(1.5f, Fonts.px(1.5f))
        val shown = model.shown
        val hasRows = shown != null && shown.rows.isNotEmpty()
        if (hasRows) {
            // The human's column, lifted behind the whole data band.
            renderer.drawHighlight(batch, model, frame, bodyLeft, footerBottom, headerBottom + frame.headerH)
        }
        renderer.drawHeader(batch, model, frame, bodyLeft, headerBottom, frame.headerH)
        renderer.drawRule(batch, bodyLeft, headerBottom - ruleH, bodyW)

        frameMaxScroll = fit.maxScrollY
        // The player's stored offset wins; absence is the newest hand (the bottom).
        frameScrollY = scrollY?.coerceIn(0f, frameMaxScroll) ?: frameMaxScroll

        if (!hasRows) {
            val emptyFont = Fonts.body
            val msg = L10n.get("scores.empty")
            val layout = com.badlogic.gdx.graphics.g2d.GlyphLayout()
            layout.setText(emptyFont, msg)
            emptyFont.draw(
                batch,
                msg,
                panelLeft + (panelW - layout.width) * 0.5f,
                bodyBottom + bodyH * 0.5f + layout.height * 0.5f,
            )
        } else {
            renderer.drawRows(
                batch = batch,
                camera = camera,
                model = model,
                frame = frame,
                left = bodyLeft,
                width = bodyW,
                bodyTop = bodyTop,
                bodyBottom = bodyBottom,
                viewH = bodyH,
                scrollY = frameScrollY,
            )
        }

        renderer.drawRule(batch, bodyLeft, footerTop, bodyW)
        renderer.drawFooter(batch, model, frame, bodyLeft, footerBottom, frame.footerH)
    }

    /** The session's hands as the shared table model: one doc, player columns relative to [humanSeat]. */
    private fun buildModel(table: SessionScoreTable, humanSeat: Int): HandsTableModel {
        val lastRow = table.rows().lastOrNull()
        val cached = cachedModel
        if (cached != null &&
            cachedTable === table &&
            cachedHumanSeat == humanSeat &&
            cachedRowCount == table.handCount &&
            cachedLastRow === lastRow
        ) {
            return cached
        }
        val seats = PlayerLabels.absoluteSeatsInColumnOrder(humanSeat)
        val labelAt: (Int) -> String = { seat -> PlayerLabels.nameForAbsoluteSeat(seat, humanSeat) }
        val players = (0 until PLAYERS).map { col ->
            val seat = seats[col]
            HandsTablePlayer(label = labelAt(seat), dim = PlayerLabels.isBot(seat))
        }
        val rows = table.rows().map { row -> row.toView(seats, labelAt) }
        val totalsRaw = table.runningTotals()
        val totals = IntArray(PLAYERS) { col -> totalsRaw[seats[col]] }
        val model = HandsTableModel(
            listOf(HandsTableDoc(players, rows, totals, highlight = seats.indexOf(humanSeat))),
        )
        cachedTable = table
        cachedHumanSeat = humanSeat
        cachedRowCount = table.handCount
        cachedLastRow = lastRow
        cachedModel = model
        return model
    }

    private fun HandScoreRow.toView(
        seats: IntArray,
        labelAt: (Int) -> String,
    ): HandsTableRow {
        val winner = winnerSeat.takeIf { it >= 0 }
        val patterns: List<HandScoreComponent> = patterns
        val won = handValue > 0 && winner != null
        return HandsTableRow(
            number = handNumber,
            winner = HandsTableText.winnerText(handValue, winner, labelAt),
            from = HandsTableText.fromText(winner, discarderSeat.takeIf { it >= 0 }, labelAt),
            score = HandsTableText.scoreText(handValue, winner),
            patterns = HandsTableText.patternsText(patterns, won),
            deltas = IntArray(PLAYERS) { col -> deltas[seats[col]] },
            totals = IntArray(PLAYERS) { col -> runningTotals[seats[col]] },
        )
    }

    override fun dispose() {
    }

    private companion object {
        const val PLAYERS = 4
    }
}
