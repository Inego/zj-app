package inego.zungjunggame.render.table

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.DiscardWait
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileLabels
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * Floating magnified open face for the hand tile under a held finger, plus the
 * ready-discard wait row above it.
 *
 * The touch magnifier stays the face of the tile under the finger, at 2.15× the
 * hand tile — [ENLARGED_SCALE] when the hand row is the enlarged
 * Android-landscape one — centered on the finger and clamped to the screen.
 * When that discard is ready, a row of the waits sits above the face: open
 * faces in tile-type order at the loupe's own tile scale (the hand size,
 * reduced with the loupe on the enlarged row) so the stack keeps its
 * proportions and its counts stay legible. Above each face the Hu score sits
 * on its own line and the unseen count (`×n`) on the line under it, on a dark
 * plate so the cream type stays readable when the row lies on the river. The
 * score cap is a fraction of the wait face and never larger than the chrome
 * face, so a dense phone does not draw desktop-chrome glyphs on a small tile.
 * Faces keep the hand gap. The row shrinks uniformly if it would leave the
 * screen; the magnified face never shrinks.
 *
 * The magnified face follows the tile-labels setting like the board does, since
 * the loupe is where a learner reads the held tile. The wait row does not: its
 * faces sit at hand size or smaller and are read as a set, so corner tags there
 * would be noise rather than labels.
 *
 * Desktop passes the hovered hand tile's top and `showDiscardFace = false`, so
 * only the wait row is drawn above the visible hand tile.
 */
object TouchTileMagnifier {
    /** Magnification over the hand tile for the ordinary (non-enlarged) hand. */
    private const val SCALE = 2.15f

    /**
     * Magnification over the hand tile on the enlarged Android-landscape row.
     * That row already fills the reclaimed bottom gutter (roughly 1.8× the felt
     * hand), so keeping [SCALE] made the loupe cover a third of the screen. 1.5×
     * still magnifies the held tile without taking over the felt.
     */
    private const val ENLARGED_SCALE = 1.5f
    private const val MIN_WAIT_SCALE = 0.05f

    /**
     * Clearance above the anchor before the wait faces, as a fraction of the
     * wait row's tile height (the hand height, reduced with the loupe on the
     * enlarged row). Includes the ready outline drawn outside the raised face.
     */
    private const val GAP_ABOVE_ANCHOR = 0.22f

    /** Clearance between a wait face and the count line above it. */
    private const val GAP_ABOVE_WAIT_FACE = 0.10f

    /** Clearance between the count line and the score line above it. */
    private const val GAP_BETWEEN_LABELS = 0.06f

    /**
     * Bold score cap as a fraction of the wait face. The count uses the same
     * scale, so the two lines keep their weight. 0.32 is above the desktop
     * chrome proportion and well under the density-scaled chrome face that
     * dwarfed the tile on a phone. The applied scale never exceeds 1, so a
     * desktop whose chrome face is already this size or smaller is left alone.
     * A line wider than its tile shrinks further so neighbors still meet only
     * at the hand gap.
     */
    private const val SCORE_CAP_FRACTION = 0.32f

    private val WAIT_TEXT_COLOR: Color = AppPalette.waitInk
    private val FACE_TINT: Color = AppPalette.faceTint

    /**
     * Plate behind one wait's score and count. Dark enough that the cream type
     * separates from a river face, open enough that the tile under it still reads.
     */
    private val WAIT_LABEL_BACK: Color = AppPalette.tint(AppPalette.panelDeep, 0.65f)

    /** Horizontal pad around that plate, as a fraction of the wait-face height. */
    private const val LABEL_BACK_PAD_X = 0.07f

    /**
     * Vertical pad around that plate, as a fraction of the wait-face height.
     * Stays inside the gap above the face, so the plate does not cover the tile.
     */
    private const val LABEL_BACK_PAD_Y = 0.04f

    fun draw(
        batch: SpriteBatch,
        drawer: TileDrawer,
        tileType: Int,
        anchorX: Float,
        anchorY: Float,
        screenW: Float,
        screenH: Float,
        handW: Float,
        handH: Float,
        handGap: Float,
        /**
         * True when [handW]/[handH] are the enlarged Android-landscape row's
         * tiles. The loupe magnifies them by [ENLARGED_SCALE] instead of [SCALE],
         * and the wait row below the loupe shrinks by the same ratio.
         */
        enlargedHand: Boolean = false,
        waits: List<DiscardWait> = emptyList(),
        showDiscardFace: Boolean = true,
    ) {
        val margin = Fonts.px(8f)
        val magnifiedScale = if (enlargedHand) ENLARGED_SCALE else SCALE
        val mw = handW * magnifiedScale
        val mh = handH * magnifiedScale
        // The wait row belongs to the loupe stack: on the enlarged hand it
        // shrinks by the same ratio as the loupe, so the faces and their counts
        // do not stay at the height that made the loupe huge.
        val waitScale = magnifiedScale / SCALE
        val waitW = handW * waitScale
        val waitH = handH * waitScale
        val gapAbove = waitH * GAP_ABOVE_ANCHOR

        var faceCy = Float.NaN
        val rowBottomY: Float
        if (showDiscardFace) {
            val gap = Fonts.px(16f) + mh * 0.12f
            faceCy = (anchorY + mh * 0.5f + gap).coerceIn(
                mh * 0.5f + margin,
                (screenH - mh * 0.5f - margin).coerceAtLeast(mh * 0.5f + margin),
            )
            rowBottomY = faceCy + mh * 0.5f + gapAbove
        } else {
            rowBottomY = anchorY + gapAbove
        }

        val row = if (waits.isEmpty()) {
            null
        } else {
            WaitRow.fit(waits, waitW, waitH, handGap, margin, screenW, screenH, rowBottomY)
        }

        val stackW = if (showDiscardFace) max(mw, row?.width ?: 0f) else (row?.width ?: 0f)
        val cx = anchorX.coerceIn(
            stackW * 0.5f + margin,
            (screenW - stackW * 0.5f - margin).coerceAtLeast(stackW * 0.5f + margin),
        )

        if (showDiscardFace) {
            drawer.drawGlow(
                batch,
                cx,
                faceCy,
                mw,
                mh,
                rotationDeg = 0f,
                color = AppPalette.tint(AppPalette.accent, 0.55f),
                expand = 1.65f,
            )
            batch.setColor(AppPalette.sheenBright)
            drawer.drawOpen(
                batch, tileType, cx, faceCy, mw, mh,
                // Learner aid: the loupe is where a player names the held tile,
                // so it follows the setting like the board. `tagOrNull` folds the
                // preference gate in, and the tag's halo takes the face tint.
                label = TileLabels.tagOrNull(tileType),
            )
            batch.setColor(Color.WHITE)
        }

        row?.draw(batch, drawer, cx, rowBottomY)
    }

    /**
     * One row of wait faces. The Hu score is the upper line, the unseen count
     * (`×n`) the lower line, each centered on its face, with a dark plate behind
     * the two lines. Faces stay on the hand gap. [fontScale] keeps the score cap
     * at [SCORE_CAP_FRACTION] of the face, never above the chrome face, and small
     * enough that the wider line still fits in the face.
     */
    private class WaitRow(
        val waits: List<DiscardWait>,
        val faceW: Float,
        val faceH: Float,
        val gap: Float,
        val fontScale: Float,
        val width: Float,
    ) {
        fun draw(batch: SpriteBatch, drawer: TileDrawer, cx: Float, bottomY: Float) {
            val countFont = Fonts.body
            val scoreFont = Fonts.wallCount
            Fonts.withFaceScale(countFont, fontScale) {
                Fonts.withFaceScale(scoreFont, fontScale) {
                    // Draw Y is the cap top. The count sits on the gap above the
                    // face; the score sits on the gap above the count.
                    val countCap = countFont.data.capHeight
                    val scoreCap = scoreFont.data.capHeight
                    val countBaseline = bottomY + faceH * (1f + GAP_ABOVE_WAIT_FACE)
                    val countY = countBaseline + countCap
                    val scoreY = countY + faceH * GAP_BETWEEN_LABELS + scoreCap
                    val faceCy = bottomY + faceH * 0.5f
                    val padX = faceH * LABEL_BACK_PAD_X
                    val padY = faceH * LABEL_BACK_PAD_Y
                    val originX = cx - width * 0.5f
                    var x = originX
                    for ((tileType) in waits) {
                        val faceCx = x + faceW * 0.5f
                        batch.setColor(FACE_TINT)
                        drawer.drawOpen(batch, tileType, faceCx, faceCy, faceW, faceH)
                        x += faceW + gap
                    }
                    batch.setColor(Color.WHITE)

                    // Plates before glyphs. A later plate must not cover an
                    // earlier wait's ink when wide scores meet in the gap.
                    val pixel = WhitePixel.get()
                    x = originX
                    for (wait in waits) {
                        val faceCx = x + faceW * 0.5f
                        val blockW = labelBlockWidth(countFont, scoreFont, wait)
                        batch.setColor(WAIT_LABEL_BACK)
                        batch.draw(
                            pixel,
                            faceCx - blockW * 0.5f - padX,
                            countBaseline - padY,
                            blockW + padX * 2f,
                            scoreY - countBaseline + padY * 2f,
                        )
                        x += faceW + gap
                    }
                    batch.setColor(Color.WHITE)

                    x = originX
                    for (wait in waits) {
                        val faceCx = x + faceW * 0.5f
                        val count = countLabel(wait)
                        val score = scoreLabel(wait)
                        val countW = Fonts.textWidth(countFont, count)
                        val scoreW = Fonts.textWidth(scoreFont, score)
                        Fonts.drawText(
                            batch, countFont, count,
                            faceCx - countW * 0.5f, countY, WAIT_TEXT_COLOR,
                        )
                        Fonts.drawText(
                            batch, scoreFont, score,
                            faceCx - scoreW * 0.5f, scoreY, WAIT_TEXT_COLOR,
                        )
                        x += faceW + gap
                    }
                }
            }
        }

        private fun labelBlockWidth(
            countFont: BitmapFont,
            scoreFont: BitmapFont,
            wait: DiscardWait,
        ): Float = max(
            Fonts.textWidth(countFont, countLabel(wait)),
            Fonts.textWidth(scoreFont, scoreLabel(wait)),
        )

        companion object {
            private fun countLabel(wait: DiscardWait): String = "\u00D7${wait.unseen}"

            private fun scoreLabel(wait: DiscardWait): String = wait.score.toString()

            fun fit(
                waits: List<DiscardWait>,
                handW: Float,
                handH: Float,
                handGap: Float,
                margin: Float,
                screenW: Float,
                screenH: Float,
                bottomY: Float,
            ): WaitRow {
                val n = waits.size
                val scoreCap = Fonts.wallCount.data.capHeight.coerceAtLeast(1f)
                val countCap = Fonts.body.data.capHeight.coerceAtLeast(1f)
                val widest = widestLabel(waits)
                val availW = (screenW - 2f * margin).coerceAtLeast(1f)
                val availH = (screenH - margin - bottomY).coerceAtLeast(1f)

                val fontAtFull = fontScaleFor(handW, handH, scoreCap, widest)
                val textAtFull =
                    (countCap + scoreCap) * fontAtFull + handH * GAP_BETWEEN_LABELS
                val rowH = textAtFull + handH * GAP_ABOVE_WAIT_FACE + handH
                val tilesW = n * handW + (n - 1).coerceAtLeast(0) * handGap
                val scale = min(1f, min(availW / tilesW, availH / rowH))
                    .coerceAtLeast(MIN_WAIT_SCALE)

                val faceW = handW * scale
                val faceH = handH * scale
                return WaitRow(
                    waits = waits,
                    faceW = faceW,
                    faceH = faceH,
                    gap = handGap * scale,
                    fontScale = fontScaleFor(faceW, faceH, scoreCap, widest),
                    width = n * faceW + (n - 1).coerceAtLeast(0) * handGap * scale,
                )
            }

            /** Wider of the count line and the score line, at font scale 1. */
            private fun widestLabel(waits: List<DiscardWait>): Float {
                var widest = 1f
                for (wait in waits) {
                    widest = max(widest, Fonts.textWidth(Fonts.body, countLabel(wait)))
                    widest = max(widest, Fonts.textWidth(Fonts.wallCount, scoreLabel(wait)))
                }
                return widest
            }

            /**
             * Shared scale for both lines. The score cap tracks the face, the
             * chrome face is never enlarged, and neither line runs past its tile.
             */
            private fun fontScaleFor(
                faceW: Float,
                faceH: Float,
                scoreCap: Float,
                widest: Float,
            ): Float = min(
                1f,
                min(faceH * SCORE_CAP_FRACTION / scoreCap, faceW / widest),
            )
        }
    }
}
