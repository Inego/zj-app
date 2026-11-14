package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.tutorial.QuizBin
import inego.zungjunggame.tutorial.QuizKind
import inego.zungjunggame.tutorial.QuizOption
import inego.zungjunggame.tutorial.QuizOptionMark
import inego.zungjunggame.tutorial.TutorialStep
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/**
 * Interactive quiz options on the felt: tile-hand grid or labeled multi-choice chips.
 *
 * Tile hands are **top-anchored** so bottom chrome (Confirm / status) never reflows them.
 */
object QuizOptionPresenter {
    private val glyphLayout = GlyphLayout()

    fun draw(
        batch: SpriteBatch,
        px: Texture,
        drawer: TileDrawer,
        q: TutorialStep.Quiz,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        touch: Boolean,
        pointerX: Float,
        pointerY: Float,
        selectedIds: Set<String>,
        selectedForBin: String?,
        binByOption: Map<String, String>,
        markOf: (optionId: String) -> QuizOptionMark?,
        hits: QuizHitRegistrar,
        alpha: Float = 1f,
    ) {
        val n = q.options.size
        if (n == 0) return
        val tileOnly = q.options.all { it.tileType != null && it.labelKey == null }
        val gap = Fonts.px(if (touch) 12f else 10f)

        if (tileOnly) {
            drawTileHand(
                batch, drawer, q, left, bottom, areaW, areaH, touch, gap,
                pointerX, pointerY, selectedIds, selectedForBin, binByOption, markOf, hits,
            )
        } else {
            drawLabeledGrid(
                batch, px, drawer, q, left, bottom, areaW, areaH, touch, gap,
                pointerX, pointerY, selectedIds, selectedForBin, binByOption, markOf, hits, alpha,
            )
        }
    }

    private fun drawTileHand(
        batch: SpriteBatch,
        drawer: TileDrawer,
        q: TutorialStep.Quiz,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        touch: Boolean,
        gap: Float,
        pointerX: Float,
        pointerY: Float,
        selectedIds: Set<String>,
        selectedForBin: String?,
        binByOption: Map<String, String>,
        markOf: (optionId: String) -> QuizOptionMark?,
        hits: QuizHitRegistrar,
    ) {
        val n = q.options.size
        // Cap by a fixed px budget first; only shrink with areaH when the
        // zone is actually tight — avoids Confirm chrome nudging tile size.
        val pxCap = Fonts.px(if (touch) 96f else 84f)
        val maxTileH = min(pxCap, areaH * 0.85f)
        val maxTileW = maxTileH * 0.72f
        // Prefer a single row when it fits; otherwise wrap.
        var tileW = maxTileW
        var tileH = maxTileH
        var perRow = max(1, ((areaW + gap) / (tileW + gap)).toInt())
        if (perRow < n) {
            val fitW = (areaW - gap * (n - 1)) / n
            if (fitW >= Fonts.px(36f)) {
                tileW = min(maxTileW, fitW)
                tileH = tileW / 0.72f
                perRow = n
            } else {
                perRow = max(1, ((areaW + gap) / (Fonts.px(40f) + gap)).toInt())
                tileW = min(maxTileW, (areaW - gap * (perRow - 1)) / perRow)
                tileH = tileW / 0.72f
            }
        }
        // Anchor from the top of the options zone (prompt side), not vertical center.
        val topPad = Fonts.px(if (touch) 10f else 8f)
        var y = bottom + areaH - topPad - tileH
        var i = 0
        while (i < n) {
            val rowCount = min(perRow, n - i)
            val rowW = rowCount * tileW + (rowCount - 1) * gap
            var x = left + (areaW - rowW) * 0.5f
            for (j in 0 until rowCount) {
                val opt = q.options[i + j]
                hits.add(QuizControlKind.Option, opt.id, x, y, tileW, tileH)
                val selected = selectedIds.contains(opt.id) || selectedForBin == opt.id
                val assigned = binByOption[opt.id]
                val mark = markOf(opt.id)
                drawTileOption(
                    batch, drawer, opt, x, y, tileW, tileH, selected, assigned, q.bins,
                    pointerX, pointerY, mark = mark,
                )
                x += tileW + gap
            }
            y -= tileH + gap
            i += rowCount
        }
    }

    private fun drawLabeledGrid(
        batch: SpriteBatch,
        px: Texture,
        drawer: TileDrawer,
        q: TutorialStep.Quiz,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        touch: Boolean,
        gap: Float,
        pointerX: Float,
        pointerY: Float,
        selectedIds: Set<String>,
        selectedForBin: String?,
        binByOption: Map<String, String>,
        markOf: (optionId: String) -> QuizOptionMark?,
        hits: QuizHitRegistrar,
        alpha: Float,
    ) {
        val n = q.options.size
        val cols = when {
            n <= 2 -> 1
            n <= 4 -> 2
            else -> if (areaW > areaH * 1.2f) 3 else 2
        }
        val rows = (n + cols - 1) / cols
        val cellW = (areaW - gap * (cols - 1)) / cols
        val idealH = max(Fonts.px(if (touch) 56f else 48f), Fonts.button.lineHeight + Fonts.px(22f))
        val cellH = min(idealH, (areaH - gap * (rows - 1)) / rows).coerceAtLeast(Fonts.px(40f))
        val blockH = rows * cellH + (rows - 1) * gap
        val blockW = cols * cellW + (cols - 1) * gap
        val originX = left + (areaW - blockW) * 0.5f
        var y = bottom + (areaH - blockH) * 0.5f + blockH - cellH
        var i = 0
        while (i < n) {
            val rowCount = min(cols, n - i)
            val rowW = rowCount * cellW + (rowCount - 1) * gap
            var x = originX + (blockW - rowW) * 0.5f
            for (j in 0 until rowCount) {
                val opt = q.options[i + j]
                hits.add(QuizControlKind.Option, opt.id, x, y, cellW, cellH)
                val selected = selectedIds.contains(opt.id) || selectedForBin == opt.id
                val mark = markOf(opt.id)
                val label = buildOptionLabel(opt, q, binByOption)
                TableChip.draw(
                    batch, px, label, x, y, cellW, cellH,
                    pointerX, pointerY,
                    tone = when {
                        mark != null && mark != QuizOptionMark.Idle -> TableChip.Tone.Marked(mark)
                        selected -> TableChip.Tone.Emphasized
                        else -> TableChip.Tone.Idle
                    },
                    alpha = alpha,
                )
                opt.tileType?.let { type ->
                    val th = cellH * 0.72f
                    val tw = th * 0.72f
                    drawer.drawOpen(
                        batch, type,
                        x + Fonts.px(10f) + tw * 0.5f,
                        y + cellH * 0.5f,
                        tw, th,
                        label = TileLabels.tagOrNull(type),
                    )
                }
                x += cellW + gap
            }
            y -= cellH + gap
            i += rowCount
        }
    }

    private fun buildOptionLabel(
        opt: QuizOption,
        q: TutorialStep.Quiz,
        binByOption: Map<String, String>,
    ): String {
        val base = opt.labelKey?.let { L10n.get(it) } ?: opt.id
        if (q.kind == QuizKind.BinAssign) {
            val binId = binByOption[opt.id] ?: return base
            val bin = q.bins.firstOrNull { it.id == binId } ?: return base
            return "$base → ${L10n.get(bin.labelKey)}"
        }
        return base
    }

    private fun drawTileOption(
        batch: SpriteBatch,
        drawer: TileDrawer,
        opt: QuizOption,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        selected: Boolean,
        assignedBin: String?,
        bins: List<QuizBin>,
        pointerX: Float,
        pointerY: Float,
        mark: QuizOptionMark? = null,
    ) {
        val type = opt.tileType ?: return
        val hovered = !pointerX.isNaN() && !pointerY.isNaN() &&
            pointerX >= left && pointerX <= left + w &&
            pointerY >= bottom && pointerY <= bottom + h
        val glow = QuizMarks.glowInk(mark)
            ?: when {
                selected -> AppPalette.tint(AppPalette.accent, 0.90f)
                hovered -> AppPalette.tint(AppPalette.accent, 0.60f)
                else -> null
            }
        drawer.drawOpen(
            batch, type, left + w * 0.5f, bottom + h * 0.5f, w * 0.90f, h * 0.90f,
            label = TileLabels.tagOrNull(type),
        )
        if (glow != null) {
            drawer.drawGlowOutline(
                batch,
                left + w * 0.5f,
                bottom + h * 0.5f,
                w, h,
                color = glow,
            )
        }
        if (assignedBin != null) {
            val binLabel = bins.firstOrNull { it.id == assignedBin }?.labelKey?.let { L10n.get(it) }
                ?: assignedBin
            val font = Fonts.small
            glyphLayout.setText(font, binLabel)
            font.color = QuizMarks.glowInk(mark) ?: AppPalette.accent
            font.draw(
                batch, binLabel,
                left + (w - glyphLayout.width) * 0.5f,
                bottom + Fonts.px(12f),
            )
            font.color = Color.WHITE
        }
    }
}
