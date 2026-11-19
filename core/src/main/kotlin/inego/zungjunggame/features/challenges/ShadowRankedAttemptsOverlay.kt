package inego.zungjunggame.features.challenges

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.*
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

/** GL-free snapshot of the attempts card's bounds after the last render. */
data class ShadowRankedAttemptsGeometry(
    val panel: Rect?,
)

/**
 * The signed-in account's Shadow Ranked attempts, in their own card above the hub.
 *
 * The list is a window, not a wall: only the rows the card actually shows are laid
 * out and painted (the rest are culled by the scissor range), so a thousand
 * attempts cost the same as ten.
 */
class ShadowRankedAttemptsOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val glyphLayout = GlyphLayout()
    private val closeButton = CloseButton()
    private val body = OverlayBodyScroll()


    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var listLeft = 0f
    private var listBottom = 0f
    private var listW = 0f
    private var listH = 0f

    private var loadMoreHit: Hit? = null

    /** The finished attempt rows the last render laid out; a release resolves one. */
    private var rowHits: List<RowHit> = emptyList()

    // The rows' natural content width, memoized by list identity + UI scale so a
    // long list measures its widest left column once, not every frame.
    private var measuredAttempts: List<ChallengeEntry>? = null
    private var measuredUiScale = Float.NaN
    private var measuredLeftMax = 0f
    private var measuredRatingW = 0f

    private data class RowHit(val hit: Hit, val publicId: String)

    private data class Hit(val left: Float, val bottom: Float, val w: Float, val h: Float) {
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= left + w && y >= bottom && y <= bottom + h
    }

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Close, Content }

    /** Pixels the list is scrolled down (0 = top), for the route's own state. */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Advance the list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    /** GL-free snapshot of the card's bounds. */
    fun measureGeometry(): ShadowRankedAttemptsGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return ShadowRankedAttemptsGeometry(panel)
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
        if (body.contains(x, y) && body.beginPointer(x, y)) {
            pressTarget = PressTarget.Content
            return true
        }
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content) return null
        if (y.isNaN()) return null
        return ModalVerdict(dragScrollOffset = body.dragTo(y))
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        return when (target) {
            PressTarget.Close -> {
                body.cancel()
                val hit = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y)
                ModalVerdict(close = hit)
            }
            PressTarget.Content -> {
                val wasDrag = body.endPointer()
                val loadMore = !wasDrag &&
                    !x.isNaN() &&
                    !y.isNaN() &&
                    loadMoreHit?.contains(x, y) == true
                val rowId = if (!wasDrag && !loadMore && !x.isNaN() && !y.isNaN()) {
                    rowHits.lastOrNull { it.hit.contains(x, y) }?.publicId
                } else {
                    null
                }
                ModalVerdict(
                    selectedId = when {
                        loadMore -> ShadowRankedAttemptsClient.LOAD_MORE_ID
                        else -> rowId
                    },
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

    private fun containsPanel(x: Float, y: Float): Boolean =
        x >= panelLeft && x <= panelLeft + panelW &&
            y >= panelBottom && y <= panelBottom + panelH

    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        attempts: List<ChallengeEntry> = emptyList(),
        loading: Boolean = false,
        loadingMore: Boolean = false,
        error: String? = null,
        loadMoreError: String? = null,
        hasMore: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        Fonts.ensureCreated()
        loadMoreHit = null
        rowHits = emptyList()

        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 16f else 22f)
        val rowPad = Fonts.px(10f)
        val colGap = Fonts.px(24f)
        val inlineGap = Fonts.px(12f)
        val titleFont = Fonts.labelActive
        val labelFont = Fonts.label
        val itemFont = Fonts.button
        val headerFont = Fonts.button
        val closeInset = CloseButton.cornerInset()

        val lineGap = Fonts.px(4f)
        val attemptPadY = Fonts.px(10f)
        val scoreLineH = labelFont.lineHeight
        val baseRowH = attemptPadY + itemFont.lineHeight + lineGap + labelFont.lineHeight + attemptPadY
        val scoreRowH = baseRowH + lineGap + scoreLineH

        val statusText = when {
            error != null && attempts.isEmpty() -> error
            attempts.isEmpty() && loading -> L10n.get("shadowRanked.attempts.loading")
            attempts.isEmpty() -> L10n.get("shadowRanked.attempts.empty")
            else -> null
        }

        val loadMoreH = max(Fonts.px(44f), itemFont.lineHeight + Fonts.px(18f))
        val showLoadMore = hasMore || (loadingMore && attempts.isNotEmpty()) || loadMoreError != null
        val loadMoreLabel = when {
            loadingMore -> L10n.get("shadowRanked.attempts.loading")
            loadMoreError != null -> loadMoreError
            else -> L10n.get("history.load_more")
        }
        val extraFooterH = if (showLoadMore) loadMoreH + Fonts.px(8f) else 0f

        val showHeader = attempts.isNotEmpty()
        val headerChallenge = L10n.get("shadowRanked.attempts.col_challenge")
        val headerRating = L10n.get("shadowRanked.attempts.col_rating")

        // Hug the content: the card is as wide as its widest cell, clamped to a
        // readable floor and the viewport — the same "never wider than it must be"
        // policy the hands-table viewer uses for its panel.
        val (rowsLeftMax, rowsRatingW) = measureRows(attempts, itemFont, labelFont, inlineGap, nowMs)
        val leftMax = max(rowsLeftMax, if (showHeader) textWidth(headerFont, headerChallenge) else 0f)
        val ratingW = max(rowsRatingW, if (showHeader) textWidth(headerFont, headerRating) else 0f)
        val statusW = statusText?.let { textWidth(labelFont, it) } ?: 0f
        val loadMoreW = if (showLoadMore) textWidth(itemFont, loadMoreLabel) else 0f
        val contentW = rowPad * 2f + maxOf(leftMax + colGap + ratingW, statusW, loadMoreW)

        val rowHeights = attempts.map { if (it.hasScore()) scoreRowH else baseRowH }
        val bodyContentH = if (attempts.isEmpty()) {
            Fonts.px(120f)
        } else {
            rowHeights.sum() + extraFooterH
        }

        val titleRowH = max(closeButton.side, titleFont.lineHeight)
        val headerH = max(Fonts.px(24f), headerFont.lineHeight + Fonts.px(6f))
        val titleGap = Fonts.px(8f)
        val headerGap = Fonts.px(6f)
        val headerBlockH = if (showHeader) headerH + headerGap else 0f

        val avail = OverlayViewport.available(screenW, screenH)
        val chromeH = closeInset + titleRowH + titleGap + headerBlockH + pad
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH)
        val gutter = if (fit.maxScrollY > 0f) VerticalScrollbar.gutterWidth() else 0f

        val minPanelW = min(avail.width, Fonts.px(320f))
        panelW = (contentW + pad * 2f + gutter).coerceIn(minPanelW, avail.width)
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

        val title = L10n.get("shadowRanked.attempts.title")
        glyphLayout.setText(titleFont, title)
        val titleBaseline = panelBottom + panelH - closeInset - (closeButton.side - glyphLayout.height) * 0.5f
        titleFont.draw(batch, title, panelLeft + pad, titleBaseline)

        val titleBottom = panelBottom + panelH - closeInset - titleRowH
        val headerTop = titleBottom - titleGap
        val headerBottom = headerTop - headerH
        val listTop = if (showHeader) headerBottom - headerGap else titleBottom - titleGap
        listLeft = panelLeft + pad
        listW = panelW - pad * 2f
        listBottom = panelBottom + pad
        listH = max(Fonts.px(48f), listTop - listBottom)

        body.layout(listLeft, listBottom, listW, listH, bodyContentH, scrollY)
        val rowsW = listW - body.gutter

        if (showHeader) {
            drawHeader(
                batch, headerFont, listLeft, headerBottom, rowsW, headerH,
                headerChallenge, headerRating, rowPad,
            )
            drawRule(batch, px, listLeft, headerBottom, rowsW)
        }

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

        body.drawBody(batch, camera, px, hoverX, hoverY) {
            var yTop = body.contentTop
            attempts.forEachIndexed { index, entry ->
                val h = rowHeights[index]
                val rowBottom = yTop - h
                if (rowBottom < body.bottom + body.height && yTop > body.bottom) {
                    // Stripe, same as a leaderboard row. hoverX/hoverY are blank
                    // during a drag; hitsContent ignores the header and the scrollbar.
                    val hovered = body.hitsContent(hoverX, hoverY) &&
                        hoverY >= rowBottom && hoverY < yTop
                    drawAttempt(
                        batch, px, labelFont, itemFont, entry,
                        listLeft, rowBottom, rowsW, h, nowMs, hovered,
                    )
                }
                yTop = rowBottom
            }
            if (showLoadMore) {
                val btnBottom = yTop - Fonts.px(8f) - loadMoreH
                val btnTop = yTop - Fonts.px(8f)
                loadMoreHit = Hit(listLeft, btnBottom, rowsW, loadMoreH)
                if (btnBottom < body.bottom + body.height && btnTop > body.bottom) {
                    val hovered = !loadingMore &&
                        !hoverX.isNaN() &&
                        !hoverY.isNaN() &&
                        loadMoreHit!!.contains(hoverX, hoverY)
                    val bg = OverlayChrome.rowFill(hovered)
                    val rim = OverlayChrome.rowRim()
                    OverlayChrome.drawButton(
                        batch, px, itemFont, loadMoreLabel,
                        listLeft, btnBottom, rowsW, loadMoreH, bg, rim,
                        AppPalette.ink,
                    )
                }
            }
        }
    }

    /**
     * The header band's two column captions, aligned with the rows: Challenge over
     * the id/subtitle, Rating over the right-aligned rating column.
     */
    private fun drawHeader(
        batch: SpriteBatch,
        font: BitmapFont,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        challenge: String,
        rating: String,
        padX: Float,
    ) {
        glyphLayout.setText(font, challenge)
        val baseline = bottom + (height + glyphLayout.height) * 0.5f
        font.color = AppPalette.ink
        font.draw(batch, challenge, left + padX, baseline)
        glyphLayout.setText(font, rating)
        font.draw(batch, rating, left + width - padX - glyphLayout.width, baseline)
        font.color = Color.WHITE
    }

    private fun drawRule(batch: SpriteBatch, px: Texture, left: Float, y: Float, width: Float) {
        batch.setColor(AppPalette.tint(AppPalette.rim, 0.35f))
        batch.draw(px, left, y, width, max(1.5f, Fonts.px(1.5f)))
        batch.setColor(Color.WHITE)
    }

    /**
     * The widest left-column and rating-column text across [attempts], memoized by
     * list identity + UI scale. Measuring all rows (not just the visible ones) keeps
     * the card from resizing as the player scrolls.
     */
    private fun measureRows(
        attempts: List<ChallengeEntry>,
        itemFont: BitmapFont,
        labelFont: BitmapFont,
        inlineGap: Float,
        nowMs: Long,
    ): Pair<Float, Float> {
        if (measuredAttempts === attempts && measuredUiScale == Fonts.uiScale) {
            return measuredLeftMax to measuredRatingW
        }
        var leftMax = 0f
        var ratingW = 0f
        for (entry in attempts) {
            ratingW = max(ratingW, textWidth(itemFont, ShadowRankedFormat.signedRating(entry.ratingDelta)))
            // The code line: the challenge code, then the date and player count.
            val codeLine = textWidth(itemFont, entry.publicId) +
                inlineGap + textWidth(itemFont, attemptInlineMeta(entry, nowMs))
            leftMax = max(leftMax, codeLine)
            leftMax = max(
                leftMax,
                textWidth(labelFont, ShadowRankedFormat.rulesLabel(entry.rounds, entry.allowChicken)),
            )
            if (entry.hasScore()) {
                leftMax = max(leftMax, textWidth(labelFont, ShadowRankedFormat.scoreLine(entry.scores)))
            }
        }
        measuredAttempts = attempts
        measuredUiScale = Fonts.uiScale
        measuredLeftMax = leftMax
        measuredRatingW = ratingW
        return leftMax to ratingW
    }

    private fun textWidth(font: BitmapFont, text: String): Float {
        glyphLayout.setText(font, text)
        return glyphLayout.width
    }

    internal fun seedLayoutForTest(
        panelLeft: Float = 100f,
        panelBottom: Float = 50f,
        panelW: Float = 800f,
        panelH: Float = 600f,
        listLeft: Float = 120f,
        listBottom: Float = 70f,
        listW: Float = 760f,
        listH: Float = 500f,
        contentHeight: Float = 0f,
        scrollY: Float = 0f,
    ) {
        this.panelLeft = panelLeft
        this.panelBottom = panelBottom
        this.panelW = panelW
        this.panelH = panelH
        this.listLeft = listLeft
        this.listBottom = listBottom
        this.listW = listW
        this.listH = listH
        closeButton.layoutAt(panelLeft + panelW - 40f, panelBottom + panelH - 40f, 32f)
        body.layout(listLeft, listBottom, listW, listH, contentHeight, scrollY)
    }

    internal val isScrollbarDraggingForTest: Boolean
        get() = body.isScrollbarDrag

    internal val maxScrollForTest: Float
        get() = body.maxScrollY

    internal val gutterForTest: Float
        get() = body.gutter

    internal val overflowingForTest: Boolean
        get() = body.overflowing

    internal val listWidthForTest: Float
        get() = listW

    internal val listLeftForTest: Float
        get() = listLeft

    internal val listBottomForTest: Float
        get() = listBottom

    internal val listHeightForTest: Float
        get() = listH

    internal fun seedLoadMoreHitForTest(left: Float, bottom: Float, w: Float, h: Float) {
        loadMoreHit = Hit(left, bottom, w, h)
    }

    internal fun seedRowHitForTest(left: Float, bottom: Float, w: Float, h: Float, publicId: String) {
        rowHits = rowHits + RowHit(Hit(left, bottom, w, h), publicId)
    }

    private fun drawAttempt(
        batch: SpriteBatch,
        px: Texture,
        labelFont: BitmapFont,
        itemFont: BitmapFont,
        entry: ChallengeEntry,
        left: Float,
        bottom: Float,
        width: Float,
        height: Float,
        nowMs: Long,
        hovered: Boolean,
    ) {
        val gap = Fonts.px(4f)
        val padX = Fonts.px(10f)
        val inlineGap = Fonts.px(12f)

        batch.setColor(
            if (hovered) OverlayChrome.rowFill(hovered = true)
            else AppPalette.tint(AppPalette.plateDeep, 0.55f),
        )
        batch.draw(px, left, bottom + Fonts.px(2f), width, height - Fonts.px(4f))
        batch.setColor(Color.WHITE)

        // Code line: the challenge code, then its date and player count, with the
        // rating right-aligned.
        val line1 = bottom + height - Fonts.px(10f)
        itemFont.color = AppPalette.accent
        itemFont.draw(batch, entry.publicId, left + padX, line1)
        glyphLayout.setText(itemFont, entry.publicId)
        itemFont.color = AppPalette.inkMuted
        itemFont.draw(batch, attemptInlineMeta(entry, nowMs), left + padX + glyphLayout.width + inlineGap, line1)

        val rating = ShadowRankedFormat.signedRating(entry.ratingDelta)
        glyphLayout.setText(itemFont, rating)
        itemFont.color = ratingColor(entry.ratingDelta)
        itemFont.draw(batch, rating, left + width - padX - glyphLayout.width, line1)

        val line2 = line1 - itemFont.lineHeight - gap
        labelFont.color = AppPalette.inkMuted
        labelFont.draw(
            batch,
            ShadowRankedFormat.rulesLabel(entry.rounds, entry.allowChicken),
            left + padX,
            line2,
        )

        if (entry.hasScore()) {
            val line3 = line2 - labelFont.lineHeight - gap
            labelFont.color = AppPalette.inkWarm
            glyphLayout.setText(
                labelFont, ShadowRankedFormat.scoreLine(entry.scores),
                labelFont.color, width - padX * 2f, Align.left, true,
            )
            labelFont.draw(
                batch, ShadowRankedFormat.scoreLine(entry.scores), left + padX, line3,
                width - padX * 2f, Align.left, true,
            )
        }
        labelFont.color = Color.WHITE
        itemFont.color = Color.WHITE
        if (entry.isFinished()) {
            rowHits = rowHits + RowHit(
                Hit(left, bottom + Fonts.px(2f), width, height - Fonts.px(4f)),
                entry.publicId,
            )
        }
    }

    private fun ratingColor(delta: Double): Color = when {
        delta > 0.0 -> AppPalette.success
        delta < 0.0 -> AppPalette.danger
        else -> AppPalette.inkMuted
    }

    override fun dispose() {
        body.cancel()
    }
}

private fun ChallengeEntry.isFinished(): Boolean = status == ChallengeStatus.Finished

private fun ChallengeEntry.hasScore(): Boolean = isFinished() && scores.isNotEmpty()

/**
 * The code line's right-hand metadata: the attempt's local date, the running player
 * count, and — unless the attempt finished — its status.
 */
private fun attemptInlineMeta(entry: ChallengeEntry, nowMs: Long): String = buildString {
    append(MatchHistoryFormat.dayLabel(entry.startedAt, nowMs))
    append(" · ").append(ShadowRankedFormat.playersLabel(entry.players))
    if (!entry.isFinished()) {
        // A finished row's status is not worth saying; anything else is.
        append(" · ").append(ShadowRankedFormat.statusLabel(entry.status))
    }
}
