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
 * bottom-to-top, right reads top-to-bottom. The active seat is marked by weight
 * ([Fonts.labelActive]) and a fixed size boost, **not** by animation: it used to
 * pulse, and since a seat is always active that kept the whole table asking for
 * frames forever. See [activeScaleBoost].
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
     * Extra scale the active seat's label carries, on top of [baseLabelScale].
     *
     * **Static, deliberately.** This used to be a 1.1 s sine pulse, and the pulse
     * was not merely cosmetic: a seat is *always* active, so an animated label meant
     * the table always had a clock running, so it always asked for a frame. The
     * active seat's label visibly froze mid-pulse whenever the player stopped
     * moving the mouse — the picture only advanced when unrelated input happened to
     * deliver a frame. A static boost keeps the affordance and removes the clock, so
     * a settled board genuinely settles.
     *
     * The value is the pulse's old *mean* rather than its peak, so the label reads as
     * the size it appeared to average rather than as a new, larger label.
     */
    var activeScaleBoost: Float = DEFAULT_ACTIVE_SCALE_BOOST

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
    ) {
        if (windTile < 0) return

        val windFont = if (active) Fonts.labelActive else Fonts.label
        val playerName = PlayerLabels.nameForAbsoluteSeat(seat, humanSeat)
        val measured = measureRuns(
            windFont = windFont,
            windTile = windTile,
            windName = PlayerLabels.windName(windTile),
            playerName = playerName,
            score = seatScores.getOrElse(seat) { 0 },
            isBot = PlayerLabels.isBot(seat),
        )
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
        val bScale = baseLabelScale(handH, activeScaleBoost)
        val handFace = (edgeInset - handH * 0.5f).coerceAtLeast(outerEdge)
        val gutter = handFace - outerEdge
        val fitScale = gutterFitScale(
            boxH = measured.boxH,
            baseScale = bScale,
            active = active,
            activeScaleBoost = activeScaleBoost,
            gutter = gutter,
            tileGap = tileGap,
        )
        val runs = if (side == SeatSide.Bottom) {
            val span = (screenW - 2f * safeEdge).coerceAtLeast(1f)
            val paintedScale = bScale * fitScale * (if (active) activeScaleBoost else 1f)
            val unscaledSpan = if (paintedScale > 0f) span / paintedScale else span
            if (measured.totalW > unscaledSpan && measured.name.isNotEmpty()) {
                val budget = (unscaledSpan - (measured.totalW - measured.nameW)).coerceAtLeast(0f)
                val fitted = TextFit.ellipsize(windFont, measured.name, budget)
                if (fitted != measured.name) {
                    measureRuns(
                        windFont = windFont,
                        windTile = windTile,
                        windName = PlayerLabels.windName(windTile),
                        playerName = fitted,
                        score = seatScores.getOrElse(seat) { 0 },
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

        // The anchor uses the scale the glyphs are painted at, boost included,
        // so the active label does not grow out of its box into the tiles.
        val drawnScale = bScale * fitScale * (if (active) activeScaleBoost else 1f)
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
     * Measures one seat label: the large Han wind glyph (when a CJK face is
     * loaded), then the optional tile-tag symbol, then name · score.
     */
    private fun measureRuns(
        windFont: BitmapFont,
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
     * Optional [scale] is applied about the center (the active seat's static boost).
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
                    color = runs.font.color,
                )
            }
            curX += runs.hanW + runs.hanGap
        }
        Fonts.drawText(batch, runs.font, runs.prefix, curX, textY, runs.font.color)
        curX += runs.prefixW
        val nameColor =
            if (runs.isBot) PlayerLabels.botNameTint(runs.font.color, botNameTint) else runs.font.color
        Fonts.drawText(batch, runs.font, runs.name, curX, textY, nameColor)
        curX += runs.nameW
        Fonts.drawText(batch, runs.font, runs.suffix, curX, textY, runs.font.color)
        batch.transformMatrix = textTransformPrev
    }

    companion object {
        /**
         * The default size boost the active seat's label carries. [baseLabelScale]
         * reserves for it, so every label stays clear of the tile even while the
         * active seat is emphasised.
         */
        internal const val DEFAULT_ACTIVE_SCALE_BOOST = 1.08f

        /** Scratch for [boxHeight]; render is single-threaded. */
        private val scaleGlyphLayout = GlyphLayout()

        /**
         * The scale a standard seat label is drawn at on a table with this
         * hand-tile height and label gutter: the hand-tile cap ([baseLabelScale])
         * times the gutter fit for the standard label face. (The active seat draws
         * the emphasised face with its static boost on top, so it stays larger.)
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
            activeScaleBoost: Float = DEFAULT_ACTIVE_SCALE_BOOST,
        ): Float {
            val base = baseLabelScale(handH, activeScaleBoost)
            return base * gutterFitScale(
                boxH = boxHeight(Fonts.label),
                baseScale = base,
                active = false,
                activeScaleBoost = activeScaleBoost,
                gutter = gutter,
                tileGap = tileGap,
            )
        }

        /**
         * Scale for every seat label before the active seat's boost and before its
         * gutter fit.
         *
         * 1 keeps the desktop size. When the active label, Han glyph and boost
         * included, would be taller than the hand tile, shrink it to the tile.
         * The tile is the same fraction of the short side on every platform, so
         * a phone app and a phone browser share the scale and a large window
         * stays at 1.
         */
        internal fun baseLabelScale(
            handH: Float,
            activeScaleBoost: Float = DEFAULT_ACTIVE_SCALE_BOOST,
        ): Float = labelFitScale(activeLabelBoxHeight(), handH, activeScaleBoost)

        internal fun labelFitScale(activeBoxH: Float, handH: Float, activeScaleBoost: Float): Float {
            if (activeBoxH <= 0f || handH <= 0f || activeScaleBoost <= 0f) return 1f
            return min(1f, handH / (activeBoxH * activeScaleBoost))
        }

        /**
         * Box height of the active label at scale 1: the Han glyph when it overhangs
         * the wind-name line, else that line. Latin sample; the Han box is
         * script-independent.
         */
        private fun activeLabelBoxHeight(): Float = boxHeight(Fonts.labelActive)

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
         * Scale factor in (0, 1] to ensure the seat label (including the active
         * seat's static boost) fits within the available gutter without overlapping
         * tiles or screen edges.
         */
        internal fun gutterFitScale(
            boxH: Float,
            baseScale: Float,
            active: Boolean,
            activeScaleBoost: Float,
            gutter: Float,
            tileGap: Float,
        ): Float {
            if (boxH <= 0f || baseScale <= 0f) return 1f
            val availableGutterH = (gutter - tileGap * 2f).coerceAtLeast(0f)
            val peakH = boxH * baseScale * (if (active) activeScaleBoost else 1f)
            return if (peakH > availableGutterH && peakH > 0f) {
                (availableGutterH / peakH).coerceIn(0.1f, 1f)
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
