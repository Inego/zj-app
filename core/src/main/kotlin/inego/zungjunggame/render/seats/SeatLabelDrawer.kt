package inego.zungjunggame.render.seats

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.render.ui.TextFit
import kotlin.math.max
import kotlin.math.min

/**
 * Seat-wind labels as plain text (never a tile graphic): a **large Han wind
 * character** (東南西北, see [PlayerLabels.windHan]) beside name · score,
 * rotated for the human (bottom) viewer — opposite stays upright, left reads
 * bottom-to-top, right reads top-to-bottom.
 *
 * **One size on every seat and every turn.** The active seat is marked by its
 * ink (white against the resting seat ink) and by the board's felt panel under
 * that seat — never by size, never by animation: a seat is *always* active, so
 * a label that pulsed or grew kept the whole table asking for frames forever,
 * and it made the row geometry move when the turn changed.
 *
 * The localized wind word (East, Юг, …) is not drawn. When tile tags are on,
 * the short symbol those tiles use sits beside the character, unless that
 * symbol is the character itself (ja and zh both 東).
 */
class SeatLabelDrawer {
    private val glyphLayout = GlyphLayout()
    private val textTransform = Matrix4()
    private val textTransformPrev = Matrix4()

    /** Scratch for [PlayerLabels.botNameTint]; one label per seat, drawn in turn. */
    private val botNameTint = Color()

    /**
     * An above-row placement: the label floats over the felt just above the
     * seat's own hand row, instead of sitting in the gutter between that row and
     * the screen edge.
     *
     * The human uses this when the bottom gutter is spent on taller tiles. The
     * label is upright — the human reads it from the near edge — right-aligned to
     * [rightX], which the board measures from the row's rightmost tile or slot
     * (the last meld tile when melds are open, or the parked draw slot), so the
     * label travels with the row as claims widen it or the drawn tile arrives.
     * Its box floats just above [rowEdgeY], the row's felt-facing edge, so the
     * ink keeps daylight from the tiles, never crosses them, and never crops at
     * the screen edge below the row.
     */
    data class AboveRow(
        /** World x the label's right edge sits on: the row's rightmost tile/slot edge. */
        val rightX: Float,
        /** World y of the hand row's felt-facing (inner) edge. */
        val rowEdgeY: Float,
        /** Width budget for the run: how far left of the band edge it may paint. */
        val maxWidth: Float,
    )

    /**
     * [active] is [inego.zungjunggame.session.table.TableViewState.activeSeat] — the seat whose turn
     * it is. That is deliberately *not* the engine's `currentPlayer` while other seats are
     * deciding on a claim: the discarder keeps the emphasis until the claim resolves.
     */
    fun draw(
        batch: SpriteBatch,
        windTile: Int,
        active: Boolean,
        seat: Int,
        side: SeatSide,
        screenW: Float,
        screenH: Float,
        edgeInset: Float,
        /** World position of the seat's row centre along its edge (the felt's centre). */
        alongCenter: Float,
        handH: Float,
        tileGap: Float,
        seatScores: IntArray,
        humanSeat: Int,
        safeInset: Float = 0f,
        /**
         * Reserved band at the bottom edge that the human label must clear
         * (the tutorial coach's Back / Next row, plus the rules-credit strip in
         * portrait). Ignored by the other seats.
         */
        bottomReserve: Float = 0f,
        /**
         * When set, the label floats above the seat's own row instead of sitting
         * in the gutter. [SeatSide.Bottom] only; the other seats keep their
         * gutters.
         */
        aboveRow: AboveRow? = null,
    ) {
        if (windTile < 0) return

        val windFont = Fonts.label
        val ink = if (active) ACTIVE_INK else windFont.color
        val playerName = PlayerLabels.nameForAbsoluteSeat(seat, humanSeat)
        val score = seatScores.getOrElse(seat) { 0 }
        val measured = measureRuns(
            windFont = windFont,
            ink = ink,
            windTile = windTile,
            windName = PlayerLabels.windName(windTile),
            playerName = playerName,
            score = score,
            isBot = PlayerLabels.isBot(seat),
        )
        val bScale = baseLabelScale(handH)
        val above = aboveRow
        if (above != null) {
            drawAboveRow(
                batch = batch,
                windFont = windFont,
                windTile = windTile,
                seat = seat,
                score = score,
                measured = measured,
                bScale = bScale,
                above = above,
            )
            return
        }
        // The bottom label fits the same gutter as every other seat. Its width
        // budget is still the screen span, so a stored long name cannot paint
        // off the edge; the budget is the width it occupies after the same
        // scale it is drawn at.
        val safeEdge = safeInset.coerceAtLeast(0f)
        val outerEdge =
            if (side == SeatSide.Bottom) {
                safeEdge + bottomReserve.coerceAtLeast(0f)
            } else {
                safeEdge
            }
        val handFace = (edgeInset - handH * 0.5f).coerceAtLeast(outerEdge)
        val gutter = handFace - outerEdge
        val fitScale = gutterFitScale(
            boxH = measured.boxH,
            baseScale = bScale,
            gutter = gutter,
            tileGap = tileGap,
        )
        val runs = if (side == SeatSide.Bottom) {
            val span = (screenW - 2f * safeEdge).coerceAtLeast(1f)
            val paintedScale = bScale * fitScale
            val unscaledSpan = if (paintedScale > 0f) span / paintedScale else span
            if (measured.totalW > unscaledSpan && measured.name.isNotEmpty()) {
                val budget = (unscaledSpan - (measured.totalW - measured.nameW)).coerceAtLeast(0f)
                val fitted = TextFit.ellipsize(windFont, measured.name, budget)
                if (fitted != measured.name) {
                    measureRuns(
                        windFont = windFont,
                        ink = ink,
                        windTile = windTile,
                        windName = PlayerLabels.windName(windTile),
                        playerName = fitted,
                        score = score,
                        isBot = PlayerLabels.isBot(seat),
                    )
                } else {
                    measured
                }
            } else {
                measured
            }
        } else {
            measured
        }

        // The anchor uses the scale the glyphs are painted at, so the label stays
        // inside its box and clear of the tiles.
        val drawnScale = bScale * fitScale
        val drawnBoxH = runs.boxH * drawnScale

        val lx = 0f
        val ly = labelLocalY(
            side,
            handH,
            tileGap,
            drawnBoxH,
            edgeInset,
            safeInset,
            bottomReserve,
        )
        val (wx, wy) = side.localToWorld(lx, ly, screenW, screenH, edgeInset, alongCenter)
        drawCenteredRotatedLabel(
            batch = batch,
            runs = runs,
            cx = wx,
            cy = wy,
            rotationDeg = side.labelRotationDeg,
            scale = drawnScale,
        )
    }

    /**
     * Paints the label above the seat's own row: upright, right-aligned to
     * [AboveRow.rightX], its box floating just above the row's felt-facing edge.
     *
     * This is where the human label lives once the bottom gutter has been spent
     * on taller tiles, so the label stays inside the screen instead of cropping
     * at the bottom edge.
     *
     * It draws at [ABOVE_ROW_SCALE] of the resting size: the band between the row
     * and the river is shallow, and at full size the Han glyph would crowd the
     * tiles. The **box** still floats at the resting size, [ABOVE_ROW_LIFT] above
     * the row, so the trimmed ink keeps a visible gap from the tiles instead of
     * only the few pixels the trim alone leaves.
     *
     * The favourite case is a label at its full above-row size with room to
     * spare. Where the felt does not give that room, the whole run shrinks a
     * little (down to [MIN_ABOVE_ROW_SCALE] of that size) and only then does the
     * name give way, so the wind character and the score stay intact.
     */
    private fun drawAboveRow(
        batch: SpriteBatch,
        windFont: BitmapFont,
        windTile: Int,
        seat: Int,
        score: Int,
        measured: LabelRuns,
        bScale: Float,
        above: AboveRow,
    ) {
        val base = bScale * ABOVE_ROW_SCALE
        val available = above.maxWidth.coerceAtLeast(1f)
        val scale = if (measured.totalW * base > available) {
            (available / measured.totalW).coerceIn(base * MIN_ABOVE_ROW_SCALE, base)
        } else {
            base
        }
        var runs = measured
        if (runs.totalW * scale > available && runs.name.isNotEmpty()) {
            val budget = available / scale - (runs.totalW - runs.nameW)
            val fitted = TextFit.ellipsize(windFont, runs.name, budget.coerceAtLeast(0f))
            if (fitted != runs.name) {
                runs = measureRuns(
                    windFont = windFont,
                    ink = runs.ink,
                    windTile = windTile,
                    windName = PlayerLabels.windName(windTile),
                    playerName = fitted,
                    score = score,
                    isBot = PlayerLabels.isBot(seat),
                )
            }
        }
        // Float the resting-size box a little above the row's felt-facing edge
        // and centre the trimmed ink in it: the label belongs to the row, but its
        // ink must keep clear of the tiles below it.
        val bandH = boxHeight(Fonts.label) * bScale
        drawCenteredRotatedLabel(
            batch = batch,
            runs = runs,
            cx = above.rightX - runs.totalW * scale * 0.5f,
            cy = above.rowEdgeY + bandH * (0.5f + ABOVE_ROW_LIFT),
            rotationDeg = 0f,
            scale = scale,
        )
    }

    /**
     * Measures one seat label: the large Han wind glyph (when a CJK face is
     * loaded), then the optional tile-tag symbol, then name · score.
     */
    private fun measureRuns(
        windFont: BitmapFont,
        ink: Color,
        windTile: Int,
        windName: String,
        playerName: String,
        score: Int,
        isBot: Boolean,
    ): LabelRuns {
        Fonts.ensureFallback()
        // Without a CJK face the character would be tofu.
        val han = if (Fonts.cjkCapable) PlayerLabels.windHan(windTile) else ""
        // Tile tags add the short honor symbol (E, В, …). Skip it when it is the
        // character already drawn large. With no face and tags off, the full wind
        // name still names the seat.
        val tag = TileLabels.tagOrNull(windTile).orEmpty()
        val beside = when {
            tag.isNotEmpty() && tag != han -> tag
            han.isEmpty() -> windName
            else -> ""
        }
        // Same " · " as the score. The extra gap below is only for a word or
        // tag sitting between the character and that dot; a bare dot uses the
        // separator's own space, or it floats off the character.
        val prefix = when {
            beside.isNotEmpty() -> "$beside · "
            han.isNotEmpty() -> " · "
            else -> ""
        }
        val suffix = if (score > 0) " · +$score" else " · $score"

        glyphLayout.setText(windFont, "$prefix$playerName$suffix")
        val nameH = glyphLayout.height

        val hanFont = Fonts.display
        val hanScale =
            hanScaleFor(nameH, if (han.isEmpty()) 0f else Fonts.textHeight(hanFont, han))
        // Measure the glyph at the size it will be painted at.
        val (hanW, hanH) = if (han.isEmpty()) {
            0f to 0f
        } else {
            Fonts.withFaceScale(hanFont, hanScale) {
                Fonts.textWidth(hanFont, han) to Fonts.textHeight(hanFont, han)
            }
        }
        return LabelRuns(
            font = windFont,
            ink = ink,
            hanFont = hanFont,
            han = han,
            hanScale = hanScale,
            hanW = hanW,
            hanH = hanH,
            hanGap = if (han.isNotEmpty() && beside.isNotEmpty()) Fonts.px(HAN_GAP_PX) else 0f,
            prefix = prefix,
            prefixW = Fonts.textWidth(windFont, prefix),
            name = playerName,
            nameW = Fonts.textWidth(windFont, playerName),
            isBot = isBot,
            suffix = suffix,
            suffixW = Fonts.textWidth(windFont, suffix),
            nameH = nameH,
        )
    }

    /** One seat label, measured in draw order: han glyph, then wind · name · score. */
    private class LabelRuns(
        /** Face for the wind / player / score runs. */
        val font: BitmapFont,
        /** Ink for every run; the active seat's is [ACTIVE_INK]. */
        val ink: Color,
        /** Large Han face — never upscaled; [hanScale] shrinks it to [hanH]. */
        val hanFont: BitmapFont,
        /** Han wind character, "" when the glyph is skipped. */
        val han: String,
        val hanScale: Float,
        val hanW: Float,
        val hanH: Float,
        /** Air between the Han glyph and a following tag or wind word. 0 before a bare dot. */
        val hanGap: Float,
        /** " · ", "<tag> · ", or the wind word when the glyph is skipped. */
        val prefix: String,
        val prefixW: Float,
        val name: String,
        val nameW: Float,
        val isBot: Boolean,
        /** " · <signed score>". */
        val suffix: String,
        val suffixW: Float,
        /** Line height of the wind / name / score runs. */
        val nameH: Float,
    ) {
        val totalW: Float get() = hanW + hanGap + prefixW + nameW + suffixW

        /** Box to centre on the seat: the Han glyph overhangs the smaller text row. */
        val boxH: Float get() = max(nameH, hanH)
    }

    /**
     * Draws [runs] with its box centered on ([cx], [cy]), rotated
     * counter-clockwise by [rotationDeg] ([SeatSide.labelRotationDeg]).
     * Optional [scale] is applied about the center.
     * The Han glyph takes the wind-name tint, so only its size sets it apart;
     * a bot's name is the same colour as anyone else's, dimmed to
     * [PlayerLabels.BOT_NAME_ALPHA] — see [PlayerLabels.botNameTint].
     */
    private fun drawCenteredRotatedLabel(
        batch: SpriteBatch,
        runs: LabelRuns,
        cx: Float,
        cy: Float,
        rotationDeg: Float,
        scale: Float = 1f,
    ) {
        textTransformPrev.set(batch.transformMatrix)
        composeCenteredTextTransform(
            dest = textTransform,
            incoming = textTransformPrev,
            cx = cx,
            cy = cy,
            rotationDeg = rotationDeg,
            scale = scale,
        )
        batch.transformMatrix = textTransform
        var curX = -runs.totalW * 0.5f
        val textY = runs.nameH * 0.5f
        if (runs.han.isNotEmpty()) {
            // The large glyph is centered on the text row, not baseline-aligned.
            Fonts.withFaceScale(runs.hanFont, runs.hanScale) {
                Fonts.drawText(
                    batch = batch,
                    primary = runs.hanFont,
                    text = runs.han,
                    x = curX,
                    y = runs.hanH * 0.5f,
                    color = runs.ink,
                )
            }
            curX += runs.hanW + runs.hanGap
        }
        Fonts.drawText(batch, runs.font, runs.prefix, curX, textY, runs.ink)
        curX += runs.prefixW
        val nameColor =
            if (runs.isBot) PlayerLabels.botNameTint(runs.ink, botNameTint) else runs.ink
        Fonts.drawText(batch, runs.font, runs.name, curX, textY, nameColor)
        curX += runs.nameW
        Fonts.drawText(batch, runs.font, runs.suffix, curX, textY, runs.ink)
        batch.transformMatrix = textTransformPrev
    }

    companion object {
        /** Ink for the seat whose turn it is: white on the felt; the size never changes. */
        private val ACTIVE_INK: Color = Color.WHITE

        /**
         * Size of a label that sits above its row, as a fraction of the resting
         * seat label. The band between the row and the river is shallow, so the
         * full-size ink would crowd the tiles below it (and the Han glyph would
         * touch them).
         */
        internal const val ABOVE_ROW_SCALE = 0.8f

        /**
         * Air between the above-row label's box and the row's felt-facing edge,
         * as a fraction of the resting-size box. The [ABOVE_ROW_SCALE] trim
         * leaves only a few pixels at the bottom of the box on a phone, which
         * reads as the wind character touching the tiles; this lift adds visible
         * daylight without carrying the label into the river.
         */
        internal const val ABOVE_ROW_LIFT = 0.12f

        /**
         * Smallest scale the above-row label will take before its name is allowed
         * to ellipsize, as a fraction of [ABOVE_ROW_SCALE]. Below this the run
         * reads as chrome noise rather than as a seat label.
         */
        internal const val MIN_ABOVE_ROW_SCALE = 0.72f

        /** Scratch for [boxHeight]; render is single-threaded. */
        private val scaleGlyphLayout = GlyphLayout()

        /**
         * The scale a seat label is drawn at on a table with this hand-tile height
         * and label gutter: the hand-tile cap ([baseLabelScale]) times the gutter
         * fit for the label face. Every seat uses it — the active one included, so
         * the turn never changes a label's size.
         *
         * This is the one coefficient every fixed-size label on the felt shares —
         * the center turn-status line, the HUD rule / hand counters, the rules
         * credit and the live-wall count. The gutter matters: it is the band
         * between the hand's outer face and the screen edge, proportional to the
         * short side. A scale built from the hand tile alone stays at 1 while the
         * window narrows and only collapses at the very end, so every other label
         * looked desktop-sized while the seat labels had already been shrinking.
         *
         * 1 keeps the desktop size. The tile is the same fraction of the short
         * side on every platform, so a phone app and a phone browser share the
         * scale and a large window stays at 1.
         */
        internal fun tableLabelScale(
            handH: Float,
            gutter: Float,
            tileGap: Float,
        ): Float {
            val base = baseLabelScale(handH)
            return base * gutterFitScale(
                boxH = boxHeight(Fonts.label),
                baseScale = base,
                gutter = gutter,
                tileGap = tileGap,
            )
        }

        /**
         * Scale for every seat label before its gutter fit.
         *
         * 1 keeps the desktop size. When the label, Han glyph included, would be
         * taller than the hand tile, shrink it to the tile. The tile is the same
         * fraction of the short side on every platform, so a phone app and a
         * phone browser share the scale and a large window stays at 1.
         */
        internal fun baseLabelScale(handH: Float): Float {
            val boxH = seatLabelBoxHeight()
            if (boxH <= 0f || handH <= 0f) return 1f
            return min(1f, handH / boxH)
        }

        /**
         * Box height of a seat label at scale 1: the Han glyph when it overhangs
         * the wind-name line, else that line. Latin sample; the Han box is
         * script-independent.
         */
        private fun seatLabelBoxHeight(): Float = boxHeight(Fonts.label)

        /** Box height [font]'s seat label occupies at scale 1. */
        private fun boxHeight(font: BitmapFont): Float {
            Fonts.ensureCreated()
            scaleGlyphLayout.setText(font, "East · You · +9999")
            val nameH = scaleGlyphLayout.height
            return max(nameH, hanGlyphHeight(nameH))
        }

        /**
         * Drawn box height of the large Han wind glyph beside a [nameLineH]-tall wind
         * name: 0 when no CJK face is loaded (the glyph is skipped, so the label is
         * the plain text row). All four wind characters share one face, so a single
         * sample measures the box.
         */
        private fun hanGlyphHeight(nameLineH: Float): Float {
            Fonts.ensureFallback()
            if (!Fonts.cjkCapable) return 0f
            val han = PlayerLabels.windHan(Tiles.EAST)
            val scale = hanScaleFor(nameLineH, Fonts.textHeight(Fonts.display, han))
            return Fonts.withFaceScale(Fonts.display, scale) { Fonts.textHeight(Fonts.display, han) }
        }

        /** Design px of air between the Han glyph and the wind name. */
        private const val HAN_GAP_PX = 8f

        /** The Han glyph targets this multiple of the text-row height, before [HAN_SIZE_FACTOR]. */
        internal const val HAN_NAME_RATIO = 1.7f

        /**
         * Drawn size of the Han glyph relative to the ratio above.
         * 0.8 is 20% smaller than the size that ratio used to paint, for both
         * the active and the resting label.
         */
        internal const val HAN_SIZE_FACTOR = 0.8f

        /**
         * Scale for the [Fonts.display] Han glyph so it reads larger than the
         * text row ([nameLineH] is that row's line height), then [HAN_SIZE_FACTOR]
         * of that.
         *
         * The fit is capped at 1 before the factor: [Fonts.display] is rasterized
         * at 48 design px and upscaling a bitmap face is what makes Han blurry.
         * 0 when either height is unknown.
         */
        internal fun hanScaleFor(nameLineH: Float, hanNativeH: Float): Float {
            if (nameLineH <= 0f || hanNativeH <= 0f) return 0f
            val fitted = min(1f, nameLineH * HAN_NAME_RATIO / hanNativeH)
            return fitted * HAN_SIZE_FACTOR
        }

        /**
         * Scale factor in (0, 1] to ensure the seat label fits within the
         * available gutter without overlapping tiles or screen edges.
         */
        internal fun gutterFitScale(
            boxH: Float,
            baseScale: Float,
            gutter: Float,
            tileGap: Float,
        ): Float {
            if (boxH <= 0f || baseScale <= 0f) return 1f
            val availableGutterH = (gutter - tileGap * 2f).coerceAtLeast(0f)
            val labelH = boxH * baseScale
            return if (labelH > availableGutterH && labelH > 0f) {
                (availableGutterH / labelH).coerceIn(0.1f, 1f)
            } else {
                1f
            }
        }

        /**
         * Local-Y of the label center (negative = toward the screen edge).
         *
         * Every seat centers in the gutter between its hand face and the screen
         * edge, with at least [tileGap] from both when the gutter is wide enough.
         * The bottom also clears [bottomReserve] (the tutorial coach row); a
         * tight gutter falls back to the hand-adjacent placement.
         */
        internal fun labelLocalY(
            side: SeatSide,
            handH: Float,
            tileGap: Float,
            labelH: Float,
            edgeInset: Float,
            safeInset: Float = 0f,
            bottomReserve: Float = 0f,
        ): Float {
            val outerEdge =
                if (side == SeatSide.Bottom) {
                    safeInset.coerceAtLeast(0f) + bottomReserve.coerceAtLeast(0f)
                } else {
                    safeInset.coerceAtLeast(0f)
                }
            val handFace = (edgeInset - handH * 0.5f).coerceAtLeast(outerEdge)
            val half = labelH * 0.5f
            val lo = outerEdge + tileGap + half
            val hi = handFace - tileGap - half
            val fromEdge = if (hi >= lo) ((outerEdge + handFace) * 0.5f).coerceIn(lo, hi) else hi
            return fromEdge - edgeInset
        }

        /**
         * Label local space: origin at the glyph-box center, then rotate / scale.
         * Must compose with [incoming] so a translated batch (tutorial content
         * zone) does not drop its offset.
         */
        internal fun composeCenteredTextTransform(
            dest: Matrix4,
            incoming: Matrix4,
            cx: Float,
            cy: Float,
            rotationDeg: Float,
            scale: Float,
        ) {
            dest.set(incoming)
            dest.translate(cx, cy, 0f)
            dest.rotate(0f, 0f, 1f, rotationDeg)
            if (scale != 1f) dest.scale(scale, scale, 1f)
        }
    }
}
