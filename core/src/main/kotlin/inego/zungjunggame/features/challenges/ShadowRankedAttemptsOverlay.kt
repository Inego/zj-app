package inego.zungjunggame.features.challenges

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.*
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.FittedLabel
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.TextFit
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
 * The card is a **table with two columns** (the challenge cell and the rating):
 * their widths come from budgets that cannot move while the card is open — the
 * headers, the rating's formatter shape, the code line's format budget and the
 * card's readable floor — never from the attempts currently in hand, so a second
 * page cannot resize the card or move a column already on screen. Each row then measures its own lines at
 * the cell width it will paint at, and those measured heights are the row's
 * stride: a wrapped rules or score line grows the row instead of painting over
 * the next one.
 *
 * The list is a window, not a wall: only the rows the card actually shows are
 * painted (the rest are culled by the scissor range), so a thousand attempts
 * cost the same as ten.
 */
class ShadowRankedAttemptsOverlay : Disposable, GestureSurface<ModalVerdict> {
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

    // The rows' measured layout, memoized by the list identity and every input
    // that can change a measurement: the cell width, the font metrics, the locale
    // (the rules, status and day words are translated) and the wall clock (the
    // inline meta says Today/Yesterday). Paging changes none of them, so a later
    // page appends rows without moving a column already on screen.
    private var measuredAttempts: List<ChallengeEntry>? = null
    private var measuredCellW = Float.NaN
    private var measuredUiScale = Float.NaN
    private var measuredLocale: AppLocale? = null
    private var measuredMinute = Long.MIN_VALUE
    private var measuredRows: List<AttemptRow> = emptyList()

    /**
     * One attempt as it will paint: the strings already fitted to the challenge
     * cell, the width of the code, and the height each line needs there.
     *
     * The heights are this layout's own output — [drawAttempt] stacks the same
     * boxes, never re-derives one — so measurement and paint cannot disagree
     * about whether a line wrapped.
     */
    private data class AttemptRow(
        val entry: ChallengeEntry,
        val id: String,
        val idW: Float,
        val meta: String,
        val rules: String,
        val score: String?,
        val line1H: Float,
        val rulesH: Float,
        val scoreH: Float,
        val height: Float,
    )

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

        // Column budgets. Neither column is measured from the rows in hand: the
        // rating comes from the formatter's own shape, the challenge cell from the
        // card's readable floor, the column headers, and the code line's format
        // budget (a code, a date and a two-digit player count). That is what keeps
        // a second page from resizing the card or moving a column mid-view.
        val reservedGutter = VerticalScrollbar.gutterWidth()
        val ratingW = max(
            max(
                Fonts.textWidth(itemFont, RATING_BUDGET_POSITIVE),
                Fonts.textWidth(itemFont, RATING_BUDGET_NEGATIVE),
            ),
            Fonts.textWidth(headerFont, headerRating),
        )
        val floorCellW = (
            Fonts.px(320f) - pad * 2f - reservedGutter - rowPad * 2f - colGap - ratingW
            ).coerceAtLeast(Fonts.px(96f))
        val codeLineBudget = Fonts.textWidth(itemFont, ID_BUDGET) + inlineGap +
            Fonts.textWidth(
                itemFont,
                META_BUDGET_PREFIX + ShadowRankedFormat.playersLabel(PLAYERS_BUDGET),
            )
        val challengeDesire = max(
            max(floorCellW, Fonts.textWidth(headerFont, headerChallenge)),
            codeLineBudget,
        )
        val contentW =
            pad * 2f + reservedGutter + rowPad * 2f + challengeDesire + colGap + ratingW

        val avail = OverlayViewport.available(screenW, screenH)
        val minPanelW = min(avail.width, Fonts.px(320f))
        panelW = contentW.coerceIn(minPanelW, avail.width)

        val innerW = (panelW - pad * 2f).coerceAtLeast(0f)
        // The scrollbar strip is reserved whether or not this list overflows, so
        // the cell width cannot change the moment the bar appears.
        val rowsW = (innerW - reservedGutter).coerceAtLeast(0f)
        val challengeW = (rowsW - rowPad * 2f - colGap - ratingW).coerceAtLeast(Fonts.px(32f))
        val rows = layoutRows(
            attempts, itemFont, labelFont, challengeW,
            attemptPadY, lineGap, inlineGap, nowMs,
        )
        val statusH = statusText?.let {
            Fonts.wrappedHeight(labelFont, it, rowsW, Align.center)
        } ?: 0f
        val bodyContentH = if (attempts.isEmpty()) {
            max(Fonts.px(120f), statusH + Fonts.px(48f))
        } else {
            rows.fold(0f) { sum, row -> sum + row.height } + extraFooterH
        }

        val closeSide = CloseButton.defaultSide()
        val titleRowH = max(closeSide, titleFont.lineHeight)
        val headerH = max(Fonts.px(24f), headerFont.lineHeight + Fonts.px(6f))
        val titleGap = Fonts.px(8f)
        val headerGap = Fonts.px(6f)
        val headerBlockH = if (showHeader) headerH + headerGap else 0f

        val chromeH = closeInset + titleRowH + titleGap + headerBlockH + pad
        val fit = OverlayViewport.fitSticky(avail.height, chromeH, bodyContentH)
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

        // The title owns the band left of the close control and fits it: a long
        // translation shrinks to the readability floor instead of clipping at the
        // rim (the same move a plate caption gets).
        val title = L10n.get("shadowRanked.attempts.title")
        val titleSlot = (closeButton.leftX - CloseButton.siblingGap() - (panelLeft + pad))
            .coerceAtLeast(0f)
        FittedLabel.draw(
            batch, titleFont, title,
            panelLeft + pad,
            panelBottom + panelH - closeInset - closeSide * 0.5f,
            titleSlot,
            titleFont.color,
        )

        val titleBottom = panelBottom + panelH - closeInset - titleRowH
        val headerTop = titleBottom - titleGap
        val headerBottom = headerTop - headerH
        val listTop = if (showHeader) headerBottom - headerGap else titleBottom - titleGap
        listLeft = panelLeft + pad
        listW = innerW
        listBottom = panelBottom + pad
        listH = max(Fonts.px(48f), listTop - listBottom)

        body.layout(listLeft, listBottom, listW, listH, bodyContentH, scrollY)

        if (showHeader) {
            drawHeader(
                batch, headerFont, listLeft, headerBottom, rowsW, headerH,
                headerChallenge, headerRating, rowPad, challengeW, ratingW,
            )
            drawRule(batch, px, listLeft, headerBottom, rowsW)
        }

        if (statusText != null) {
            // Centred in the body: the block's top is half its measured height
            // above the centre, the same box the empty-state height reserved.
            Fonts.drawWrapped(
                batch, labelFont, statusText,
                listLeft, listBottom + listH * 0.5f + statusH * 0.5f,
                rowsW, Align.center, AppPalette.inkMuted,
            )
            return
        }

        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)

        body.drawBody(batch, camera, px, hoverX, hoverY) {
            var yTop = body.contentTop
            rows.forEach { row ->
                val rowBottom = yTop - row.height
                if (rowBottom < body.bottom + body.height && yTop > body.bottom) {
                    // Stripe, same as a leaderboard row. hoverX/hoverY are blank
                    // during a drag; hitsContent ignores the header and the scrollbar.
                    val hovered = body.hitsContent(hoverX, hoverY) &&
                        hoverY >= rowBottom && hoverY < yTop
                    drawAttempt(
                        batch, px, labelFont, itemFont, row,
                        listLeft, rowBottom, rowsW, challengeW, ratingW,
                        attemptPadY, lineGap, inlineGap, hovered,
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
        challengeW: Float,
        ratingW: Float,
    ) {
        val centerY = bottom + height * 0.5f
        // Each caption owns the same column its cells do, so a translation widens
        // its column (the width budgets measure it) instead of being clipped.
        FittedLabel.draw(
            batch, font, challenge,
            left + padX, centerY, challengeW, AppPalette.ink,
        )
        FittedLabel.draw(
            batch, font, rating,
            left + width - padX - ratingW, centerY, ratingW, AppPalette.ink,
            align = FittedLabel.Align.Right,
        )
    }

    private fun drawRule(batch: SpriteBatch, px: Texture, left: Float, y: Float, width: Float) {
        batch.setColor(AppPalette.tint(AppPalette.rim, 0.35f))
        batch.draw(px, left, y, width, max(1.5f, Fonts.px(1.5f)))
        batch.setColor(Color.WHITE)
    }

    /**
     * Every attempt fitted to [cellW] and measured at it: the code, the metadata
     * that survives beside it, the rules line and the score line, with the height
     * each needs. Memoized by list identity, cell width, face metrics, locale and
     * the current minute — the inputs that can change an answer.
     */
    private fun layoutRows(
        attempts: List<ChallengeEntry>,
        itemFont: BitmapFont,
        labelFont: BitmapFont,
        cellW: Float,
        padY: Float,
        lineGap: Float,
        inlineGap: Float,
        nowMs: Long,
    ): List<AttemptRow> {
        val locale = L10n.currentLocale
        val minute = nowMs / MINUTE_MS
        if (measuredAttempts === attempts &&
            measuredCellW == cellW &&
            measuredUiScale == Fonts.uiScale &&
            measuredLocale == locale &&
            measuredMinute == minute
        ) {
            return measuredRows
        }

        val rows = attempts.map { entry ->
            // The code is the row's identity: it keeps the cell, and the metadata
            // beside it is what gets cut when the cell cannot hold both.
            val id = TextFit.ellipsize(itemFont, entry.publicId, cellW)
            val idW = Fonts.textWidth(itemFont, id)
            val meta = TextFit.ellipsize(
                itemFont,
                attemptInlineMeta(entry, nowMs),
                cellW - idW - inlineGap,
            )
            val rules = ShadowRankedFormat.rulesLabel(entry.rounds, entry.allowChicken)
            val rulesH = Fonts.wrappedHeight(labelFont, rules, cellW, Align.left)
            val score = if (entry.hasScore()) {
                ShadowRankedFormat.scoreLine(entry.scores)
            } else {
                null
            }
            val scoreH = if (score != null) {
                Fonts.wrappedHeight(labelFont, score, cellW, Align.left)
            } else {
                0f
            }
            val line1H = max(
                Fonts.textHeight(itemFont, id),
                Fonts.textHeight(itemFont, meta),
            )
            AttemptRow(
                entry = entry,
                id = id,
                idW = idW,
                meta = meta,
                rules = rules,
                score = score,
                line1H = line1H,
                rulesH = rulesH,
                scoreH = scoreH,
                height = padY + line1H + lineGap + rulesH +
                    (if (score != null) lineGap + scoreH else 0f) + padY,
            )
        }

        measuredAttempts = attempts
        measuredCellW = cellW
        measuredUiScale = Fonts.uiScale
        measuredLocale = locale
        measuredMinute = minute
        measuredRows = rows
        return rows
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

    /**
     * Paints one row from the layout that measured it. Every block starts at the
     * top [AttemptRow] reserved for it, so a wrapped line consumes the height it
     * was measured at and the next line starts below it — never over it.
     */
    private fun drawAttempt(
        batch: SpriteBatch,
        px: Texture,
        labelFont: BitmapFont,
        itemFont: BitmapFont,
        row: AttemptRow,
        left: Float,
        bottom: Float,
        width: Float,
        challengeW: Float,
        ratingW: Float,
        padY: Float,
        lineGap: Float,
        inlineGap: Float,
        hovered: Boolean,
    ) {
        val padX = Fonts.px(10f)

        batch.setColor(
            if (hovered) OverlayChrome.rowFill(hovered = true)
            else AppPalette.tint(AppPalette.plateDeep, 0.55f),
        )
        batch.draw(px, left, bottom + Fonts.px(2f), width, row.height - Fonts.px(4f))
        batch.setColor(Color.WHITE)

        // Code line: the challenge code, then the metadata that survived beside
        // it, with the rating right-aligned in its own column. [Fonts.drawText]
        // carries the primary + fallback pair, and the code and meta widths come
        // from the same fit that cut the meta.
        var top = bottom + row.height - padY
        Fonts.drawText(batch, itemFont, row.id, left + padX, top, AppPalette.accent)
        if (row.meta.isNotEmpty()) {
            Fonts.drawText(
                batch, itemFont, row.meta,
                left + padX + row.idW + inlineGap, top, AppPalette.inkMuted,
            )
        }

        val rating = ShadowRankedFormat.signedRating(row.entry.ratingDelta)
        Fonts.drawText(
            batch, itemFont, rating,
            left + width - padX - Fonts.textWidth(itemFont, rating), top,
            ratingColor(row.entry.ratingDelta),
        )

        top -= row.line1H + lineGap
        Fonts.drawWrapped(
            batch, labelFont, row.rules,
            left + padX, top, challengeW, Align.left, AppPalette.inkMuted,
        )

        if (row.score != null) {
            top -= row.rulesH + lineGap
            Fonts.drawWrapped(
                batch, labelFont, row.score,
                left + padX, top, challengeW, Align.left, AppPalette.inkWarm,
            )
        }

        if (row.entry.isFinished()) {
            rowHits = rowHits + RowHit(
                Hit(left, bottom + Fonts.px(2f), width, row.height - Fonts.px(4f)),
                row.entry.publicId,
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

/**
 * The rating column's display budget: a sign, two whole digits and the two
 * decimals [ShadowRankedFormat.signedRating] always writes. Both signs are
 * sampled because they need not share a width. The server declares no rating
 * bound, so this is a budget, not a proof — a wider value is right-aligned out
 * of the cell rather than moving the column.
 */
private const val RATING_BUDGET_POSITIVE = "+00.00"
private const val RATING_BUDGET_NEGATIVE = "\u221200.00"

/**
 * The code line's format budget: the challenge code, then the inline meta at its
 * widest fixed shape — a ten-digit date and a two-digit player count. The date
 * can also read Today/Yesterday, which is shorter in every bundle; the status a
 * live attempt appends is the part that gets cut when a translation outgrows it.
 */
private const val ID_BUDGET = "XXXX-XXXX"
private const val META_BUDGET_PREFIX = "0000-00-00 \u00B7 "
private const val PLAYERS_BUDGET = 99

/** The inline meta's day label is a function of wall time; one re-measure a minute. */
private const val MINUTE_MS = 60_000L

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
