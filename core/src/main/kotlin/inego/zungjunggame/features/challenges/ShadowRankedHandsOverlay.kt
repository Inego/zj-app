package inego.zungjunggame.features.challenges

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.AttemptHands
import inego.zungjunggame.net.ChallengeHands
import inego.zungjunggame.net.HandScoreRowWire
import inego.zungjunggame.net.ShadowRankedFormat
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.HandsTableDoc
import inego.zungjunggame.render.ui.HandsTableModel
import inego.zungjunggame.render.ui.HandsTableText
import inego.zungjunggame.render.ui.HandsTablePlayer
import inego.zungjunggame.render.ui.HandsTableRenderer
import inego.zungjunggame.render.ui.HandsTableRow
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.TextFit
import inego.zungjunggame.render.ui.UiIcons
import inego.zungjunggame.render.ui.VerticalScroll
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * The control ids the hand-tables card reports through [ModalVerdict.selectedId].
 * The card's vocabulary; [ShadowRankedHandsActions] maps each to an intent.
 */
internal object ShadowRankedHandsControls {
    const val PREV = "shadowRankedHands.prev"
    const val NEXT = "shadowRankedHands.next"
    const val WHO = "shadowRankedHands.who"

    private const val ATTEMPT_PREFIX = "shadowRankedHands.attempt:"

    fun attempt(index: Int): String = "$ATTEMPT_PREFIX$index"

    fun attemptIndex(selectedId: String?): Int? =
        selectedId?.takeIf { it.startsWith(ATTEMPT_PREFIX) }
            ?.removePrefix(ATTEMPT_PREFIX)
            ?.toIntOrNull()
}

/** GL-free snapshot of the hand-tables card's bounds after the last render. */
data class ShadowRankedHandsGeometry(
    val panel: Rect?,
)

/**
 * The hand tables of one finished Shadow Ranked challenge: a Who bar over the
 * selected attempt's [HandsTableRenderer] table.
 *
 * The card is controlled: [render] is handed the document, the selected row, and
 * the dropdown's open state every frame, and never keeps an authoritative copy.
 * Vertical scroll is reported to the owning component as the route's state; the
 * open dropdown is transient view state.
 *
 * The table itself is the shared renderer used by the in-game Scores card, so the
 * two look identical. Its column widths and row grid are measured across **all**
 * attempts, so Who only changes the text drawn into a fixed grid.
 */
class ShadowRankedHandsOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val table = HandsTableRenderer()
    private val closeButton = CloseButton()
    private val glyphLayout = GlyphLayout()
    private val vertical = VerticalScroll()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var bodyLeft = 0f
    private var bodyBottom = 0f
    private var bodyW = 0f
    private var bodyH = 0f
    private var headerBottom = 0f
    private var footerTop = 0f
    private var footerBottom = 0f

    private var frameScrollY = 0f
    private var frameMaxScroll = 0f

    // The document's docs, cached by document identity so the shared renderer's
    // grid memo survives a per-frame Who change.
    private var docsDocument: ChallengeHands? = null
    private var docsCache: List<HandsTableDoc> = emptyList()

    private var prevRect = Rect(0f, 0f, 0f, 0f)
    private var nextRect = Rect(0f, 0f, 0f, 0f)
    private var whoRect = Rect(0f, 0f, 0f, 0f)

    private var dropdownLeft = 0f
    private var dropdownBottom = 0f
    private var dropdownW = 0f
    private var dropdownH = 0f
    private var dropdownOpen = false
    private var dropdownScroll = 0f
    private var dropdownMaxScroll = 0f

    /** The open dropdown's selectable rows, in paint order; the release resolves one. */
    private var dropdownRows: List<Pair<Int, Rect>> = emptyList()

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Close, Content, Prev, Next, Who, DropdownItem, OutsideDropdown }

    private enum class Axis { None, Vertical, Horizontal }

    private var dragAxis = Axis.None
    private var dragStartX = 0f
    private var dragStartY = 0f

    override fun scrollOffset(): Float = frameScrollY

    override fun scrollCoasting(): Boolean = vertical.isCoasting

    /** Advance the table's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float {
        frameScrollY = vertical.advance(deltaSec, frameScrollY, frameMaxScroll).coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    /** GL-free snapshot of the card's bounds. */
    fun measureGeometry(): ShadowRankedHandsGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return ShadowRankedHandsGeometry(panel)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        if (dropdownOpen) {
            if (dropdownMaxScroll <= 0f) return false
            dropdownScroll = (dropdownScroll + amountY * Fonts.px(48f)).coerceIn(0f, dropdownMaxScroll)
            return true
        }
        if (frameMaxScroll <= 0f) return false
        frameScrollY = vertical.wheel(amountY, frameScrollY, frameMaxScroll).coerceIn(0f, frameMaxScroll)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        vertical.cancel()
        pressTarget = PressTarget.None
        dragAxis = Axis.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        if (dropdownOpen) {
            if (dropdownRectContains(x, y)) {
                pressTarget = PressTarget.DropdownItem
                return true
            }
            if (whoRect.contains(x, y)) {
                pressTarget = PressTarget.Who
                return true
            }
            pressTarget = PressTarget.OutsideDropdown
            return true
        }
        if (prevRect.contains(x, y)) {
            pressTarget = PressTarget.Prev
            return true
        }
        if (nextRect.contains(x, y)) {
            pressTarget = PressTarget.Next
            return true
        }
        if (whoRect.contains(x, y)) {
            pressTarget = PressTarget.Who
            return true
        }
        if (bodyContains(x, y)) {
            pressTarget = PressTarget.Content
            vertical.beginDrag(y, frameScrollY)
            dragStartX = x
            dragStartY = y
            return true
        }
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content) return null
        if (dragAxis == Axis.None) {
            val dx = abs(x - dragStartX)
            val dy = abs(y - dragStartY)
            val slop = Fonts.px(6f)
            if (dx >= slop || dy >= slop) {
                dragAxis = if (dx > dy) Axis.Horizontal else Axis.Vertical
                if (dragAxis == Axis.Horizontal) vertical.cancel()
            }
        }
        return when (dragAxis) {
            Axis.Vertical -> {
                frameScrollY = vertical.dragTo(y, frameMaxScroll).coerceIn(0f, frameMaxScroll)
                ModalVerdict(dragScrollOffset = frameScrollY)
            }
            // The table always fits the viewport, so a sideways drag never moves
            // it; recognising the axis keeps that drag from scrolling vertically.
            Axis.Horizontal -> null
            Axis.None -> null
        }
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Close -> {
                vertical.cancel()
                val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
                ModalVerdict(close = hit)
            }
            PressTarget.Prev -> ModalVerdict(selectedId = if (prevRect.contains(x, y)) ShadowRankedHandsControls.PREV else null)
            PressTarget.Next -> ModalVerdict(selectedId = if (nextRect.contains(x, y)) ShadowRankedHandsControls.NEXT else null)
            PressTarget.Who -> ModalVerdict(selectedId = if (whoRect.contains(x, y)) ShadowRankedHandsControls.WHO else null)
            PressTarget.DropdownItem -> ModalVerdict(selectedId = dropdownHit(x, y)?.let(ShadowRankedHandsControls::attempt))
            PressTarget.OutsideDropdown -> ModalVerdict(selectedId = ShadowRankedHandsControls.WHO)
            PressTarget.Content -> {
                val wasDrag = dragAxis == Axis.Vertical
                if (dragAxis == Axis.Vertical) vertical.endDrag() else vertical.cancel()
                dragAxis = Axis.None
                ModalVerdict(settledScrollOffset = if (wasDrag) frameScrollY else null)
            }
            PressTarget.None -> {
                vertical.cancel()
                ModalVerdict()
            }
        }
    }

    override fun cancelGesture() {
        vertical.cancel()
        pressTarget = PressTarget.None
        dragAxis = Axis.None
    }

    private fun containsPanel(x: Float, y: Float): Boolean =
        x >= panelLeft && x <= panelLeft + panelW && y >= panelBottom && y <= panelBottom + panelH

    private fun bodyContains(x: Float, y: Float): Boolean =
        x >= bodyLeft && x <= bodyLeft + bodyW && y >= bodyBottom && y <= bodyBottom + bodyH

    private fun dropdownRectContains(x: Float, y: Float): Boolean =
        dropdownOpen && dropdownW > 1f && dropdownH > 1f &&
            x >= dropdownLeft && x <= dropdownLeft + dropdownW &&
            y >= dropdownBottom && y <= dropdownBottom + dropdownH

    private fun dropdownHit(x: Float, y: Float): Int? {
        if (!dropdownRectContains(x, y)) return null
        return dropdownRows.lastOrNull { it.second.contains(x, y) }?.first
    }

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        publicId: String,
        scrollY: Float,
        document: ChallengeHands?,
        selectedIndex: Int,
        dropdownOpen: Boolean,
        loading: Boolean,
        failed: Boolean,
        errorCode: String?,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
    ) {
        Fonts.ensureCreated()
        this.dropdownOpen = dropdownOpen
        dropdownRows = emptyList()
        frameMaxScroll = 0f

        val attempts = document?.attempts ?: emptyList()
        val docs = docsFor(document)
        val selected = selectedIndex.coerceIn(0, max(0, attempts.lastIndex))
        val model = HandsTableModel(docs, selected)

        val px = whitePixel()
        batch.setColor(AppPalette.scrimDeep)
        batch.draw(px, 0f, 0f, screenW, screenH)

        val titleFont = Fonts.labelActive
        val cellFont = Fonts.button

        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 14f else 20f)
        val closeInset = CloseButton.cornerInset()
        val closeSide = closeButton.side

        val titleRowH = max(closeSide, titleFont.lineHeight)
        val whoH = max(Fonts.px(34f), titleFont.lineHeight + Fonts.px(8f))
        val titleGap = Fonts.px(8f)
        val headerGap = Fonts.px(8f)
        val footerGap = Fonts.px(6f)

        val avail = OverlayViewport.available(screenW, screenH)
        val frame = table.frame(model, avail, pad)

        val shownDoc = docs.getOrNull(selected)
        val hands = shownDoc?.rows
        val hasTable = hands != null && hands.isNotEmpty()

        val message = when {
            loading -> L10n.get("shadowRanked.attempts.loading")
            failed -> errorMessage(errorCode)
            document == null -> L10n.get("shadowRanked.attempts.loading")
            attempts.isEmpty() -> L10n.get("shadowRanked.hands.missing")
            shownDoc == null -> L10n.get("shadowRanked.hands.missing")
            !hasTable -> L10n.get("shadowRanked.hands.unavailable")
            else -> null
        }
        val bodyContentH = if (hasTable) frame.bodyContentH else Fonts.px(120f)

        val chromeH = closeInset + titleRowH + titleGap + whoH + headerGap + frame.headerH +
            footerGap + frame.footerH + pad
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH)
        val placedH = fit.cardH
        bodyH = fit.bodyViewH
        frameMaxScroll = fit.maxScrollY
        frameScrollY = scrollY.coerceIn(0f, frameMaxScroll)

        val placed = OverlayViewport.placeCentered(frame.panelW, placedH, avail)
        panelLeft = placed.first
        panelBottom = placed.second
        panelW = frame.panelW
        panelH = placedH

        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val panelTop = panelBottom + panelH
        val titleTop = panelTop - closeInset
        val titleBottom = titleTop - titleRowH
        val whoTop = titleBottom - titleGap
        val whoBottom = whoTop - whoH
        headerBottom = whoBottom - headerGap - frame.headerH
        val bodyTop = headerBottom
        bodyBottom = bodyTop - bodyH
        footerTop = bodyBottom - footerGap
        footerBottom = footerTop - frame.footerH

        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)
        bodyLeft = panelLeft + pad
        bodyW = frame.contentW

        // The title is the challenge code alone.
        glyphLayout.setText(titleFont, publicId)
        val titleTextTop = titleBottom + (titleRowH + glyphLayout.height) * 0.5f
        titleFont.color = AppPalette.ink
        titleFont.draw(batch, publicId, panelLeft + pad, titleTextTop)
        titleFont.color = Color.WHITE

        val gap = Fonts.px(6f)
        val arrowW = max(Fonts.px(30f), min(Fonts.px(40f), frame.contentW * 0.12f))
        prevRect = Rect(bodyLeft, whoBottom, arrowW, whoH)
        nextRect = Rect(bodyLeft + frame.contentW - arrowW, whoBottom, arrowW, whoH)
        whoRect = Rect(bodyLeft + arrowW + gap, whoBottom, (frame.contentW - arrowW * 2f - gap * 2f).coerceAtLeast(1f), whoH)

        drawWhoBar(batch, px, cellFont, attempts.getOrNull(selected))
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        if (shownDoc == null) {
            drawCentered(batch, cellFont, message ?: L10n.get("shadowRanked.hands.missing"))
            return
        }

        val ruleH = max(1.5f, Fonts.px(1.5f))
        if (!hasTable) {
            table.drawHeader(batch, model, frame, bodyLeft, headerBottom, frame.headerH)
            table.drawRule(batch, bodyLeft, headerBottom - ruleH, bodyW)
            drawCentered(batch, cellFont, message ?: L10n.get("shadowRanked.hands.unavailable"))
            if (dropdownOpen) {
                drawDropdown(batch, camera, px, cellFont, attempts, selected)
            }
            return
        }

        // Each band gets its own single-level clip; the table always fits the
        // viewport horizontally, so no band pans sideways. The chrome (title, Who
        // bar, dropdown) stays at its natural size.
        table.drawHighlight(batch, model, frame, bodyLeft, footerBottom, headerBottom + frame.headerH)
        table.drawHeader(batch, model, frame, bodyLeft, headerBottom, frame.headerH)
        table.drawRule(batch, bodyLeft, headerBottom - ruleH, bodyW)
        table.drawRows(
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
        table.drawRule(batch, bodyLeft, footerTop, bodyW)
        table.drawFooter(batch, model, frame, bodyLeft, footerBottom, frame.footerH)

        if (dropdownOpen) {
            drawDropdown(batch, camera, px, cellFont, attempts, selected)
        }
    }

    /** The document's attempts as shared table docs, memoized by document identity. */
    private fun docsFor(document: ChallengeHands?): List<HandsTableDoc> {
        if (document == null) return emptyList()
        if (docsDocument === document) return docsCache
        val docs = document.attempts.map { attempt -> attempt.toDoc() }
        docsDocument = document
        docsCache = docs
        return docs
    }

    private fun AttemptHands.toDoc(): HandsTableDoc {
        val headers = seatHeaders(this)
        val rows = hands?.map { row -> row.toView(headers) } ?: emptyList()
        val totals = hands?.lastOrNull()?.runningTotals?.let { IntArray(4) { i -> it.getOrElse(i) { 0 } } } ?: IntArray(4)
        val players = (0 until 4).map { i ->
            HandsTablePlayer(label = headers.getOrElse(i) { "" }, dim = !(isYou && i == 0))
        }
        return HandsTableDoc(players, rows, totals, highlight = 0)
    }

    private fun HandScoreRowWire.toView(headers: List<String>): HandsTableRow {
        val labelAt: (Int) -> String = { seat -> headers.getOrElse(seat) { "" } }
        val won = handValue > 0 && winnerSeat != null
        val components: List<HandScoreComponent> =
            patterns.map { HandScoreComponent(it.id, it.points, it.formatArgs) }
        return HandsTableRow(
            number = handNumber,
            winner = HandsTableText.winnerText(handValue, winnerSeat, labelAt),
            from = HandsTableText.fromText(winnerSeat, discarderSeat, labelAt),
            score = HandsTableText.scoreText(handValue, winnerSeat),
            patterns = HandsTableText.patternsText(components, won),
            deltas = IntArray(4) { i -> deltas.getOrElse(i) { 0 } },
            totals = IntArray(4) { i -> runningTotals.getOrElse(i) { 0 } },
        )
    }

    private fun drawWhoBar(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        selected: AttemptHands?,
    ) {
        drawArrow(batch, UiIcons.chevronLeft, prevRect)
        drawArrow(batch, UiIcons.chevronRight, nextRect)
        val open = dropdownOpen
        OverlayChrome.drawPanel(
            batch, px, whoRect.left, whoRect.bottom, whoRect.width, whoRect.height,
            if (open) AppPalette.plateHover else AppPalette.plate,
            if (open) AppPalette.tint(AppPalette.accent, 0.90f) else AppPalette.rim,
        )
        val line = if (selected == null) "\u2014" else whoLine(selected)
        val chevron = if (open) UiIcons.chevronUp else UiIcons.chevronDown
        val iconSide = min(whoRect.height * 0.55f, Fonts.px(22f))
        val textWidth = (whoRect.width - iconSide - Fonts.px(24f)).coerceAtLeast(1f)
        val fitted = TextFit.ellipsize(font, line, textWidth)
        Fonts.drawText(
            batch, font, fitted, whoRect.left + Fonts.px(12f),
            whoRect.bottom + (whoRect.height + Fonts.textHeight(font, fitted)) * 0.5f,
            AppPalette.ink,
        )
        batch.setColor(AppPalette.tint(AppPalette.ink, 0.9f))
        batch.draw(
            chevron,
            whoRect.left + whoRect.width - iconSide - Fonts.px(10f),
            whoRect.bottom + (whoRect.height - iconSide) * 0.5f,
            iconSide, iconSide,
        )
        batch.setColor(Color.WHITE)
    }

    private fun drawArrow(batch: SpriteBatch, icon: Texture, rect: Rect) {
        val side = min(rect.height * 0.5f, Fonts.px(20f))
        batch.setColor(AppPalette.tint(AppPalette.ink, 0.95f))
        batch.draw(icon, rect.left + (rect.width - side) * 0.5f, rect.bottom + (rect.height - side) * 0.5f, side, side)
        batch.setColor(Color.WHITE)
    }

    private fun drawDropdown(
        batch: SpriteBatch,
        camera: Camera,
        px: Texture,
        font: BitmapFont,
        attempts: List<AttemptHands>,
        selectedIndex: Int,
    ) {
        val rowH = max(Fonts.px(30f), font.lineHeight + Fonts.px(10f))
        val availableH = (whoRect.bottom - Fonts.px(6f)) - (panelBottom + Fonts.px(12f))
        val contentH = attempts.size * rowH
        val h = min(contentH, max(Fonts.px(30f), availableH))
        dropdownMaxScroll = max(0f, contentH - h)
        dropdownScroll = dropdownScroll.coerceIn(0f, dropdownMaxScroll)
        dropdownLeft = whoRect.left
        dropdownW = whoRect.width
        val top = whoRect.bottom - Fonts.px(4f)
        dropdownBottom = top - h
        dropdownH = h

        OverlayChrome.drawPanel(
            batch, px, dropdownLeft, dropdownBottom, dropdownW, dropdownH,
            AppPalette.panel,
            AppPalette.panelRim,
        )

        val rows = ArrayList<Pair<Int, Rect>>(attempts.size)
        clipped(batch, camera, dropdownLeft, dropdownBottom, dropdownW, dropdownH) {
            var rowTop = top + dropdownScroll
            attempts.forEachIndexed { index, attempt ->
                val rect = Rect(dropdownLeft, rowTop - rowH, dropdownW, rowH)
                if (index == selectedIndex) {
                    batch.setColor(AppPalette.rowHighlight)
                    batch.draw(px, dropdownLeft, rect.bottom, dropdownW, rowH)
                    batch.setColor(Color.WHITE)
                }
                val line = whoLine(attempt)
                val tw = (dropdownW - Fonts.px(24f)).coerceAtLeast(1f)
                val fitted = TextFit.ellipsize(font, line, tw)
                Fonts.drawText(
                    batch, font, fitted, dropdownLeft + Fonts.px(12f),
                    rect.bottom + (rowH + Fonts.textHeight(font, fitted)) * 0.5f,
                    if (index == selectedIndex) AppPalette.accent else AppPalette.inkSoft,
                )
                rows += index to rect
                rowTop -= rowH
            }
        }
        dropdownRows = rows
    }

    private fun drawCentered(batch: SpriteBatch, font: BitmapFont, message: String) {
        glyphLayout.setText(font, message, Color.WHITE, bodyW, Align.center, true)
        font.color = AppPalette.inkMuted
        font.draw(
            batch, message, bodyLeft,
            bodyBottom + bodyH * 0.5f + glyphLayout.height * 0.5f,
            bodyW, Align.center, true,
        )
        font.color = Color.WHITE
    }

    private fun clipped(
        batch: SpriteBatch,
        camera: Camera,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        block: () -> Unit,
    ) {
        if (w <= 1f || h <= 1f) return
        batch.flush()
        clipBounds.set(x, y, w, h)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val on = ScissorStack.pushScissors(scissors)
        try {
            block()
        } finally {
            batch.flush()
            if (on) ScissorStack.popScissors()
        }
    }

    private fun seatHeaders(attempt: AttemptHands): List<String> {
        val seat0 = ShadowRankedFormat.attemptName(attempt.isBot, attempt.isYou, attempt.name)
        return listOf(
            seat0,
            L10n.format("seat.bot_numbered", 1),
            L10n.format("seat.bot_numbered", 2),
            L10n.format("seat.bot_numbered", 3),
        )
    }

    private fun whoLine(attempt: AttemptHands): String {
        val name = ShadowRankedFormat.attemptName(attempt.isBot, attempt.isYou, attempt.name)
        val rating = attempt.rating?.let { ShadowRankedFormat.ratingValue(it) } ?: "\u2014"
        val score = ShadowRankedFormat.signedScore(attempt.score)
        return L10n.format("shadowRanked.hands.who", name, rating, score)
    }

    private fun errorMessage(code: String?): String = when (code) {
        "NOT_FOUND" -> L10n.get("shadowRanked.hands.missing")
        "CHALLENGE_NOT_FINISHED" -> L10n.get("shadowRanked.hands.not_finished")
        else -> L10n.get("shadowRanked.hands.error")
    }

    override fun dispose() {
        vertical.cancel()
        docsDocument = null
        docsCache = emptyList()
    }
}
