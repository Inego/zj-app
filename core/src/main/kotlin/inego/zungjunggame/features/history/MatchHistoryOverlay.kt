package inego.zungjunggame.features.history

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.MatchHistoryClient
import inego.zungjunggame.net.MatchHistoryFormat
import inego.zungjunggame.net.MatchStatus
import inego.zungjunggame.net.MatchSummary
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.VerticalScrollbar
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the match-history card's bounds after the last render. */
data class MatchHistoryGeometry(
    val panel: Rect?,
)

/**
 * Paginated past-games list behind the hub's **Past games** link.
 *
 * Rows are read-only (no match detail). **Load more** appends the next page.
 *
 * The card is a window, not a wall: it hugs the widest row its formatter can
 * produce, clamped to a readable floor and the safe viewport, and centers itself
 * — the same fit policy as
 * [inego.zungjunggame.features.challenges.ShadowRankedAttemptsOverlay] and the
 * hands-table viewer. Every row shares one set of policy-measured columns, so
 * the date, place, delta and rating never spread to fill a wider panel, paging
 * cannot resize them, and the place label is no longer a fraction of the
 * window.
 */
class MatchHistoryOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val glyphLayout = GlyphLayout()
    private val closeButton = CloseButton()
    private val body = OverlayBodyScroll()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var pressTarget: PressTarget = PressTarget.None
    private var loadMoreHit: Hit? = null

    // One column set, measured from the formatter's own bounds (the two relative
    // day words, the four place labels, a digit budget, and every rules/status
    // sentence) rather than from the rows currently in hand. Paging therefore
    // cannot move a column or resize the card; only a locale or UI-scale change
    // re-measures, and the whole set is memoized on those two.
    private var measuredUiScale = Float.NaN
    private var measuredLocale: AppLocale? = null
    private var measuredColumns: Columns? = null

    private enum class PressTarget { None, Close, Content }

    private data class Hit(
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= left && px <= left + w && py >= bottom && py <= bottom + h
    }

    /**
     * One column set for every row: date, place, delta, rating and detail, each
     * sized to the widest string that column must draw. The flexible part of the
     * row is only the gap between the place column and the right-aligned numbers.
     */
    private data class Columns(
        val rowPadX: Float,
        val inlineGap: Float,
        val colGap: Float,
        val dayW: Float,
        val placeW: Float,
        val deltaW: Float,
        val ratingW: Float,
        val detailW: Float,
    ) {
        /** Date column + gutter + place column. */
        val leftW: Float get() = dayW + inlineGap + placeW

        /** Delta column + gutter + rating column; the delta keeps the edge with no rating. */
        val rightW: Float get() = deltaW + if (ratingW > 0f) inlineGap + ratingW else 0f

        /** Width a row's own content needs (outside [rowPadX]). */
        val rowsW: Float get() = max(leftW + colGap + rightW, detailW)
    }

    private fun whitePixel(): Texture = WhitePixel.get()

    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** GL-free snapshot of the card's bounds. */
    fun measureGeometry(): MatchHistoryGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return MatchHistoryGeometry(panel)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        body.wheel(amountY)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        body.cancel()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        if (body.contains(x, y)) {
            body.beginPointer(x, y)
            pressTarget = PressTarget.Content
        }
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content || y.isNaN()) return null
        return ModalVerdict(dragScrollOffset = body.dragTo(y))
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Close -> {
                val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
                body.cancel()
                ModalVerdict(close = hit)
            }
            PressTarget.Content -> {
                val wasDrag = body.endPointer()
                val loadMore = !wasDrag &&
                    !x.isNaN() &&
                    !y.isNaN() &&
                    loadMoreHit?.contains(x, y) == true
                ModalVerdict(
                    selectedId = if (loadMore) MatchHistoryClient.LOAD_MORE_ID else null,
                    settledScrollOffset = if (wasDrag) body.scrollY else null,
                )
            }
            PressTarget.None -> {
                body.cancel()
                ModalVerdict()
            }
        }
    }

    override fun cancelGesture() {
        body.cancel()
        pressTarget = PressTarget.None
    }

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        matches: List<MatchSummary> = emptyList(),
        loading: Boolean = false,
        error: String? = null,
        hasMore: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        Fonts.ensureCreated()
        loadMoreHit = null

        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 16f else 22f)
        val titleFont = Fonts.labelActive
        val labelFont = Fonts.label
        val itemFont = Fonts.button
        val closeInset = CloseButton.cornerInset()
        // Read the control's side before it is placed: `closeButton.side` is the
        // last frame's size, which is 0 on the first frame and would make the
        // header and the card width jump once.
        val closeSide = CloseButton.defaultSide()

        val lineGap = Fonts.px(4f)
        val rowH = max(Fonts.px(56f), itemFont.lineHeight + labelFont.lineHeight + lineGap + Fonts.px(16f))
        val loadMoreH = max(Fonts.px(44f), itemFont.lineHeight + Fonts.px(18f))

        val statusText = when {
            error != null && matches.isEmpty() -> error
            matches.isEmpty() && loading -> L10n.get("history.loading")
            matches.isEmpty() -> L10n.get("history.empty")
            else -> null
        }
        val showLoadMore = hasMore || (loading && matches.isNotEmpty())
        val loadMoreLabel = if (loading) {
            L10n.get("history.loading")
        } else {
            L10n.get("history.load_more")
        }
        val extra = if (showLoadMore) loadMoreH + Fonts.px(8f) else 0f
        val bodyContentH = if (matches.isEmpty()) {
            Fonts.px(120f)
        } else {
            matches.size * rowH + extra
        }

        val columns = measureColumns(itemFont, labelFont)
        // Both labels are always reserved, so loading the last page (which drops
        // the button) cannot narrow the card under the player.
        val loadMoreW = max(
            textWidth(itemFont, L10n.get("history.load_more")),
            textWidth(itemFont, L10n.get("history.loading")),
        )
        val contentW = columns.rowPadX * 2f +
            max(if (matches.isEmpty()) 0f else columns.rowsW, loadMoreW)

        val avail = OverlayViewport.available(screenW, screenH)
        val title = L10n.get("history.title")
        val titleW = textWidth(titleFont, title)
        val titleGap = Fonts.px(8f)
        val titleRowH = max(closeSide, titleFont.lineHeight)
        val chromeH = closeInset + titleRowH + titleGap + pad
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH)
        val gutter = if (fit.maxScrollY > 0.5f) VerticalScrollbar.gutterWidth() else 0f

        // The header band shares its row with the corner control; the card must
        // clear that control even when the rows are narrower than the title.
        val titleNeedW = pad + titleW + Fonts.px(10f) + closeInset + closeSide
        val minPanelW = min(avail.width, Fonts.px(320f))
        panelW = (max(contentW + pad * 2f, titleNeedW) + gutter)
            .coerceIn(minPanelW, avail.width)
        panelH = fit.cardH
        val placed = OverlayViewport.placeCentered(panelW, panelH, avail)
        panelLeft = placed.first
        panelBottom = placed.second

        val px = whitePixel()
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )
        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        glyphLayout.setText(titleFont, title)
        val titleBaseline = panelBottom + panelH - closeInset - (closeSide - glyphLayout.height) * 0.5f
        titleFont.draw(batch, title, panelLeft + pad, titleBaseline)

        val titleBottom = panelBottom + panelH - closeInset - titleRowH
        val listTop = titleBottom - titleGap
        val listLeft = panelLeft + pad
        val listW = panelW - pad * 2f
        val listBottom = panelBottom + pad
        val listH = max(Fonts.px(48f), listTop - listBottom)

        body.layout(listLeft, listBottom, listW, listH, bodyContentH, scrollY)
        val rowsW = listW - body.gutter

        if (statusText != null) {
            labelFont.color = AppPalette.inkMuted
            glyphLayout.setText(labelFont, statusText, labelFont.color, rowsW, Align.center, true)
            labelFont.draw(
                batch,
                statusText,
                listLeft,
                listBottom + listH * 0.5f + glyphLayout.height * 0.5f,
                rowsW,
                Align.center,
                true,
            )
            labelFont.color = Color.WHITE
            return
        }

        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)

        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            var yTop = body.contentTop
            for (match in matches) {
                val rowBottom = yTop - rowH
                if (rowBottom < body.bottom + body.height && yTop > body.bottom) {
                    drawMatchRow(
                        batch, px, match, listLeft, rowBottom, rowsW, rowH,
                        itemFont, labelFont, columns, nowMs,
                    )
                }
                yTop = rowBottom
            }
            if (showLoadMore) {
                val bottom = yTop - Fonts.px(8f) - loadMoreH
                val top = yTop - Fonts.px(8f)
                loadMoreHit = Hit(listLeft, bottom, rowsW, loadMoreH)
                if (bottom < body.bottom + body.height && top > body.bottom) {
                    val hovered = !loading &&
                        !hoverX.isNaN() &&
                        !hoverY.isNaN() &&
                        loadMoreHit!!.contains(hoverX, hoverY)
                    val bg = OverlayChrome.rowFill(hovered)
                    val rim = OverlayChrome.rowRim()
                    OverlayChrome.drawButton(
                        batch, px, itemFont, loadMoreLabel,
                        listLeft, bottom, rowsW, loadMoreH, bg, rim,
                        AppPalette.ink,
                    )
                }
            }
        }
    }

    /**
     * One column set for every row, measured from the formatter's bounds: the
     * day's two relative words and fixed ISO date, the four seat places, a
     * numeric digit budget, and every rules/status sentence the detail line can
     * join. The loaded pages are deliberately not an input, so fetching a page
     * cannot re-measure a column, move an edge, or reflow the rows.
     */
    private fun measureColumns(
        itemFont: BitmapFont,
        labelFont: BitmapFont,
    ): Columns {
        val locale = L10n.currentLocale
        val cached = measuredColumns
        if (cached != null &&
            measuredUiScale == Fonts.uiScale &&
            measuredLocale == locale
        ) {
            return cached
        }

        // "Today", "Yesterday", or the server's fixed yyyy-MM-dd.
        var dayW = max(
            textWidth(itemFont, L10n.get("history.today")),
            textWidth(itemFont, L10n.get("history.yesterday")),
        )
        dayW = max(dayW, textWidth(itemFont, "0000-00-00"))

        // Four seats; zero covers an abandoned match with no place.
        var placeW = 0f
        for (place in 0..4) {
            placeW = max(placeW, textWidth(itemFont, MatchHistoryFormat.placeLabel(place)))
        }

        // The delta column carries either a signed number or the pending label.
        var deltaW = textWidth(itemFont, L10n.get("history.rating_pending"))
        deltaW = max(deltaW, textWidth(itemFont, MatchHistoryFormat.deltaLabel(9999)))
        deltaW = max(deltaW, textWidth(itemFont, MatchHistoryFormat.deltaLabel(-9999)))
        deltaW = max(deltaW, textWidth(itemFont, MatchHistoryFormat.deltaLabel(0)))
        val ratingW = textWidth(itemFont, MatchHistoryFormat.ratingLabel(9999))

        // The detail line is score · rules [· status]. Measure the widest
        // combination so a later page's longer row cannot widen the card.
        var detailW = 0f
        for (score in listOf(-9999, 9999, 0)) {
            val scoreText = MatchHistoryFormat.scoreLabel(score)
            for (rounds in listOf("one", "two")) {
                for (allowChicken in listOf(true, false)) {
                    val rules = MatchHistoryFormat.rulesLabel(rounds, allowChicken)
                    detailW = max(detailW, textWidth(labelFont, "$scoreText · $rules"))
                    for (status in listOf(MatchStatus.Left, MatchStatus.Aborted)) {
                        detailW = max(
                            detailW,
                            textWidth(
                                labelFont,
                                "$scoreText · $rules · ${MatchHistoryFormat.statusLabel(status)}",
                            ),
                        )
                    }
                }
            }
        }

        val columns = Columns(
            rowPadX = Fonts.px(10f),
            inlineGap = Fonts.px(12f),
            colGap = Fonts.px(24f),
            dayW = dayW,
            placeW = placeW,
            deltaW = deltaW,
            ratingW = ratingW,
            detailW = detailW,
        )
        measuredUiScale = Fonts.uiScale
        measuredLocale = locale
        measuredColumns = columns
        return columns
    }

    private fun textWidth(font: BitmapFont, text: String): Float {
        glyphLayout.setText(font, text)
        return glyphLayout.width
    }

    private fun detailLine(match: MatchSummary): String {
        val score = MatchHistoryFormat.scoreLabel(match.finalScore)
        val rules = MatchHistoryFormat.rulesLabel(match.rounds, match.allowChicken)
        val status = if (match.status == MatchStatus.Finished) {
            ""
        } else {
            MatchHistoryFormat.statusLabel(match.status)
        }
        return listOf(score, rules, status).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun drawMatchRow(
        batch: SpriteBatch,
        px: Texture,
        match: MatchSummary,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        itemFont: BitmapFont,
        labelFont: BitmapFont,
        columns: Columns,
        nowMs: Long,
    ) {
        val padX = columns.rowPadX
        val day = MatchHistoryFormat.dayLabel(match.endedAt, nowMs)
        val place = MatchHistoryFormat.placeLabel(match.placement)
        val delta = MatchHistoryFormat.deltaLabel(match.delta)
        val rating = MatchHistoryFormat.ratingLabel(match.newRating).orEmpty()

        batch.setColor(AppPalette.tint(AppPalette.plateDeep, 0.55f))
        batch.draw(px, left, bottom + Fonts.px(2f), width, height - Fonts.px(4f))
        batch.setColor(Color.WHITE)

        val topBaseline = bottom + height - Fonts.px(8f)
        itemFont.color = AppPalette.ink
        itemFont.draw(batch, day, left + padX, topBaseline)
        itemFont.draw(batch, place, left + padX + columns.dayW + columns.inlineGap, topBaseline)

        val ratingRight = left + width - padX
        if (rating.isNotEmpty()) {
            itemFont.color = AppPalette.accent
            itemFont.draw(batch, rating, ratingRight - textWidth(itemFont, rating), topBaseline)
        }
        val deltaRight = if (columns.ratingW > 0f) {
            ratingRight - columns.ratingW - columns.inlineGap
        } else {
            ratingRight
        }
        val deltaColor = when {
            match.oldRating == null || match.newRating == null -> AppPalette.inkMuted
            (match.newRating - match.oldRating) > 0.0 -> AppPalette.success
            (match.newRating - match.oldRating) < 0.0 -> AppPalette.danger
            else -> AppPalette.inkMuted
        }
        itemFont.color = deltaColor
        itemFont.draw(batch, delta, deltaRight - textWidth(itemFont, delta), topBaseline)
        itemFont.color = Color.WHITE

        labelFont.color = AppPalette.inkMuted
        labelFont.draw(
            batch,
            detailLine(match),
            left + padX,
            bottom + Fonts.px(12f) + labelFont.capHeight,
        )
        labelFont.color = Color.WHITE
    }

    override fun dispose() {
        body.cancel()
    }
}
