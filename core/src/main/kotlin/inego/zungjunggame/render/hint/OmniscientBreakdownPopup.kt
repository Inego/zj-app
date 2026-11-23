package inego.zungjunggame.render.hint

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.hints.OmniscientHintArmSnapshot
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.HandsTableText
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import kotlin.math.max

/**
 * Floating hover popup for Omniscient hints: shows an enlarged best-known
 * winning score, step count, and full Zung Jung pattern breakdown above the
 * hovered hand tile or claim button.
 */
object OmniscientBreakdownPopup {
    private const val SCORE_SCALE = 1.45f
    private const val ROW_SCALE = 0.84f
    private val DIVIDER_COLOR: Color = AppPalette.tint(AppPalette.panelRim, 0.35f)
    private val META_INK: Color = AppPalette.inkMuted

    fun draw(
        batch: SpriteBatch,
        arm: OmniscientHintArmSnapshot,
        anchorX: Float,
        anchorTopY: Float,
        screenW: Float,
        screenH: Float,
        glyphLayout: GlyphLayout = GlyphLayout(),
    ) {
        Fonts.ensureCreated()
        val px = WhitePixel.get()
        val scoreFont = Fonts.labelActive
        // Full-alpha button face scaled down (Fonts.small bakes 0.6 alpha into its atlas).
        val rowFont = Fonts.button

        val padX = Fonts.px(14f)
        val padY = Fonts.px(10f)
        val colGap = Fonts.px(18f)
        val rowGap = Fonts.px(5f)
        val dividerGap = Fonts.px(7f)
        val dividerH = max(1f, Fonts.px(1f))
        val screenMargin = Fonts.px(8f)
        val gapAboveAnchor = Fonts.px(8f)

        val details = arm.bestDetails
        val hasWin = arm.bestScore > 0 && details != null
        val rows: List<Pair<String, String>> = if (hasWin) {
            details.components.map { comp ->
                HandsTableText.componentName(comp) to comp.points.toString()
            }
        } else {
            emptyList()
        }

        val scoreText: String
        val scoreW: Float
        val scoreH: Float
        if (hasWin) {
            scoreText = arm.bestScore.toString()
            var w = 0f
            var h = 0f
            Fonts.withFaceScale(scoreFont, SCORE_SCALE) {
                glyphLayout.setText(scoreFont, scoreText)
                w = glyphLayout.width
                h = glyphLayout.height
            }
            scoreW = w
            scoreH = h
        } else {
            scoreText = if (arm.playouts > 0 || arm.solved) {
                L10n.get("hint.omniscient.no_win")
            } else {
                L10n.get("hint.omniscient.searching")
            }
            var w = 0f
            var h = 0f
            Fonts.withFaceScale(rowFont, ROW_SCALE) {
                w = Fonts.textWidth(rowFont, scoreText)
                h = rowFont.capHeight
            }
            scoreW = w
            scoreH = h
        }

        val stepsNote = if (hasWin && arm.bestSteps > 0) {
            L10n.format("hint.omniscient.steps", arm.bestSteps)
        } else {
            null
        }
        val capNote = if (hasWin && details.uncappedTotal > details.totalScore) {
            "(${details.uncappedTotal}\u2192${details.totalScore})"
        } else {
            null
        }
        val exactTag = if (hasWin && arm.solved) L10n.get("hint.omniscient.exact") else null

        var stepsW = 0f
        var capW = 0f
        var exactW = 0f
        var maxNameW = 0f
        var maxPtsW = 0f
        var rowH = 0f
        var metaCapH = 0f

        Fonts.withFaceScale(rowFont, ROW_SCALE) {
            metaCapH = rowFont.capHeight
            rowH = rowFont.capHeight
            if (stepsNote != null) stepsW = Fonts.textWidth(rowFont, stepsNote)
            if (capNote != null) capW = Fonts.textWidth(rowFont, capNote)
            if (exactTag != null) exactW = Fonts.textWidth(rowFont, exactTag)
            for ((name, pts) in rows) {
                val nw = Fonts.textWidth(rowFont, name)
                if (nw > maxNameW) maxNameW = nw
                val pw = Fonts.textWidth(rowFont, pts)
                if (pw > maxPtsW) maxPtsW = pw
            }
        }

        val headerMetaGap = Fonts.px(8f)
        var headerW = scoreW
        if (capNote != null) headerW += headerMetaGap + capW
        if (stepsNote != null) headerW += headerMetaGap + stepsW
        if (exactTag != null) headerW += headerMetaGap + exactW

        val rowsW = if (rows.isNotEmpty()) maxNameW + colGap + maxPtsW else 0f
        val innerW = max(headerW, rowsW)
        val cardW = innerW + padX * 2f

        val rowsBlockH = if (rows.isNotEmpty()) {
            dividerGap + dividerH + dividerGap + rows.size * rowH + (rows.size - 1) * rowGap
        } else {
            0f
        }
        val cardH = padY * 2f + scoreH + rowsBlockH

        // Clamp within safe viewport bounds
        val avail = OverlayViewport.available(screenW, screenH)
        val minLeft = avail.left + screenMargin
        val maxLeft = max(minLeft, avail.right - screenMargin - cardW)
        val cardLeft = (anchorX - cardW * 0.5f).coerceIn(minLeft, maxLeft)
        val minBottom = avail.bottom + screenMargin
        val maxBottom = max(minBottom, avail.top - screenMargin - cardH)
        val cardBottom = (anchorTopY + gapAboveAnchor).coerceIn(minBottom, maxBottom)

        OverlayChrome.drawPanel(
            batch,
            px,
            cardLeft,
            cardBottom,
            cardW,
            cardH,
            AppPalette.panel,
            AppPalette.panelRim,
            max(1.5f, Fonts.px(2f)),
        )

        val contentLeft = cardLeft + padX
        val contentRight = cardLeft + cardW - padX
        val headerTop = cardBottom + cardH - padY

        if (hasWin) {
            Fonts.withFaceScale(scoreFont, SCORE_SCALE) {
                Fonts.drawText(batch, scoreFont, scoreText, contentLeft, headerTop, AppPalette.accentBright)
            }
            Fonts.withFaceScale(rowFont, ROW_SCALE) {
                var metaX = contentLeft + scoreW
                val metaY = headerTop - (scoreH - metaCapH) * 0.5f
                if (capNote != null) {
                    metaX += headerMetaGap
                    Fonts.drawText(batch, rowFont, capNote, metaX, metaY, META_INK)
                    metaX += capW
                }
                if (stepsNote != null) {
                    metaX += headerMetaGap
                    Fonts.drawText(batch, rowFont, stepsNote, metaX, metaY, META_INK)
                }
                if (exactTag != null) {
                    Fonts.drawText(batch, rowFont, exactTag, contentRight - exactW, metaY, META_INK)
                }
            }
        } else {
            Fonts.withFaceScale(rowFont, ROW_SCALE) {
                Fonts.drawText(batch, rowFont, scoreText, contentLeft, headerTop, META_INK)
            }
        }

        if (rows.isNotEmpty()) {
            val divY = headerTop - scoreH - dividerGap
            batch.setColor(DIVIDER_COLOR)
            batch.draw(px, contentLeft, divY - dividerH, innerW, dividerH)
            batch.setColor(Color.WHITE)

            Fonts.withFaceScale(rowFont, ROW_SCALE) {
                var rowTop = divY - dividerH - dividerGap
                for ((name, pts) in rows) {
                    Fonts.drawText(batch, rowFont, name, contentLeft, rowTop, AppPalette.ink)
                    val ptsW = Fonts.textWidth(rowFont, pts)
                    Fonts.drawText(batch, rowFont, pts, contentRight - ptsW, rowTop, AppPalette.inkStrong)
                    rowTop -= rowH + rowGap
                }
            }
        }
    }
}
