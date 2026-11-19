package inego.zungjunggame.features.leaderboard

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.AliasPolicy
import inego.zungjunggame.net.LeaderboardClient
import inego.zungjunggame.net.LeaderboardReading
import inego.zungjunggame.net.LeaderboardRow
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.Hyperlink
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.TextFit
import inego.zungjunggame.render.ui.VerticalScroll
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.Rect
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/** GL-free snapshot of the leaderboard card's bounds after the last render. */
data class LeaderboardGeometry(
    val panel: Rect?,
)

/**
 * The leaderboard card: the fetched windows of the pinned reading, with **Top**
 * and **Me** links above the list. Each is a [Hyperlink]. **Me** is omitted when
 * the viewer is not on the board. A link is disabled while its target intersects
 * the list viewport — row 0 for **Top**, the viewer's snapshot row for **Me** —
 * using the same intersection the row painter uses, so a sliver still counts.
 * An empty board counts as the top being on screen. A disabled link still
 * captures the press, so it does not drag the list, and the release reports no
 * scroll offset.
 *
 * Each row carries the place, the display name, the rating, and the two count
 * numbers. The count columns are labelled once, in a fixed header row between the
 * links and the list (`leaderboard.live` / `leaderboard.shadow_ranked`), so the
 * labels do not repeat on every row and do not scroll away with them. A row with
 * fewer than five combined counted games is drawn in the muted hub ink but keeps
 * its place (locked decision 5); the viewer's row is banded by its snapshot index,
 * never by comparing names.
 *
 * The list's scroll offset is in whole-snapshot coordinates: row `i` of the
 * snapshot sits at `i * rowH`, and each fetched window is painted at its own
 * absolute position. A jump to a far **Me** therefore lands immediately, and the
 * window under the viewport is fetched afterwards; rows the reading does not hold
 * yet simply leave their space blank for the frame or two the fetch takes. **Top**
 * and **Me** are scroll targets while they are live: the overlay computes the offset
 * and reports it as the verdict's `settledScrollOffset`, so the component writes it
 * as the route's own scroll state. [missingWindowOffset] reports the window the viewport has
 * reached but the reading does not hold, for the component to fetch.
 *
 * The columns are measured, not proportions: [LeaderboardGrid] sizes every
 * column to the widest thing it must draw — a digit sample for the numbers, the
 * alias policy's own bound for the display name — and the card is exactly as wide
 * as that table, centered on the screen. So the four places, two game counts and
 * display names of a board of any size line up down the list without a width
 * guess, and a desktop screen does not get a mostly-empty card with a name column
 * no one can fill.
 *
 * A release that ends a **tap** (not a scroll) on a row resolves that row's public
 * `history_id` as the verdict's `selectedId`, which opens the player's rating
 * history. A release on the blank space of an unfetched row, and a release that
 * ended a drag, resolve nothing here.
 */
class LeaderboardOverlay : Disposable, GestureSurface<ModalVerdict> {
    private val glyphLayout = GlyphLayout()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()
    private val closeButton = CloseButton()
    private val scroll = VerticalScroll()

    /**
     * The muted hub ink (see `HubDraw.drawCycleRow`) a row with fewer than five
     * counted games is drawn in — locked decision 5.
     */
    private val mutedInk = AppPalette.inkInert

    /** Ink for the fixed count-column header above the list. */
    private val headerInk = AppPalette.inkMuted

    private fun whitePixel(): Texture = WhitePixel.get()

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var listLeft = 0f
    private var listBottom = 0f
    private var listTop = 0f
    private var listW = 0f
    private var listH = 0f
    private var rowH = 0f

    private var topHit: Hit? = null
    private var meHit: Hit? = null

    /** True when the last render's **Top** link could still move the list. */
    private var topEnabled = false

    /** True when the last render's **Me** link could still move the list. */
    private var meEnabled = false

    /** The viewer's row in the snapshot, or null when not listed; drives the Me control. */
    private var selfOffset: Int? = null

    /** The reading painted last frame: its windows, total, and viewer row. */
    private var reading: LeaderboardReading? = null

    /**
     * The window the viewport reached but the reading did not hold, as of the last
     * [render]; the component reads it in `motion` and asks the effect to fetch it.
     */
    private var missingWindow: Int? = null

    private var pressTarget: PressTarget = PressTarget.None

    private enum class PressTarget { None, Close, Top, Me, Content }

    private data class Hit(
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= left && px <= left + w && py >= bottom && py <= bottom + h
    }

    /** The frame's offset, installed by [render] from the owner's value. */
    private var frameScrollY = 0f

    /** The frame's measured range, from the last [render]. */
    private var frameMaxScroll = 0f

    /**
     * Pixels the list is scrolled down (0 = top), reported to the
     * [inego.zungjunggame.ui.GestureBridge]'s consumed-scroll channel so the
     * component can emit it as the route's state.
     */
    override fun scrollOffset(): Float = frameScrollY

    override fun scrollCoasting(): Boolean = scroll.isCoasting

    /** Advance the list's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float {
        frameScrollY = scroll.advance(deltaSec, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return frameScrollY
    }

    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= panelLeft && worldX <= panelLeft + panelW &&
            worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /** GL-free snapshot of the card's bounds. */
    fun measureGeometry(): LeaderboardGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        return LeaderboardGeometry(panel)
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        frameScrollY = scroll.wheel(amountY, frameScrollY, frameMaxScroll)
            .coerceIn(0f, frameMaxScroll)
        return true
    }

    override fun onPointerDown(x: Float, y: Float): Boolean {
        scroll.cancel()
        pressTarget = PressTarget.None
        if (!containsPanel(x, y)) return false
        if (closeButton.contains(x, y)) {
            pressTarget = PressTarget.Close
            return true
        }
        if (topHit?.contains(x, y) == true) {
            pressTarget = PressTarget.Top
            return true
        }
        if (meHit?.contains(x, y) == true) {
            pressTarget = PressTarget.Me
            return true
        }
        pressTarget = PressTarget.Content
        scroll.beginDrag(y, frameScrollY)
        return true
    }

    override fun onPointerDrag(x: Float, y: Float): ModalVerdict? {
        if (pressTarget != PressTarget.Content) return null
        frameScrollY = scroll.dragTo(y, frameMaxScroll).coerceIn(0f, frameMaxScroll)
        return ModalVerdict(dragScrollOffset = frameScrollY)
    }

    override fun onPointerUp(x: Float, y: Float): ModalVerdict {
        val target = pressTarget
        pressTarget = PressTarget.None
        val inside = { hit: Hit? -> !x.isNaN() && !y.isNaN() && hit?.contains(x, y) == true }
        return when (target) {
            PressTarget.Close -> {
                scroll.cancel()
                ModalVerdict(close = !x.isNaN() && !y.isNaN() && closeButton.contains(x, y))
            }

            PressTarget.Top -> {
                scroll.cancel()
                ModalVerdict(settledScrollOffset = if (topEnabled && inside(topHit)) 0f else null)
            }

            PressTarget.Me -> {
                scroll.cancel()
                ModalVerdict(settledScrollOffset = if (meEnabled && inside(meHit)) meScrollOffset() else null)
            }

            PressTarget.Content -> {
                val wasDrag = scroll.endDrag()
                if (wasDrag) {
                    ModalVerdict(settledScrollOffset = frameScrollY)
                } else {
                    // A tap: the release selects the row it landed on, by that
                    // row's own public handle. Blank space (a window not fetched
                    // yet) selects nothing.
                    ModalVerdict(selectedId = rowHistoryIdAt(y))
                }
            }

            PressTarget.None -> {
                scroll.cancel()
                ModalVerdict()
            }
        }
    }

    /**
     * The offset that brings the viewer's row to the top of the list, or null when
     * the viewer is not on the board. The row is placed at its absolute snapshot
     * position, so a self outside the fetched windows still resolves; the window
     * under the viewport is fetched afterwards.
     */
    private fun meScrollOffset(): Float? {
        val index = selfOffset ?: return null
        return (index * rowH).coerceIn(0f, frameMaxScroll)
    }

    /**
     * The public handle of the row painted at [y] in the list, or null when no
     * fetched row is there.
     *
     * Rows are placed at whole-snapshot offsets ([listTop] + `frameScrollY` - `i` *
     * [rowH]), so a release resolves the row under it whether or not the list was
     * scrolled; the reading answers which row that index is, and a window that has
     * not landed stays blank rather than naming a neighbour.
     */
    private fun rowHistoryIdAt(y: Float): String? {
        val current = reading ?: return null
        if (y.isNaN() || rowH <= 0f || y < listBottom || y > listTop) return null
        val index = floor((listTop + frameScrollY - y) / rowH).toInt()
        return current.rowAt(index)?.historyId
    }

    /**
     * The window the viewport has reached but the reading does not hold, aligned to
     * [LeaderboardClient.PAGE_SIZE], or null when every visible row is covered.
     * Read by the component in `motion` to fetch it.
     */
    fun missingWindowOffset(): Int? = missingWindow

    private fun computeMissingWindow(): Int? {
        val current = reading ?: return null
        val total = current.total
        if (total <= 0 || rowH <= 0f || listH <= 0f) return null
        val first = floor(frameScrollY / rowH).toInt().coerceAtLeast(0)
        val last = floor((frameScrollY + listH) / rowH).toInt().coerceAtMost(total - 1)
        if (first > last) return null
        for (index in first..last) {
            if (!current.covers(index)) {
                return (index / LeaderboardClient.PAGE_SIZE) * LeaderboardClient.PAGE_SIZE
            }
        }
        return null
    }

    fun render(
        batch: SpriteBatch,
        camera: com.badlogic.gdx.graphics.Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        reading: LeaderboardReading? = null,
        loading: Boolean = false,
        error: String? = null,
    ) {
        Fonts.ensureCreated()
        topHit = null
        meHit = null
        topEnabled = false
        meEnabled = false
        this.reading = reading
        selfOffset = reading?.self?.offset
        missingWindow = null

        val portrait = screenH > screenW
        val pad = Fonts.px(if (portrait) 16f else 22f)
        val controlGap = Fonts.px(8f)
        val linkPadX = Fonts.px(8f)
        val titleFont = Fonts.labelActive
        val labelFont = Fonts.label
        val itemFont = Fonts.button
        val title = L10n.get("leaderboard.title")
        val topLabel = L10n.get("leaderboard.top")
        val meLabel = L10n.get("leaderboard.me")

        val marginX = if (portrait) screenW * 0.04f else screenW * 0.10f
        val marginY = if (portrait) screenH * 0.04f else screenH * 0.08f
        // The card is as wide as the widest thing it has to hold — the table, the
        // title, or the two links — and never wider than the screen, then
        // centered. A viewport-sized card on a desktop would be mostly empty, and
        // nothing inside it stretches to fill that. The table is measured from the
        // alias policy's own bound rather than from the row width, so sizing the
        // panel from it does not feed back into itself; only a screen too narrow for
        // the table clips the name.
        val maxPanelW = screenW - marginX * 2f
        val contentW = max(
            measureGrid(reading, Float.MAX_VALUE).contentW,
            max(
                Fonts.textWidth(titleFont, title),
                Hyperlink.width(topLabel) + Hyperlink.width(meLabel) + controlGap + linkPadX * 4f,
            ),
        ) + pad * 2f
        panelW = min(maxPanelW, contentW)
        panelLeft = marginX + (maxPanelW - panelW) * 0.5f
        panelBottom = marginY
        panelH = screenH - marginY * 2f

        val px = whitePixel()
        batch.setColor(AppPalette.scrim)
        batch.draw(px, 0f, 0f, screenW, screenH)
        OverlayChrome.drawPanel(
            batch, px, panelLeft, panelBottom, panelW, panelH,
            AppPalette.panel,
        )

        val closeInset = CloseButton.cornerInset()
        closeButton.layoutTopRight(panelLeft, panelBottom, panelW, panelH, closeInset)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)

        glyphLayout.setText(titleFont, title)
        val titleBaseline = panelBottom + panelH - closeInset - (closeButton.side - glyphLayout.height) * 0.5f
        titleFont.draw(batch, title, panelLeft + pad, titleBaseline)

        // Top / Me sit in the band between the title and the list, left-aligned and
        // only as wide as their labels. Me exists only while the viewer is on the
        // board. Whether each link is live depends on the viewport laid out below,
        // so they are painted once that geometry exists.
        val controlH = max(Fonts.px(40f), itemFont.lineHeight + Hyperlink.inkDepth())
        val controlTop = panelBottom + panelH - closeInset - closeButton.side - Fonts.px(10f)
        val controlBottom = controlTop - controlH
        val topRect = Hit(
            panelLeft + pad,
            controlBottom,
            Hyperlink.width(topLabel) + linkPadX * 2f,
            controlH,
        )
        val meRect = Hit(
            topRect.left + topRect.w + controlGap,
            controlBottom,
            Hyperlink.width(meLabel) + linkPadX * 2f,
            controlH,
        )

        // A fixed column-header row sits between the links and the list: the two
        // count labels are drawn once, pinned here, and the scrolled rows below
        // carry only their numbers.
        val ruleH = max(1.5f, Fonts.px(1.5f))
        val headerH = max(Fonts.px(22f), itemFont.lineHeight + Fonts.px(8f))
        val headerTop = controlBottom - Fonts.px(8f)
        val headerBottom = headerTop - headerH

        listTop = headerBottom - ruleH - Fonts.px(4f)
        listBottom = panelBottom + pad
        listH = max(Fonts.px(48f), listTop - listBottom)

        // The list is the table's own width, centered in whatever the card has room
        // for: a viewport-sized row would either clip the name or pad it out with
        // empty space, since no display name is wide enough to fill a desktop card.
        val grid = measureGrid(reading, panelW - pad * 2f)
        listW = grid.contentW
        listLeft = panelLeft + (panelW - listW) * 0.5f

        drawHeader(batch, Fonts.small, grid, listLeft, headerBottom, headerH)
        batch.setColor(AppPalette.tint(AppPalette.panelRim, 0.35f))
        batch.draw(px, listLeft, headerBottom - ruleH, listW, ruleH)
        batch.setColor(Color.WHITE)

        // The list is as long as the snapshot, not as long as what is fetched: a
        // window that has not landed yet leaves its rows' space blank rather than
        // shrinking the scroll range.
        rowH = max(Fonts.px(44f), itemFont.lineHeight + Fonts.px(18f))
        val totalRows = reading?.total ?: 0
        frameMaxScroll = max(0f, totalRows * rowH - listH)
        frameScrollY = scrollY.coerceIn(0f, frameMaxScroll)
        missingWindow = computeMissingWindow()

        topEnabled = !topOnScreen()
        meEnabled = selfOffset != null && !selfOnScreen()
        topHit = topRect
        if (selfOffset != null) meHit = meRect
        drawLink(batch, px, topRect, topLabel, topEnabled, pointerWorldX, pointerWorldY)
        if (selfOffset != null) {
            drawLink(batch, px, meRect, meLabel, meEnabled, pointerWorldX, pointerWorldY)
        }

        val current = reading
        if (current == null || current.isEmpty) {
            val statusText = when {
                error != null -> error
                loading -> L10n.get("history.loading")
                totalRows == 0 -> L10n.get("leaderboard.empty")
                else -> L10n.get("history.loading")
            }
            labelFont.color = AppPalette.inkMuted
            glyphLayout.setText(labelFont, statusText, labelFont.color, listW, Align.center, true)
            labelFont.draw(
                batch,
                statusText,
                listLeft,
                listBottom + listH * 0.5f + glyphLayout.height * 0.5f,
                listW,
                Align.center,
                true,
            )
            labelFont.color = Color.WHITE
            return
        }

        batch.flush()
        clipBounds.set(listLeft, listBottom, listW, listH)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val scissorOn = ScissorStack.pushScissors(scissors)
        if (!scissorOn) return
        for ((offset, windowRows) in current.windows) {
            windowRows.forEachIndexed { position, row ->
                val index = offset + position
                val rowBottom = listTop + frameScrollY - (index + 1) * rowH
                if (rowIntersectsViewport(index)) {
                    // The viewer is marked by their index in the snapshot, never by
                    // their name (locked decision 7): names are not unique.
                    drawRow(
                        batch, px, itemFont, grid, row,
                        isSelf = index == selfOffset,
                        hovered = rowUnderPointer(pointerWorldX, pointerWorldY, rowBottom),
                        left = listLeft, bottom = rowBottom, w = listW, h = rowH,
                    )
                }
            }
        }
        batch.flush()
        ScissorStack.popScissors()
    }

    /**
     * One scroll link, centred in its own target. [enabled] false is the widget's
     * disabled ink and no hover. The hit is still published by the caller, so the
     * press does not fall through onto the list.
     */
    private fun drawLink(
        batch: SpriteBatch,
        px: Texture,
        rect: Hit,
        label: String,
        enabled: Boolean,
        pointerX: Float,
        pointerY: Float,
    ) {
        val hovered = enabled &&
            !pointerX.isNaN() && !pointerY.isNaN() &&
            rect.contains(pointerX, pointerY)
        Hyperlink.drawCentered(
            batch = batch,
            whitePixel = px,
            label = label,
            rect = Rect(rect.left, rect.bottom, rect.w, rect.h),
            hovered = hovered,
            enabled = enabled,
        )
    }

    /**
     * True when row [index] meets the list viewport, including a sliver. This is
     * the painter's test: [render] draws a row only when this is true, and the
     * scroll links disable from it.
     */
    private fun rowIntersectsViewport(index: Int): Boolean {
        if (rowH <= 0f) return false
        val rowTop = listTop + frameScrollY - index * rowH
        val rowBottom = rowTop - rowH
        return rowTop > listBottom && rowBottom < listTop
    }

    /**
     * The top of the board is on screen when row 0 meets the viewport, or when
     * there is no row yet. An empty or unloaded board is already showing its top.
     */
    private fun topOnScreen(): Boolean {
        val total = reading?.total ?: 0
        if (total <= 0) return true
        return rowIntersectsViewport(0)
    }

    /** The viewer's row meets the viewport. False when the viewer is not listed. */
    private fun selfOnScreen(): Boolean {
        val index = selfOffset ?: return false
        return rowIntersectsViewport(index)
    }

    /**
     * The pointer is on the fetched row whose bottom is [rowBottom]. A drag is a
     * scroll, so the row under the pointer is not a hover. The test is clipped to
     * the list: a row that only peeks into the viewport does not highlight when
     * the pointer is on the header or the padding past the clip.
     */
    private fun rowUnderPointer(pointerX: Float, pointerY: Float, rowBottom: Float): Boolean {
        if (scroll.isDrag) return false
        if (pointerX.isNaN() || pointerY.isNaN()) return false
        if (pointerX < listLeft || pointerX > listLeft + listW) return false
        if (pointerY < listBottom || pointerY > listTop) return false
        return pointerY >= rowBottom && pointerY < rowBottom + rowH
    }

    /**
     * One row of the reading: the place, the display name, the rating, and the two
     * counted-game numbers. The count labels live once in the fixed [drawHeader]
     * row, not on every row. Columns are right-aligned so the numbers line up down
     * the list; the place owns the left margin and is left-aligned there, and the
     * name is ellipsized into the space [LeaderboardGrid] left it.
     *
     * Locked decision 5: fewer than five combined counted games draws the whole
     * row in the muted hub ink, at its own place. [isSelf] bands the viewer's row
     * (matched by snapshot index, not by name). [hovered] paints the shared
     * tappable-row fill and no rim: a leaderboard line is a stripe, so the
     * cursor changes its colour and does not frame it.
     */
    private fun drawRow(
        batch: SpriteBatch,
        px: Texture,
        itemFont: BitmapFont,
        grid: LeaderboardGrid,
        row: LeaderboardRow,
        isSelf: Boolean,
        hovered: Boolean,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
    ) {
        val plateBottom = bottom + Fonts.px(2f)
        val plateH = h - Fonts.px(4f)
        batch.setColor(
            if (hovered) OverlayChrome.rowFill(hovered = true)
            else AppPalette.tint(AppPalette.plateDeep, 0.55f),
        )
        batch.draw(px, left, plateBottom, w, plateH)
        batch.setColor(Color.WHITE)
        // The viewer's own row carries a faint band over its plate.
        if (isSelf) {
            batch.setColor(AppPalette.tint(AppPalette.accent, 0.12f))
            batch.draw(px, left, plateBottom, w, plateH)
            batch.setColor(Color.WHITE)
        }

        val baseline = bottom + h * 0.5f + itemFont.capHeight * 0.5f
        val muted = row.liveGames + row.shadowRankedGames < 5
        val accent = if (muted) mutedInk else AppPalette.accent
        val ivory = if (muted) mutedInk else AppPalette.ink

        // The shared columns place every cell; the header uses the same geometry, so
        // its labels sit over the numbers they name.
        val c = grid.columns(left)

        itemFont.color = accent
        itemFont.draw(batch, row.place.toString(), c.placeLeft, baseline)

        Fonts.drawText(
            batch,
            itemFont,
            TextFit.ellipsize(itemFont, row.displayName, c.nameW),
            c.nameLeft,
            baseline,
            ivory,
        )

        itemFont.color = accent
        itemFont.draw(
            batch, row.rating.toString(),
            c.ratingRight - c.ratingW, baseline, c.ratingW, Align.right, false,
        )

        // The two count cells draw their numbers only; the labels are the header.
        drawCount(batch, itemFont, row.liveGames, c.liveRight, c.liveW, baseline, ivory)
        drawCount(batch, itemFont, row.shadowRankedGames, c.shrRight, c.shrW, baseline, ivory)

        itemFont.color = Color.WHITE
    }

    /**
     * The fixed count-column header, drawn once above the scrolled list. It reuses
     * the row's [grid], so each label is right-aligned at the same edge as the
     * numbers in its column and cannot drift from them. The first three columns
     * (place, name, rating) have no label key, so their header cells are left empty.
     *
     * Drawn in [Fonts.small], not the row face, and each count column is measured
     * wide enough for its label as well as its numbers, so the label is never
     * truncated and never runs left into the neighbouring column in a translation.
     */
    private fun drawHeader(
        batch: SpriteBatch,
        font: BitmapFont,
        grid: LeaderboardGrid,
        left: Float,
        bottom: Float,
        h: Float,
    ) {
        val c = grid.columns(left)
        val baseline = bottom + h * 0.5f + font.capHeight * 0.5f
        val previousInk = font.color
        font.color = headerInk
        font.draw(
            batch, L10n.get("leaderboard.shadow_ranked"),
            c.shrRight - c.shrW, baseline, c.shrW, Align.right, false,
        )
        font.draw(
            batch, L10n.get("leaderboard.live"),
            c.liveRight - c.liveW, baseline, c.liveW, Align.right, false,
        )
        font.color = previousInk
    }

    /**
     * One counted-game cell: its number, right-aligned at [colRight] so the numbers
     * line up across rows. The label is drawn once in [drawHeader].
     */
    private fun drawCount(
        batch: SpriteBatch,
        font: BitmapFont,
        value: Int,
        colRight: Float,
        colW: Float,
        baseline: Float,
        ink: Color,
    ) {
        font.color = ink
        font.draw(batch, countLabel(value), colRight - colW, baseline, colW, Align.right, false)
    }

    /**
     * A counted-game cell's text: a zero is "no games here yet", and a column of
     * bare zeroes reads as data rather than absence, so it is written as a dash.
     * The cell is right-aligned like any other, so the dash sits on the same edge
     * as the digits around it.
     *
     * Only the two count columns go through this. The place is never zero and a
     * rating of exactly zero is not a state the game produces, so there is nothing
     * for them to spell.
     */
    private fun countLabel(value: Int): String = if (value == 0) "-" else value.toString()

    /**
     * The row's cell edges in one row's left-to-right order: the place owns the
     * left margin, then the name, the rating, live and Shadow Ranked. Every number
     * is right-aligned at its column's right edge. Resolved once per row by
     * [LeaderboardGrid.columns] and shared by [drawRow] and [drawHeader] so the two
     * cannot disagree on where a column is.
     */
    private data class Columns(
        val placeLeft: Float,
        val nameLeft: Float,
        val nameW: Float,
        val ratingW: Float,
        val ratingRight: Float,
        val liveW: Float,
        val liveRight: Float,
        val shrW: Float,
        val shrRight: Float,
    )

    /**
     * The table's column widths, measured against the width available for one row.
     *
     * Every column is as wide as the widest thing it must ever draw, and nothing
     * more:
     *  - a repeated-digit sample in the row face, for the numbers. The place is
     *    bounded exactly by the snapshot's own [total]; the rating and the two
     *    game counts are bounded by nothing the client knows, so they carry a fixed
     *    digit budget. Each count column is measured to fit its header word as well
     *    as its numbers, because [drawHeader] draws that word untruncated.
     *  - [widestLegalNameW], for the display name, because a name is not free-form:
     *    the alias policy bounds it, so padding the column out to a viewport width
     *    would reserve room no one can type into.
     *
     * A name wider than its column still ellipsizes; on a row too narrow even for
     * that, the whole grid shrinks together and the name is given nothing. Nothing
     * is scaled the way the hands table scales its faces: a row is a fixed height
     * and the list scrolls by whole rows, so there is no wrapping and no zoom to
     * fall back on.
     *
     * The widths never read the fetched rows: [render] runs every frame against a
     * reading whose windows grow, and a width taken from the rows in hand would
     * shift the columns under the player as pages land. [total], the digit budgets
     * and the alias bound are all stable, so the table only reflows when the board
     * or the panel does.
     */
    private data class LeaderboardGrid(
        val placeW: Float,
        val ratingW: Float,
        val liveW: Float,
        val shrW: Float,
        val gap: Float,
        val padX: Float,
        /** The name column: [widestLegalNameW], or what the row has left over. */
        val nameW: Float,
    ) {
        /** The row width the numbers claim, margins and gaps included. */
        val fixedW: Float get() = padX * 2f + placeW + ratingW + liveW + shrW + gap * 4f

        /** The row width the whole table claims: the numbers' share plus the name. */
        val contentW: Float get() = fixedW + nameW

        /** The cell edges of one row at [left], laid out left to right. */
        fun columns(left: Float): Columns {
            val placeLeft = left + padX
            val nameLeft = placeLeft + placeW + gap
            val ratingLeft = nameLeft + nameW + gap
            val ratingRight = ratingLeft + ratingW
            val liveLeft = ratingRight + gap
            val liveRight = liveLeft + liveW
            val shrLeft = liveRight + gap
            return Columns(
                placeLeft = placeLeft,
                nameLeft = nameLeft,
                nameW = nameW,
                ratingW = ratingW,
                ratingRight = ratingRight,
                liveW = liveW,
                liveRight = liveRight,
                shrW = shrW,
                shrRight = shrLeft + shrW,
            )
        }

        /** This grid shrunk by [factor], name and all: the degenerate fit. */
        fun scaled(factor: Float): LeaderboardGrid = copy(
            placeW = placeW * factor,
            ratingW = ratingW * factor,
            liveW = liveW * factor,
            shrW = shrW * factor,
            gap = gap * factor,
            padX = padX * factor,
            nameW = nameW * factor,
        )
    }

    /**
     * [reading]'s table measured against the [availW] a row may occupy: the widest
     * legal name, but never more room than the row has left over.
     */
    private fun measureGrid(reading: LeaderboardReading?, availW: Float): LeaderboardGrid {
        val rowFont = Fonts.button
        val headerFont = Fonts.small
        val counts = digitsWidth(rowFont, COUNT_DIGITS)
        val grid = LeaderboardGrid(
            placeW = digitsWidth(rowFont, max(PLACE_DIGITS_MIN, digits(reading?.total ?: 0))),
            ratingW = Fonts.textWidth(rowFont, "-" + "9".repeat(RATING_DIGITS)),
            liveW = max(counts, Fonts.textWidth(headerFont, L10n.get("leaderboard.live"))),
            shrW = max(counts, Fonts.textWidth(headerFont, L10n.get("leaderboard.shadow_ranked"))),
            gap = Fonts.px(8f),
            padX = Fonts.px(10f),
            nameW = 0f,
        )
        val fixed = grid.fixedW
        // Numbers too wide for the row: shrink them together and drop the name,
        // the only way to keep a number from being cut off.
        if (fixed > availW && fixed > 0f) return grid.scaled(availW / fixed)
        return grid.copy(nameW = min(availW - fixed, widestLegalNameW(rowFont)))
    }

    /**
     * The width of the widest display name the server can return.
     *
     * A stored name is bounded twice: by [AliasPolicy.STORED_GRAPHEMES] glyphs —
     * the old rule's names are never rewritten, so the write limit is not the bound
     * here — and by that many terminal columns, which a CJK name spends two at a
     * time and so is half as many glyphs. Both are measured and the wider wins: a
     * bound that clips a stored name is worse than one that leaves a pixel spare.
     */
    private fun widestLegalNameW(font: BitmapFont): Float = max(
        Fonts.textWidth(font, "W".repeat(AliasPolicy.STORED_GRAPHEMES)),
        Fonts.textWidth(font, "完".repeat(AliasPolicy.STORED_GRAPHEMES / 2)),
    )

    override fun dispose() {
    }
}

/** The place column never narrows below this, so a small board still has a margin. */
private const val PLACE_DIGITS_MIN = 2

/** Rating sample: [RATING_DIGITS] digits and a sign, none of them bounded by the client. */
private const val RATING_DIGITS = 4

/** The game-count columns: the widest number either count is given room for. */
private const val COUNT_DIGITS = 3

/** A column is never sized past this many digits, however large the snapshot. */
private const val MAX_SAMPLE_DIGITS = 6

private fun digits(value: Int): Int = value.coerceAtLeast(0).toString().length

/** The width of an [n]-digit sample in [font]: what a numeric column must hold. */
private fun digitsWidth(font: BitmapFont, n: Int): Float =
    Fonts.textWidth(font, "9".repeat(n.coerceIn(1, MAX_SAMPLE_DIGITS)))
