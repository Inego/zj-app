package inego.zungjunggame.features.ratinghistory

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.chart.ChartViewport
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.RatingCandle
import inego.zungjunggame.net.RatingHistoryFailure
import inego.zungjunggame.net.RatingHistoryPublication
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.HandsTableText
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/** The three explicit controls' rects, in the title row or the row under it. */
data class RatingHistoryControlsGeometry(
    val zoomOut: Rect,
    val zoomIn: Rect,
    val allTime: Rect,
) {
    val rects: List<Rect> get() = listOf(zoomOut, zoomIn, allTime)
}

/**
 * GL-free bounds of the rating-history surface for one viewport, one footer
 * answer and one set of safe insets: the surface, its title band, the explicit
 * controls, the plot, the optional failure band with its Retry control, and the
 * close control.
 *
 * [measure] and [RatingHistoryOverlay.render] both derive from this one shape, so
 * the published hit region and the painted control cannot disagree — and [plot]
 * is the seam the chart itself arrives through: E2 reads gestures over it and E3
 * clips its candles to it. Controls and Retry sit **outside** [plot], so the
 * chart's gesture claim can never swallow them.
 *
 * The surface reserves no band it has nothing to put in: the controls share the
 * title row whenever the name still gets its budget, and [footer] exists only
 * while there is a failure to name. A healthy surface hands everything below the
 * title to the plot.
 */
data class RatingHistoryGeometry(
    /** The surface itself: the whole window, edge to edge, cutouts included. */
    val panel: Rect,
    /**
     * The title text's rect inside the title band, from the surface's content
     * margin to the explicit controls (side by side with it) or to the close
     * control.
     */
    val title: Rect,
    /**
     * The explicit controls: right-aligned in the title band beside the name, or
     * on their own row under it when the surface is too narrow to share.
     */
    val controls: RatingHistoryControlsGeometry,
    /**
     * The **plot area**: the whole region reserved for the chart, below the title
     * band and above the failure band. Nothing but the chart and its state text
     * is painted here.
     */
    val plot: Rect,
    /**
     * The failure band under the plot, present only while there is a failure to
     * name; null when the plot runs to the surface's content edge. Retry, when
     * offered, is inside it.
     */
    val footer: Rect?,
    /** The Retry control's touch target, inside [footer]; null with it. */
    val retry: Rect?,
    /** The close control's touch target. */
    val close: Rect,
)

/**
 * The chart's visible state. These are deliberately not rendered as the same
 * blank plot: `empty` is a complete history with no games, `empty_view` is a
 * loaded window with no events in it, `unavailable` is a window the server does
 * not cover, `pending` is publication lag, and `not_ready` is a history the
 * server is still preparing. The phase decides the *copy*; what the plot draws
 * beneath it is the retained coverage's answer (`paintsRetainedCoverage`,
 * [RatingChartSeries.hasCoveredTileBetween]). So `ready` paints candles,
 * `empty_view` paints the flat carry with its line over it, `loading` and a
 * drawable `error` paint whatever coverage is retained (an uncharted loading is
 * silent for the opening grace, then a skeleton), and the phases that mean
 * "the server has no answer for this window" paint their line alone.
 */
enum class RatingHistoryPhase {
    /**
     * No publication yet, or a held window has not arrived. The overlay paints
     * retained coverage if there is any; with nothing to paint it stays silent
     * until the opening grace has passed, then shows the skeleton.
     */
    Loading,

    /** The published history exists and holds no event at all. */
    Empty,

    /** The history exists but the visible window has no candle in it. */
    EmptyView,

    /** The window holds only non-covered tiles: missing history, not an empty one. */
    Unavailable,

    /** The server is still backfilling / preparing this account. */
    NotReady,

    /** A settlement is ahead of the published watermark (publication lag). */
    Pending,

    /** The last request failed; retained candles may still be painted. */
    Error,

    /** A normal chart. */
    Ready,
}

/** Which explicit controls this frame accepts. */
data class RatingHistoryControlsState(
    val zoomOutEnabled: Boolean = false,
    val zoomInEnabled: Boolean = false,
    val allTimeEnabled: Boolean = false,
    val retryEnabled: Boolean = false,
)

/**
 * What the popup names and the place it hangs: either one candle's numbers or
 * one flat carry's value and span.
 *
 * [anchorX]/[anchorY] are the world point the popup hangs from: the pointer on
 * a desktop, the tap on a phone. The popup sits beside that point, so the
 * reader's eye never travels from the cursor to a corner of the chart.
 * [offsetMinutes] is the device's UTC offset at the selection, or null when the
 * device cannot report one — the popup then reads UTC, the axis's own clock,
 * and says so rather than pretending to be local.
 */
sealed interface RatingHistoryPopup {
    /** The world point the popup hangs from: the pointer or the tap. */
    val anchorX: Float
    val anchorY: Float

    /** The device's UTC offset at the selection, or null when it cannot report one. */
    val offsetMinutes: Int?

    /** One candle: the numbers of a bucket the player pointed at or tapped. */
    data class Candle(
        val candle: RatingCandle,
        override val anchorX: Float,
        override val anchorY: Float,
        override val offsetMinutes: Int?,
    ) : RatingHistoryPopup

    /** One flat carry: the rating held across a stretch with no candle in it. */
    data class Carry(
        val carry: RatingCarry,
        override val anchorX: Float,
        override val anchorY: Float,
        override val offsetMinutes: Int?,
    ) : RatingHistoryPopup
}

/**
 * One frame of chart data the component hands the overlay: the route's target,
 * the window after this frame's gestures, the resolution actually being drawn, and
 * that resolution's cached series.
 *
 * [viewport] and [level] are null before any window can be resolved (the initial
 * all-time answer has not landed and the route has no stored range) or when only
 * an error/loading state has to be painted; the overlay then draws its state text
 * and footer chrome without a chart. [level] may differ from the window's density
 * target while a finer level is still arriving; the chart keeps drawing the
 * retained coarse level until the new one holds the window. [series] is null only
 * before any coverage exists. [phase] and [controls] are the component's
 * orchestration decision, so the overlay only formats and paints them.
 */
data class RatingChartRender(
    val target: RatingHistoryTarget,
    val viewport: ChartViewport?,
    val level: Int?,
    val series: RatingChartSeries?,
    val phase: RatingHistoryPhase,
    val publication: RatingHistoryPublication?,
    val failure: RatingHistoryFailure?,
    val controls: RatingHistoryControlsState,
    /**
     * The selection the popup names, if any: the candle or flat carry under the
     * pointer (a desktop hover), or the one the player tapped (a phone). Null
     * paints no popup.
     *
     * It is also the one thing the chart highlights, so the numbers and the ink
     * they are about cannot be two different selections.
     */
    val popup: RatingHistoryPopup? = null,
    /**
     * True once the opening wait has outlived its grace, so a chartless
     * [RatingHistoryPhase.Loading] may show its skeleton.
     *
     * False hides the loading state completely: a fast all-time answer must not
     * flash a sentence or a placeholder on its way to the chart. The component
     * owns the clock; the overlay only paints what this says.
     */
    val loadingVisible: Boolean = true,
)

/**
 * The rating-history screen: a **full-screen surface** — a plate edge to edge, no
 * scrim and no card margins — with a title band carrying the name, the explicit
 * zoom / all-time controls and the close control, the plot area the candlestick
 * chart fills, and — only while there is a failure to name — a footer band
 * carrying that line and, when retryable, Retry.
 *
 * A chart is read across time, so the scarce resource is pixels: the surface takes
 * the window and every piece of chrome is arranged around the plot rather than the
 * plot being fitted into a card. The plate itself runs under the display cutout;
 * the chrome lays out inside [SafeArea] with one design pad, so a camera hole or a
 * rounded corner never covers a control, a tick label, or the name.
 *
 * Geometry is a pure function of the viewport, the frame's failure and the safe
 * insets ([measure]), so [RatingHistoryComponent.layout] and [render] derive the
 * same rects without measuring text, and a narrow screen degrades by moving the
 * controls onto their own row and shrinking the plot, never by clipping them.
 *
 * The plot is painted by [RatingHistoryChartView] from the component's cached
 * series. This class owns only geometry, chrome, control paint and the view-local
 * chart animation; the window, resolution, state choice and network remain the
 * component's / E2's.
 */
class RatingHistoryOverlay : Disposable {

    private val closeButton = CloseButton()
    private val glyphLayout = GlyphLayout()

    /** Paint and view-local Y easing for the plot; it reads, it never decides. */
    private val chartView = RatingHistoryChartView()

    /**
     * The surface's own plate: the window's full-bleed background. Opaque, because
     * the layer is declared [inego.zungjunggame.ui.stack.Presentation.Opaque] and
     * nothing below it is modelled — a translucent tone here would show through to
     * nothing. The plot then carries the brighter plate its candles sit on.
     */
    private val surfaceInk = AppPalette.panel

    /** Ink for the plot's state text and the footer status. */
    private val placeholderInk = AppPalette.inkMuted

    /** Dimmer ink for a state's second line. */
    private val detailInk = AppPalette.inkQuiet

    /**
     * The opening skeleton: faint rules where the grid will be, and a quiet
     * caption under them. Kept below the state text's contrast so a skeleton is
     * never mistaken for the plotted history.
     */
    private val skeletonInk = AppPalette.tint(AppPalette.panelRim, 0.06f)
    private val skeletonCaptionInk = AppPalette.tint(AppPalette.inkMuted, 0.80f)

    /**
     * The plate a chart-backed state line is filled onto: the plot's own tone at a
     * higher alpha, so the copy reads over the grid and the carry line it names.
     */
    private val statePlateInk = AppPalette.tint(AppPalette.panelDeep, 0.94f)

    /** The popup's plate and rim: the card's own tones, one step brighter. */
    private val popupFill = AppPalette.panelDeep
    private val popupRim = AppPalette.panelRim

    /** The headline's rating ink: the accent the rest of the app gives a rating. */
    private val popupValueInk = AppPalette.accent

    /**
     * The quiet lines under the headline: the date and clock in the state text's
     * ink, a high or low dimmer still, so the context never competes with the
     * answer above it.
     */
    private val popupContextInk = AppPalette.inkMuted
    private val popupExtremeInk = AppPalette.inkQuiet

    private fun whitePixel(): Texture = WhitePixel.get()

    /** True while the Y axis is still easing, so the caller keeps frames coming. */
    val chartAnimating: Boolean get() = chartView.isAnimating

    /**
     * Start a new chart surface.
     *
     * This overlay is one shared view that outlives a route instance, so its
     * eased Y scale and last painted selection belong to the visit before. A new
     * component is a new surface: it must snap to its own first window instead
     * of animating from the scale the previous visit left.
     */
    fun resetChart() {
        chartView.reset()
    }

    /**
     * Advance the plot's view-local animation: the Y scale eases toward the range
     * the *settled* window shows. The caller passes the window the zoom is heading
     * to, never the frame the time-axis ease is showing, so two chained eases
     * cannot make the vertical scale trail the horizontal one.
     *
     * Called from the component's `motion`, never from `layout`, so the paint
     * phase only paints.
     */
    fun advanceChart(
        deltaSec: Float,
        series: RatingChartSeries?,
        viewport: ChartViewport?,
        target: RatingHistoryTarget?,
    ) {
        chartView.advance(deltaSec, series, viewport, target)
    }

    /**
     * Paint the surface for one frame. [target] is the route's own state, read by
     * the component; it selects whose history the surface says it shows. [chart]
     * is this frame's viewport, cached series and state; null paints the shell's
     * loading placeholder exactly as E1 did.
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera?,
        screenW: Float,
        screenH: Float,
        target: RatingHistoryTarget?,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        chart: RatingChartRender? = null,
    ) {
        Fonts.ensureCreated()
        val geometry = measure(screenW, screenH, footer = chart?.failure != null)
        val px = whitePixel()

        // The surface is the screen, edge to edge: no scrim and no floating card.
        // The layer below is not modelled at all (the route is presented Opaque),
        // so this plate is the only backdrop there is, and it must be opaque.
        batch.setColor(surfaceInk)
        batch.draw(
            px,
            geometry.panel.left, geometry.panel.bottom,
            geometry.panel.width, geometry.panel.height,
        )
        batch.setColor(Color.WHITE)

        val titleFont = Fonts.labelActive
        val title = RatingHistoryText.title(target, chart?.publication?.displayName)
        val titleScale = Fonts.fitScale(Fonts.textWidth(titleFont, title), geometry.title.width)
        Fonts.withFaceScale(titleFont, titleScale) {
            Fonts.drawText(
                batch, titleFont, title,
                geometry.title.left,
                geometry.title.centerY + Fonts.textHeight(titleFont, title) * 0.5f,
                titleFont.color,
            )
        }

        drawControls(batch, px, geometry, chart?.controls, pointerWorldX, pointerWorldY)
        drawFooter(batch, px, geometry, chart, pointerWorldX, pointerWorldY)
        val chartPainted = drawPlot(batch, px, geometry.plot, Fonts.label, chart, camera)
        drawPopup(batch, px, geometry.plot, chart, chartPainted)

        closeButton.layoutAt(geometry.close.left, geometry.close.bottom, geometry.close.width)
        closeButton.render(batch, px, pointerWorldX, pointerWorldY)
    }

    // ── Controls ────────────────────────────────────────────────────────────

    private fun drawControls(
        batch: SpriteBatch,
        px: Texture,
        geometry: RatingHistoryGeometry,
        controls: RatingHistoryControlsState?,
        pointerX: Float,
        pointerY: Float,
    ) {
        val state = controls ?: RatingHistoryControlsState()
        val row = geometry.controls
        drawControl(
            batch, px, row.zoomOut, "\u2212",
            enabled = state.zoomOutEnabled, selected = false,
            hovered = state.zoomOutEnabled && row.zoomOut.contains(pointerX, pointerY),
        )
        drawControl(
            batch, px, row.zoomIn, "+",
            enabled = state.zoomInEnabled, selected = false,
            hovered = state.zoomInEnabled && row.zoomIn.contains(pointerX, pointerY),
        )
        drawControl(
            batch, px, row.allTime, L10n.get("ratingHistory.control.all_time"),
            enabled = state.allTimeEnabled, selected = false,
            hovered = state.allTimeEnabled && row.allTime.contains(pointerX, pointerY),
        )
    }

    private fun drawControl(
        batch: SpriteBatch,
        px: Texture,
        rect: Rect,
        label: String,
        enabled: Boolean,
        selected: Boolean,
        hovered: Boolean,
    ) {
        val fill = when {
            !enabled -> AppPalette.plateMuted
            selected -> AppPalette.tint(AppPalette.plateHover, 0.98f)
            hovered -> AppPalette.tint(AppPalette.plateRaised, 0.98f)
            else -> AppPalette.tint(AppPalette.plateMuted, 0.96f)
        }
        val rim = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.22f)
            selected -> AppPalette.tint(AppPalette.accent, 0.90f)
            else -> AppPalette.rim
        }
        val ink = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.45f)
            selected -> AppPalette.inkStrong
            else -> AppPalette.inkSoft
        }
        OverlayChrome.drawButton(
            batch, px, Fonts.button, label,
            rect.left, rect.bottom, rect.width, rect.height,
            fill, rim, ink,
        )
    }

    // ── Footer: failure and Retry ───────────────────────────────────────────

    /**
     * The failure band under the plot. It is only reserved while there is a
     * failure to name ([RatingHistoryGeometry.footer]), so the line always has
     * content and a healthy surface never paints an empty strip: the plot itself
     * already carries the phase's copy, and this band says what went wrong
     * without depending on which phase won it.
     */
    private fun drawFooter(
        batch: SpriteBatch,
        px: Texture,
        geometry: RatingHistoryGeometry,
        chart: RatingChartRender?,
        pointerX: Float,
        pointerY: Float,
    ) {
        val footer = geometry.footer ?: return
        val status = chart?.failure?.let(RatingHistoryText::error) ?: return
        val retry = geometry.retry?.takeIf { chart.controls.retryEnabled }

        val font = Fonts.label
        val right = if (retry != null) {
            retry.left - Fonts.px(CONTROL_GAP_DP)
        } else {
            footer.right
        }
        val available = (right - footer.left).coerceAtLeast(1f)
        val scale = Fonts.fitScale(Fonts.textWidth(font, status), available)
        font.color = placeholderInk
        Fonts.withFaceScale(font, scale) {
            glyphLayout.setText(font, status)
            font.draw(
                batch,
                status,
                footer.left,
                footer.centerY + glyphLayout.height * 0.5f,
            )
        }
        font.color = Color.WHITE

        if (retry != null) {
            drawControl(
                batch, px, retry, L10n.get("ratingHistory.retry"),
                enabled = true, selected = false,
                hovered = retry.contains(pointerX, pointerY),
            )
        }
    }

    // ── Popup: a candle or a flat carry ─────────────────────────────────────

    /**
     * The popup over the plot: the numbers of one candle or the value and span
     * of one flat carry, in the player's own clock.
     *
     * **The answer is large; the context is quiet.** The headline is the rating
     * or the movement, in the accent the rest of the app gives a rating, with a
     * candle's signed change in a chip tinted by its direction. The date, the
     * clock and the extremes sit under it in the small, muted faces, so the one
     * thing the reader pointed at is the one thing the eye lands on.
     *
     * It hangs **beside** the point that named the selection — the pointer on a
     * desktop, the tap on a phone — so the reader never has to look away from
     * the cursor to find the numbers. It sits to the right of that point (and
     * flips to the left when the right does not fit), centred on it vertically,
     * and is clamped inside the plot so it never leaves the chart.
     *
     * Beside, never on top: the candle (or carry line) it names stands at the
     * anchor, and the reader must still see it — the chart paints that selection
     * highlighted (see [RatingHistoryChartView]), so the two are visibly one
     * thing.
     *
     * A phase whose own line is the plot has no candles to name, so the popup
     * stays away from it: it paints only over a chart that is really on screen.
     */
    private fun drawPopup(
        batch: SpriteBatch,
        px: Texture,
        plot: Rect,
        chart: RatingChartRender?,
        chartPainted: Boolean,
    ) {
        if (!chartPainted) return
        val popup = chart?.popup ?: return
        val content = when (popup) {
            is RatingHistoryPopup.Candle -> {
                val bucketMs = chart.series?.bucketMs ?: return
                RatingHistoryTooltip.content(popup.candle, bucketMs, popup.offsetMinutes)
            }

            is RatingHistoryPopup.Carry ->
                RatingHistoryTooltip.content(popup.carry, popup.offsetMinutes)
        }

        val headlineFont = Fonts.labelActive
        val lineFont = Fonts.small
        val changeFont = Fonts.button
        val pad = Fonts.px(POPUP_PAD_DP)
        val margin = Fonts.px(POPUP_MARGIN_DP)
        val gap = Fonts.px(POPUP_GAP_DP)
        val leading = Fonts.px(POPUP_LEADING_DP)
        val headlineGap = Fonts.px(POPUP_HEADLINE_GAP_DP)
        val chipPadX = Fonts.px(POPUP_CHIP_PAD_X_DP)
        val chipPadY = Fonts.px(POPUP_CHIP_PAD_Y_DP)
        val chipGap = Fonts.px(POPUP_CHIP_GAP_DP)

        val headline = content.headline
        val changeText = (headline as? RatingPopupHeadline.Movement)?.change
        val chipWidth = if (changeText != null) {
            Fonts.textWidth(changeFont, changeText) + chipPadX * 2f
        } else {
            0f
        }
        val ratingsWidth = when (headline) {
            is RatingPopupHeadline.Movement -> Fonts.textWidth(headlineFont, headline.ratings)
            is RatingPopupHeadline.Value -> Fonts.textWidth(headlineFont, headline.value)
        }
        val headlineWidth = ratingsWidth + if (changeText != null) chipGap + chipWidth else 0f

        // The headline row's box is the cap box, plus the chip's lower pad when
        // there is one: the chip hangs its padding below the baseline, and the
        // next line must start under it. The quiet lines stack by the small
        // face's own line height, the same rule the old all-small stack used.
        val headlineBox = headlineFont.capHeight + if (changeText != null) chipPadY else 0f
        val metaAdvance = lineFont.lineHeight + leading
        val width = max(
            headlineWidth,
            content.lines.maxOfOrNull { Fonts.textWidth(lineFont, it.text) } ?: 0f,
        ) + pad * 2f
        val height = if (content.lines.isEmpty()) {
            pad * 2f + headlineBox
        } else {
            pad * 2f + headlineBox + headlineGap +
                metaAdvance * (content.lines.size - 1) + lineFont.capHeight
        }

        val minLeft = plot.left + margin
        val maxLeft = max(minLeft, plot.right - width - margin)
        val rightSide = popup.anchorX + gap
        val left = if (rightSide + width <= plot.right - margin) {
            rightSide
        } else {
            popup.anchorX - gap - width
        }.coerceIn(minLeft, maxLeft)

        // A tap may land on the axis band; the popup then hangs from the candle
        // area, where the candle it names stands.
        val area = chartArea
        val anchorY = if (area != null) {
            popup.anchorY.coerceIn(area.bottom, area.top)
        } else {
            popup.anchorY
        }
        val minBottom = plot.bottom + margin
        val maxBottom = max(minBottom, plot.top - margin - height)
        val bottom = (anchorY - height * 0.5f).coerceIn(minBottom, maxBottom)
        val top = bottom + height

        OverlayChrome.drawPanel(batch, px, left, bottom, width, height, popupFill, popupRim)
        var lineTop = top - pad
        when (headline) {
            is RatingPopupHeadline.Movement -> {
                Fonts.drawText(batch, headlineFont, headline.ratings, left + pad, lineTop, popupValueInk)
                val baseline = lineTop - headlineFont.capHeight
                val chipLeft = left + pad + ratingsWidth + chipGap
                val chipBottom = baseline - chipPadY
                val chipInk = when (headline.movement) {
                    RatingMovement.Up -> AppPalette.success
                    RatingMovement.Down -> AppPalette.danger
                    RatingMovement.Flat -> AppPalette.inkMuted
                }
                OverlayChrome.drawPanel(
                    batch, px,
                    chipLeft, chipBottom, chipWidth, changeFont.capHeight + chipPadY * 2f,
                    Color(chipInk.r, chipInk.g, chipInk.b, 0.16f),
                    Color(chipInk.r, chipInk.g, chipInk.b, 0.50f),
                    Fonts.px(1f),
                )
                Fonts.drawText(
                    batch, changeFont, headline.change,
                    chipLeft + chipPadX, baseline + changeFont.capHeight,
                    chipInk,
                )
            }

            is RatingPopupHeadline.Value ->
                Fonts.drawText(batch, headlineFont, headline.value, left + pad, lineTop, popupValueInk)
        }
        lineTop -= headlineBox + headlineGap
        for (line in content.lines) {
            Fonts.drawText(batch, lineFont, line.text, left + pad, lineTop, popupInk(line.tone))
            lineTop -= metaAdvance
        }
    }

    /** The quiet line's ink: context first, an extreme one step dimmer. */
    private fun popupInk(tone: RatingPopupLineTone): Color = when (tone) {
        RatingPopupLineTone.Context -> popupContextInk
        RatingPopupLineTone.Extreme -> popupExtremeInk
    }

    // ── Plot and its state text ─────────────────────────────────────────────

    /**
     * The candle area of the last painted chart, or null before the first paint.
     *
     * The hit test reads it, so a pointer names the candle the paint put there
     * rather than a candle somewhere else in the plot; the gesture anchor reads it
     * for the same reason, so a zoom holds the instant under the pointer.
     */
    internal val chartArea: RatingChartArea? get() = chartView.chartArea

    /**
     * The band [candle]'s range occupies in the last painted frame, or null when
     * there is none to stand on.
     *
     * The hit test reads it so the pointer and the ink cannot disagree about
     * where a candle is.
     */
    internal fun candleBand(candle: RatingCandle): ClosedFloatingPointRange<Float>? =
        chartView.candleBand(candle)

    /**
     * The flat carry at ([x], [y]), or null when the pointer is not on one.
     *
     * The answer is the last paint's, exactly as [chartArea] and [candleBand]
     * are: the line the reader points at and the line the popup names are one
     * ink.
     */
    internal fun carryAt(x: Float, y: Float): RatingCarry? = chartView.carryAt(x, y)

    /**
     * True while a phase may be painted **over** retained coverage. The phases
     * that mean the server has no answer for the visible window — a complete
     * history with no games, a backfill still being prepared, publication lag, and
     * a window of only non-covered tiles — answer the whole plot themselves, so
     * their copy is never drawn over a chart that says something else.
     */
    private fun RatingHistoryPhase.paintsRetainedCoverage(): Boolean = when (this) {
        RatingHistoryPhase.Ready,
        RatingHistoryPhase.EmptyView,
        RatingHistoryPhase.Loading,
        RatingHistoryPhase.Error,
        -> true

        RatingHistoryPhase.Empty,
        RatingHistoryPhase.Unavailable,
        RatingHistoryPhase.NotReady,
        RatingHistoryPhase.Pending,
        -> false
    }

    /**
     * The reserved plot area: the chart whenever the window holds something to map
     * — candles, or the flat carry of a covered interval with no event in it — and
     * the state text for what the chart cannot say.
     *
     * The chart reports whether it painted; this method decides whether the phase's
     * own line is painted over the result. A phase whose copy answers for the whole
     * window never paints the chart at all ([paintsRetainedCoverage]), so its line
     * stands alone. Of the phases that do paint, [RatingHistoryPhase.EmptyView] is
     * the one whose line must survive its chart: the line names the empty span the
     * flat carry is drawn across. `Ready`, and a charted `Loading`/`Error`, leave
     * the plot to the chart; an uncharted `Loading` is quiet for the opening grace,
     * then paints a skeleton instead of a centered sentence.
     *
     * Returns true when the chart is on screen, so the popup knows there are
     * candles under it to name.
     */
    private fun drawPlot(
        batch: SpriteBatch,
        px: Texture,
        plot: Rect,
        statusFont: BitmapFont,
        chart: RatingChartRender?,
        camera: Camera?,
    ): Boolean {
        OverlayChrome.drawPanel(
            batch, px,
            plot.left, plot.bottom, plot.width, plot.height,
            AppPalette.tint(AppPalette.panelDeep, 0.85f),
            AppPalette.tint(AppPalette.panelRim, 0.30f),
        )

        val phase = chart?.phase ?: RatingHistoryPhase.Loading
        // Preserve E3's partial chart: retained covered tiles are painted while the
        // rest of the window is still loading, or while a failed refresh leaves them
        // on screen. A window with no covered tile at all paints no chart, and the
        // phase's own line is the plot.
        val painted = chart != null &&
            camera != null &&
            phase.paintsRetainedCoverage() &&
            chartView.render(
                batch, camera, plot, chart.series, chart.viewport, chart.target,
                // The selection the popup names is the one the chart highlights:
                // one selection, two painters.
                highlight = chart.popup,
            )
        if (painted && phase != RatingHistoryPhase.EmptyView) return painted

        if (phase == RatingHistoryPhase.Loading) {
            // A fast answer never shows anything: the grace hides both the line
            // and the skeleton. A slow one gets the chart's own frame, not a
            // sentence in the middle of it.
            if (chart?.loadingVisible != false) {
                drawLoadingSkeleton(batch, px, plot, statusFont)
            }
            return painted
        }

        val (headline, detail) = stateText(phase, chart)
        if (headline != null) {
            drawStateText(batch, px, plot, statusFont, headline, detail, backdrop = painted)
        }
        return painted
    }

    /**
     * The opening load's placeholder: the chart's candle area as a faint grid,
     * with the loading line as a quiet caption in the time gutter.
     *
     * It deliberately keeps the real plot's geometry ([ratingChartArea]) so the
     * skeleton and the first painted candles occupy the same rectangle; a static
     * grid does not ask for frames, and the caption is small enough that a wait
     * crossing the grace does not read as a layout change.
     */
    private fun drawLoadingSkeleton(
        batch: SpriteBatch,
        px: Texture,
        plot: Rect,
        font: BitmapFont,
    ) {
        val area = ratingChartArea(plot, font)
        val thickness = max(1f, Fonts.px(1f))
        batch.setColor(skeletonInk)
        for (i in 1..3) {
            val y = area.bottom + area.height * i / 4f
            batch.draw(px, area.left, y - thickness * 0.5f, area.width, thickness)
        }
        for (i in 1..4) {
            val x = area.left + area.width * i / 5f
            batch.draw(px, x - thickness * 0.5f, area.bottom, thickness, area.height)
        }
        batch.setColor(Color.WHITE)

        val caption = L10n.get("ratingHistory.loading")
        val captionWidth = Fonts.textWidth(font, caption)
        Fonts.drawText(
            batch, font, caption,
            plot.left + (plot.width - captionWidth) * 0.5f,
            plot.bottom + Fonts.px(8f) + font.capHeight,
            skeletonCaptionInk,
        )
    }

    /**
     * The headline and optional second line for [phase]. [Ready] and [Loading]
     * have none because their plot is the chart or the skeleton.
     */
    private fun stateText(
        phase: RatingHistoryPhase,
        chart: RatingChartRender?,
    ): Pair<String?, String?> = when (phase) {
        // Loading is the skeleton's job (drawLoadingSkeleton); it never takes the
        // centered state copy, so a fast answer cannot flash a sentence.
        RatingHistoryPhase.Loading -> null to null

        RatingHistoryPhase.Empty ->
            L10n.get("ratingHistory.empty") to null

        RatingHistoryPhase.EmptyView ->
            L10n.get("ratingHistory.empty_view") to null

        RatingHistoryPhase.Unavailable ->
            L10n.get("ratingHistory.unavailable_view") to null

        RatingHistoryPhase.NotReady ->
            L10n.get("ratingHistory.not_ready") to L10n.get("ratingHistory.not_ready_detail")

        RatingHistoryPhase.Pending ->
            L10n.get("ratingHistory.pending") to L10n.get("ratingHistory.pending_detail")

        RatingHistoryPhase.Error -> {
            val failure = chart?.failure
            val text = if (failure != null) {
                RatingHistoryText.error(failure)
            } else {
                L10n.get("ratingHistory.error")
            }
            val detail = if (chart?.controls?.retryEnabled == true) {
                L10n.get("ratingHistory.error.detail")
            } else {
                null
            }
            text to detail
        }

        RatingHistoryPhase.Ready -> null to null
    }

    /**
     * The state copy, centred in the plot. [backdrop] fills a plate behind it, for
     * the one phase whose line is drawn over a chart: without it the flat carry
     * would run straight through the middle of the sentence it explains.
     */
    private fun drawStateText(
        batch: SpriteBatch,
        px: Texture,
        plot: Rect,
        headlineFont: BitmapFont,
        headline: String,
        detail: String?,
        backdrop: Boolean,
    ) {
        val width = (plot.width - Fonts.px(STATE_PAD_DP) * 2f).coerceAtLeast(1f)
        glyphLayout.setText(headlineFont, headline, placeholderInk, width, Align.center, true)
        val headlineHeight = glyphLayout.height
        val headlineWidth = glyphLayout.width
        val detailFont = Fonts.small
        var detailHeight = 0f
        var detailWidth = 0f
        if (detail != null) {
            glyphLayout.setText(detailFont, detail, detailInk, width, Align.center, true)
            detailHeight = glyphLayout.height + Fonts.px(STATE_LINE_GAP_DP)
            detailWidth = glyphLayout.width
        }
        val total = headlineHeight + detailHeight
        val top = plot.centerY + total * 0.5f

        if (backdrop) {
            val pad = Fonts.px(STATE_PLATE_PAD_DP)
            // A wrapped run that cannot break (a long word) may exceed the wrap
            // width; the plate still stays inside the plot.
            val plateWidth = (max(headlineWidth, detailWidth) + pad * 2f)
                .coerceAtMost(plot.width)
            val plateHeight = total + pad * 2f
            OverlayChrome.drawPanel(
                batch, px,
                plot.centerX - plateWidth * 0.5f,
                top - total - pad,
                plateWidth,
                plateHeight,
                statePlateInk,
                AppPalette.tint(AppPalette.panelRim, 0.12f),
                Fonts.px(1f),
            )
        }

        headlineFont.color = placeholderInk
        headlineFont.draw(batch, headline, plot.left, top, width, Align.center, true)
        if (detail != null) {
            detailFont.color = detailInk
            detailFont.draw(
                batch,
                detail,
                plot.left,
                top - headlineHeight - Fonts.px(STATE_LINE_GAP_DP),
                width,
                Align.center,
                true,
            )
            detailFont.color = Color.WHITE
        }
        headlineFont.color = Color.WHITE
    }

    override fun dispose() {
    }

    companion object {
        /** The close control's side and the air between two stacked bands. */
        private const val CLOSE_SIDE_DP = 36f
        private const val GAP_DP = 10f

        /** The explicit control row and footer bands. */
        private const val CONTROL_HEIGHT_DP = 36f
        private const val CONTROL_GAP_DP = 8f
        private const val ALL_TIME_WIDTH_DP = 88f
        private const val FOOTER_HEIGHT_DP = 30f
        private const val RETRY_WIDTH_DP = 84f

        /**
         * The narrowest title the controls may share a band with. Below it the
         * name would be reduced to a fit-scaled sliver, so the surface gives the
         * controls their own row instead.
         */
        private const val MIN_TITLE_DP = 120f

        /** Air between the plot's rim and its state text. */
        private const val STATE_PAD_DP = 12f
        private const val STATE_LINE_GAP_DP = 4f

        /** Air between the state text and the plate behind it. */
        private const val STATE_PLATE_PAD_DP = 8f

        /** Air inside the popup's plate, and between the popup and the plot's edge. */
        private const val POPUP_PAD_DP = 8f
        private const val POPUP_MARGIN_DP = 6f

        /**
         * The air between the anchor (the pointer or the tap) and the popup's
         * plate, so the pointer never sits under the numbers.
         */
        private const val POPUP_GAP_DP = 14f

        /** The air between two lines of the popup's text. */
        private const val POPUP_LEADING_DP = 2f

        /**
         * The air between the headline's cap box and the first quiet line. The
         * headline is nearly twice the small face's size, so it gets its own
         * breath rather than the line-to-line leading.
         */
        private const val POPUP_HEADLINE_GAP_DP = 8f

        /**
         * The movement chip: air inside its plate, and the gap between it and the
         * two ratings it qualifies.
         */
        private const val POPUP_CHIP_PAD_X_DP = 5f
        private const val POPUP_CHIP_PAD_Y_DP = 2f
        private const val POPUP_CHIP_GAP_DP = 8f

        /**
         * Pure screen geometry: the surface fills the window, and its chrome lays
         * out inside the display's safe area with one design pad of air. The title
         * band is the surface's pinned top row, and the plot takes everything
         * below that band down to the content edge — unless [footer] asks for the
         * failure band, which is then reserved under the plot. No fonts and no GL,
         * so `layout()` and `draw()` derive the same rects in the same order — the
         * close button is placed from the surface's own pad rather than from
         * [CloseButton]'s live metrics, which need fonts.
         *
         * **The plate bleeds; the chrome does not.** A chart is read across time,
         * so the surface takes the whole window — but a cutout, a rounded corner
         * or a camera hole must never sit on the close control, a tick label or
         * the name, so [safe] is subtracted before the pad is applied. `[safe]`
         * defaults to the window's insets ([SafeArea.current]); every caller in a
         * frame therefore measures the same surface without threading a snapshot
         * through five call sites, and hit-testing cannot drift from paint. Those
         * insets change only on rotation or a cutout change, so a stale one is a
         * single frame of chrome, never a mis-placed control.
         *
         * The controls sit in the title band beside the name whenever the name
         * still keeps a legible width ([MIN_TITLE_DP]); a narrower surface drops
         * them to their own row under it rather than squeezing the title. Either
         * way they are above the plot, never inside it.
         *
         * [footer] is true only while the frame has a failure to name, so an
         * otherwise healthy surface never reserves a strip it would paint nothing
         * in.
         */
        fun measure(
            screenW: Float,
            screenH: Float,
            footer: Boolean = false,
            safe: SafeArea.Insets = SafeArea.current(),
        ): RatingHistoryGeometry {
            val portrait = screenH > screenW
            val pad = Fonts.px(if (portrait) 16f else 22f)
            val gap = Fonts.px(GAP_DP)
            val panel = Rect(
                left = 0f,
                bottom = 0f,
                width = screenW.coerceAtLeast(1f),
                height = screenH.coerceAtLeast(1f),
            )
            val contentLeft = panel.left + safe.left + pad
            val contentRight = panel.right - safe.right - pad
            val contentTop = panel.top - safe.top
            val contentBottom = panel.bottom + safe.bottom + pad
            val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)

            // One band holds the close control and, when the surface is wide
            // enough, the three explicit controls and the name as well.
            val closeSide = Fonts.px(CLOSE_SIDE_DP)
            val controlsHeight = Fonts.px(CONTROL_HEIGHT_DP)
            val bandHeight = max(closeSide, controlsHeight)
            val bandBottom = contentTop - pad - bandHeight
            val close = Rect(
                left = contentRight - closeSide,
                bottom = bandBottom,
                width = closeSide,
                height = closeSide,
            )
            val controlsRight = close.left - gap
            val inlineTitleWidth =
                controlsRight - contentLeft - naturalControlsWidth(controlsHeight) - gap
            val inline = inlineTitleWidth >= Fonts.px(MIN_TITLE_DP)
            // The lowest row above the plot: the title band itself when the
            // controls share it, their own row under it when they do not.
            val rowsBottom = if (inline) bandBottom else bandBottom - gap - controlsHeight
            val controls = if (inline) {
                inlineControls(controlsRight, bandBottom, controlsHeight)
            } else {
                controlsGeometry(contentLeft, rowsBottom, contentWidth, controlsHeight)
            }
            val title = Rect(
                left = contentLeft,
                bottom = bandBottom,
                width = if (inline) {
                    inlineTitleWidth.coerceAtLeast(1f)
                } else {
                    (close.left - gap - contentLeft).coerceAtLeast(1f)
                },
                height = bandHeight,
            )

            val footerHeight = if (footer) Fonts.px(FOOTER_HEIGHT_DP) else 0f
            val footerGap = if (footer) gap else 0f
            val footerRect = if (footer) {
                Rect(
                    left = contentLeft,
                    bottom = contentBottom,
                    width = contentWidth,
                    height = footerHeight,
                )
            } else {
                null
            }
            val retryWidth = Fonts.px(RETRY_WIDTH_DP)
            val retry = footerRect?.let {
                Rect(
                    left = it.right - retryWidth,
                    bottom = it.bottom,
                    width = retryWidth,
                    height = it.height,
                )
            }
            val plotBottom = contentBottom + footerHeight + footerGap
            val plot = Rect(
                left = contentLeft,
                bottom = plotBottom,
                width = contentWidth,
                height = (rowsBottom - gap - plotBottom).coerceAtLeast(1f),
            )
            return RatingHistoryGeometry(
                panel = panel,
                title = title,
                controls = controls,
                plot = plot,
                footer = footerRect,
                retry = retry,
                close = close,
            )
        }

        /** The width the three controls keep when they are laid out at [height]. */
        private fun naturalControlsWidth(height: Float): Float {
            val gap = Fonts.px(CONTROL_GAP_DP)
            return height * 2f + Fonts.px(ALL_TIME_WIDTH_DP) + gap * 2f
        }

        /**
         * The title row's three controls, right-aligned at [right] — the air
         * before the close control — and at their natural width: the inline row
         * exists only when that width plus the title's budget fits.
         */
        private fun inlineControls(
            right: Float,
            bottom: Float,
            height: Float,
        ): RatingHistoryControlsGeometry {
            val gap = Fonts.px(CONTROL_GAP_DP)
            val allTimeWidth = Fonts.px(ALL_TIME_WIDTH_DP)
            var x = right - naturalControlsWidth(height)
            val zoomOut = Rect(x, bottom, height, height)
            x += height + gap
            val zoomIn = Rect(x, bottom, height, height)
            x += height + gap
            val allTime = Rect(x, bottom, allTimeWidth, height)
            return RatingHistoryControlsGeometry(
                zoomOut = zoomOut,
                zoomIn = zoomIn,
                allTime = allTime,
            )
        }

        /**
         * Three controls sized to their labels and centered as a row of their own,
         * for a surface too narrow to share the title band. Each is measured in
         * design units, not from the current locale's text: the row keeps one
         * width while the copy changes, and [OverlayChrome.drawButton] fits a
         * longer translation inside its plate. A narrow surface shrinks the whole
         * row to fit; the zoom squares are never widened past their side.
         */
        private fun controlsGeometry(
            left: Float,
            bottom: Float,
            width: Float,
            height: Float,
        ): RatingHistoryControlsGeometry {
            val gap = Fonts.px(CONTROL_GAP_DP)
            val natural = naturalControlsWidth(height)
            val scale = if (natural > width) width / natural else 1f
            val zoomWidth = height * scale
            val allTimeWidth = Fonts.px(ALL_TIME_WIDTH_DP) * scale
            val scaledGap = gap * scale
            val total = zoomWidth * 2f + allTimeWidth + scaledGap * 2f
            var x = left + (width - total) * 0.5f
            val zoomOut = Rect(x, bottom, zoomWidth, height)
            x += zoomWidth + scaledGap
            val zoomIn = Rect(x, bottom, zoomWidth, height)
            x += zoomWidth + scaledGap
            val allTime = Rect(x, bottom, allTimeWidth, height)
            return RatingHistoryControlsGeometry(
                zoomOut = zoomOut,
                zoomIn = zoomIn,
                allTime = allTime,
            )
        }
    }
}
