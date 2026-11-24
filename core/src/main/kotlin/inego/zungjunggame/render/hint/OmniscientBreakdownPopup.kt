package inego.zungjunggame.render.hint

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.hints.OmniscientHintArmSnapshot
import inego.zungjunggame.hints.OmniscientWinningHand
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.HandsTableText
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.OverlayViewport
import kotlin.math.max

/**
 * Floating hover popup for Omniscient hints: shows the winning hand strip,
 * enlarged best-known winning score, step count, and full Zung Jung pattern
 * breakdown above the hovered hand tile or claim button.
 */
object OmniscientBreakdownPopup {
    private const val SCORE_SCALE = 1.45f
    private const val ROW_SCALE = 0.84f
    private const val TILE_HEIGHT_DP = 32f
    private const val WIN_ROW_GAP_DP = 8f
    private const val WIN_SOURCE_TEXT_GAP_DP = 10f
    private const val HAND_BOTTOM_GAP_DP = 10f
    private const val MIN_HAND_SCALE = 0.45f
    private const val TILE_GAP_FRACTION = 0.06f
    private const val MELD_GAP_FRACTION = 0.42f
    private val PANEL_FILL: Color = AppPalette.tint(AppPalette.panel, 0.80f)
    private val DIVIDER_COLOR: Color = AppPalette.tint(AppPalette.panelRim, 0.35f)
    private val META_INK: Color = AppPalette.inkMuted
    private val FACE_TINT: Color = AppPalette.faceTint

    fun draw(
        batch: SpriteBatch,
        drawer: TileDrawer,
        arm: OmniscientHintArmSnapshot,
        anchorX: Float,
        anchorTopY: Float,
        screenW: Float,
        screenH: Float,
        humanSeat: Int = 0,
        tileAspect: Float = 3f / 4f,
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
        val winningHand = if (hasWin) arm.bestWinningHand else null
        val hasWinTileRow = winningHand != null && winningHand.winningTile >= 0
        val winSourceText = if (hasWinTileRow) {
            PlayerLabels.winSourceLabel(
                discarder = winningHand.winSourceSeat.takeIf { it >= 0 },
                humanSeat = humanSeat,
            )
        } else {
            null
        }

        var stepsW = 0f
        var capW = 0f
        var exactW = 0f
        var winSourceW = 0f
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
            if (winSourceText != null) winSourceW = Fonts.textWidth(rowFont, winSourceText)
            for ((name, pts) in rows) {
                val nw = Fonts.textWidth(rowFont, name)
                if (nw > maxNameW) maxNameW = nw
                val pw = Fonts.textWidth(rowFont, pts)
                if (pw > maxPtsW) maxPtsW = pw
            }
        }

        val safeRect = OverlayViewport.available(screenW, screenH, marginPx = 0f)
        val maxInnerW = max(1f, safeRect.width - (screenMargin + padX) * 2f)

        val baseTileH = Fonts.px(TILE_HEIGHT_DP)
        val baseTileW = baseTileH * tileAspect
        val baseTileGap = baseTileW * TILE_GAP_FRACTION
        val baseMeldGap = baseTileW * MELD_GAP_FRACTION
        val winSourceGap = Fonts.px(WIN_SOURCE_TEXT_GAP_DP)
        val rawHandRowW = if (winningHand != null) {
            measureHandRowWidth(winningHand, baseTileW, baseTileGap, baseMeldGap)
        } else {
            0f
        }
        val hasHandRow = winningHand != null && rawHandRowW > 0f
        val handScale = if (hasHandRow && rawHandRowW > maxInnerW) {
            (maxInnerW / rawHandRowW).coerceAtLeast(MIN_HAND_SCALE)
        } else {
            1f
        }
        val tileW = baseTileW * handScale
        val tileH = baseTileH * handScale
        val tileGap = baseTileGap * handScale
        val meldGap = baseMeldGap * handScale
        val handRowW = if (hasHandRow) rawHandRowW * handScale else 0f
        val winRowW = if (hasWinTileRow) {
            tileW + if (!winSourceText.isNullOrEmpty()) winSourceGap + winSourceW else 0f
        } else {
            0f
        }
        val winRowGap = Fonts.px(WIN_ROW_GAP_DP)
        val handBottomGap = Fonts.px(HAND_BOTTOM_GAP_DP)
        val handBlockH = when {
            hasWinTileRow && hasHandRow -> tileH + winRowGap + tileH + handBottomGap
            hasWinTileRow || hasHandRow -> tileH + handBottomGap
            else -> 0f
        }

        val headerMetaGap = Fonts.px(8f)
        val leftHeaderW = when {
            stepsNote != null && exactTag != null -> stepsW + headerMetaGap + exactW
            stepsNote != null -> stepsW
            exactTag != null -> exactW
            else -> 0f
        }
        val rightHeaderW = scoreW + if (capNote != null) headerMetaGap + capW else 0f
        val headerW = if (hasWin && leftHeaderW > 0f) {
            leftHeaderW + colGap + rightHeaderW
        } else {
            rightHeaderW
        }

        val rowsW = if (rows.isNotEmpty()) maxNameW + colGap + maxPtsW else 0f
        val innerW = max(max(winRowW, handRowW), max(headerW, rowsW))
        val cardW = innerW + padX * 2f

        val rowsBlockH = if (rows.isNotEmpty()) {
            dividerGap + dividerH + dividerGap + rows.size * rowH + (rows.size - 1) * rowGap
        } else {
            0f
        }
        val cardH = padY * 2f + handBlockH + scoreH + rowsBlockH

        // Clamp within safe viewport bounds: keep a small horizontal margin,
        // but allow touching the top/bottom safe edges when vertically tight
        // (e.g. Android landscape with ready-hand wait row underneath).
        val minLeft = safeRect.left + screenMargin
        val maxLeft = max(minLeft, safeRect.right - screenMargin - cardW)
        val cardLeft = (anchorX - cardW * 0.5f).coerceIn(minLeft, maxLeft)
        val minBottom = safeRect.bottom
        val maxBottom = max(minBottom, safeRect.top - cardH)
        val cardBottom = (anchorTopY + gapAboveAnchor).coerceIn(minBottom, maxBottom)

        OverlayChrome.drawPanel(
            batch,
            px,
            cardLeft,
            cardBottom,
            cardW,
            cardH,
            PANEL_FILL,
            AppPalette.panelRim,
            max(1.5f, Fonts.px(2f)),
        )

        val contentLeft = cardLeft + padX
        val contentRight = cardLeft + cardW - padX
        var cursorTop = cardBottom + cardH - padY

        if (hasWinTileRow) {
            val relativeSource = if (winningHand.winSourceSeat >= 0) {
                ((winningHand.winSourceSeat - humanSeat) % NUM_PLAYERS + NUM_PLAYERS) % NUM_PLAYERS
            } else {
                -1
            }
            val winRowLeft = when (relativeSource) {
                3 -> contentLeft // from Left
                1 -> contentRight - winRowW // from Right
                else -> contentLeft + (innerW - winRowW) * 0.5f // Opposite or self-draw
            }
            val tileOnRight = relativeSource == 1
            val winTileLeft = if (tileOnRight) winRowLeft + winRowW - tileW else winRowLeft
            val winCx = winTileLeft + tileW * 0.5f
            val winCy = cursorTop - tileH * 0.5f
            batch.setColor(Color.WHITE)
            drawer.drawGlow(
                batch = batch,
                cx = winCx,
                cy = winCy,
                w = tileW,
                h = tileH,
                color = TileDrawer.WIN_DISCARD_GLOW_COLOR,
                expand = 1.55f,
            )
            batch.setColor(FACE_TINT)
            drawer.drawOpen(batch, winningHand.winningTile, winCx, winCy, tileW, tileH)
            batch.setColor(Color.WHITE)
            if (!winSourceText.isNullOrEmpty()) {
                Fonts.withFaceScale(rowFont, ROW_SCALE) {
                    val textX = if (tileOnRight) winRowLeft else winRowLeft + tileW + winSourceGap
                    val textY = winCy + rowH * 0.5f
                    Fonts.drawText(batch, rowFont, winSourceText, textX, textY, AppPalette.ink)
                }
            }
            cursorTop -= tileH + if (hasHandRow) winRowGap else handBottomGap
        }

        if (hasHandRow) {
            val handLeft = contentLeft + (innerW - handRowW) * 0.5f
            val handCy = cursorTop - tileH * 0.5f
            drawHandRow(
                batch = batch,
                drawer = drawer,
                hand = winningHand,
                startX = handLeft,
                cy = handCy,
                tileW = tileW,
                tileH = tileH,
                tileGap = tileGap,
                meldGap = meldGap,
            )
            cursorTop -= tileH + handBottomGap
        }

        val headerTop = cursorTop

        if (hasWin) {
            val scoreX = contentRight - scoreW
            Fonts.withFaceScale(scoreFont, SCORE_SCALE) {
                Fonts.drawText(batch, scoreFont, scoreText, scoreX, headerTop, AppPalette.accentBright)
            }
            Fonts.withFaceScale(rowFont, ROW_SCALE) {
                val metaY = headerTop - (scoreH - metaCapH) * 0.5f
                var leftX = contentLeft
                if (stepsNote != null) {
                    Fonts.drawText(batch, rowFont, stepsNote, leftX, metaY, META_INK)
                    leftX += stepsW + headerMetaGap
                }
                if (exactTag != null) {
                    Fonts.drawText(batch, rowFont, exactTag, leftX, metaY, META_INK)
                }
                if (capNote != null) {
                    Fonts.drawText(batch, rowFont, capNote, scoreX - headerMetaGap - capW, metaY, META_INK)
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

    private fun measureHandRowWidth(
        hand: OmniscientWinningHand,
        tileW: Float,
        tileGap: Float,
        meldGap: Float,
    ): Float {
        var w = 0f
        var hasLeft = false
        val concealedCount = hand.concealedTiles.size
        if (concealedCount > 0) {
            w += concealedCount * tileW + (concealedCount - 1) * tileGap
            hasLeft = true
        }
        for (meld in hand.melds) {
            val n = meld.tiles.size
            if (n == 0) continue
            if (hasLeft) w += meldGap
            w += n * tileW + (n - 1) * tileGap
            hasLeft = true
        }
        return w
    }

    private fun drawHandRow(
        batch: SpriteBatch,
        drawer: TileDrawer,
        hand: OmniscientWinningHand,
        startX: Float,
        cy: Float,
        tileW: Float,
        tileH: Float,
        tileGap: Float,
        meldGap: Float,
    ) {
        var x = startX
        var hasLeft = false

        if (hand.concealedTiles.isNotEmpty()) {
            batch.setColor(FACE_TINT)
            for ((i, tile) in hand.concealedTiles.withIndex()) {
                val cx = x + tileW * 0.5f
                drawer.drawOpen(batch, tile, cx, cy, tileW, tileH)
                x += tileW
                if (i < hand.concealedTiles.lastIndex) x += tileGap
            }
            hasLeft = true
        }

        for (meld in hand.melds) {
            val tiles = meld.tiles
            if (tiles.isEmpty()) continue
            if (hasLeft) x += meldGap
            for ((i, tile) in tiles.withIndex()) {
                val cx = x + tileW * 0.5f
                if (meld.concealedKong && (i == 0 || i == tiles.lastIndex)) {
                    batch.setColor(Color.WHITE)
                    drawer.drawClosed(batch, cx, cy, tileW, tileH)
                } else {
                    batch.setColor(FACE_TINT)
                    drawer.drawOpen(batch, tile, cx, cy, tileW, tileH)
                }
                x += tileW
                if (i < tiles.lastIndex) x += tileGap
            }
            hasLeft = true
        }

        batch.setColor(Color.WHITE)
    }
}
