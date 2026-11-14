package inego.zungjunggame.tutorial.slide.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Shared geometry for teaching tile diagrams (fit-first, no overflow).
 *
 * Callers supply measured label widths at a candidate font scale; planners
 * may request a lower [labelScale] so type and tiles share the rect cleanly.
 */
object DiagramLayout {

    /** Open-tile width / height (matches [inego.zungjunggame.render.tiles.TileDrawer] faces). */
    const val TILE_ASPECT: Float = 0.72f

    /** Soft floor for readability — never forced if it would overflow the rect. */
    const val MIN_READABLE_TILE_H_DESIGN: Float = 22f

    enum class LabelMode {
        /** Group title left of the tile block, vertically centered on the row. */
        Beside,
        /** Group title above the tile block (narrow / compact viewports). */
        Above,
    }

    data class TileRect(
        val type: Int,
        val left: Float,
        val bottom: Float,
        val w: Float,
        val h: Float,
    ) {
        val cx: Float get() = left + w * 0.5f
        val cy: Float get() = bottom + h * 0.5f
    }

    data class LabelSpec(
        val text: String,
        val x: Float,
        /** BitmapFont baseline y. */
        val baselineY: Float,
        val scale: Float,
        val alignCenter: Boolean = false,
        /** Optional max width for wrapping (0 = single line measure). */
        val maxWidth: Float = 0f,
    )

    // ── Tile size solving ──────────────────────────────────────────────────

    /**
     * Largest tile height that fits [cols]×[rows] into [areaW]×[areaH] with gaps.
     * Never exceeds the rect (no min coerce that overflows).
     */
    fun fitTileHeight(
        areaW: Float,
        areaH: Float,
        cols: Int,
        rows: Int,
        gapX: Float,
        gapY: Float,
        aspect: Float = TILE_ASPECT,
    ): Float {
        require(cols >= 1 && rows >= 1)
        val gapXTotal = gapX * (cols - 1).coerceAtLeast(0)
        val gapYTotal = gapY * (rows - 1).coerceAtLeast(0)
        val fromW = if (areaW > gapXTotal) (areaW - gapXTotal) / cols / aspect else 0f
        val fromH = if (areaH > gapYTotal) (areaH - gapYTotal) / rows else 0f
        return min(fromW, fromH).coerceAtLeast(0f)
    }

    fun tileWidth(tileH: Float, aspect: Float = TILE_ASPECT): Float = tileH * aspect

    /**
     * Prefer [LabelMode.Beside] when a side column still leaves room for
     * readable tiles; otherwise stack labels above rows.
     */
    fun chooseLabelMode(
        vp: SlideViewport,
        areaW: Float,
        maxLabelW: Float,
        tileCols: Int,
        minTileHDesired: Float,
    ): LabelMode {
        if (vp.isCompact && vp.isPortrait) return LabelMode.Above
        val gap = vp.px(if (vp.touch) 6f else 8f)
        val labelToTiles = vp.px(if (vp.touch) 10f else 14f)
        val side = maxLabelW + labelToTiles
        // Cap side column so tiles keep most of the width.
        val sideCap = areaW * if (vp.isPortrait) 0.30f else 0.26f
        val sideUsed = min(side, sideCap)
        val tileAreaW = (areaW - sideUsed).coerceAtLeast(0f)
        val tileH = fitTileHeight(tileAreaW, vp.px(80f), tileCols, 1, gap, 0f)
        return if (tileH >= minTileHDesired * 0.85f && sideUsed < areaW * 0.34f) {
            LabelMode.Beside
        } else {
            LabelMode.Above
        }
    }

    fun defaultTileGap(vp: SlideViewport): Float =
        vp.px(
            when {
                vp.isCompact && vp.touch -> 4f
                vp.touch -> 6f
                vp.isCompact -> 5f
                else -> 8f
            },
        )

    fun defaultRowGap(vp: SlideViewport): Float =
        vp.px(
            when {
                vp.isCompact && vp.touch -> 6f
                vp.touch -> 8f
                vp.isCompact -> 8f
                else -> 12f
            },
        )

    /**
     * Label / caption scale relative to base face — **≤ 1** on compact, mild grow
     * only when comfortable. Replaces the old fixed 1.15 / 1.40 multipliers.
     */
    fun labelScaleMax(vp: SlideViewport): Float = when (vp.sizeClass) {
        SlideSizeClass.Compact -> if (vp.touch) 0.92f else 0.95f
        SlideSizeClass.Regular -> 1f
        SlideSizeClass.Comfortable -> if (vp.isLandscape) 1.08f else 1.02f
    }

    fun captionScaleMax(vp: SlideViewport): Float =
        labelScaleMax(vp) * if (vp.isCompact) 0.82f else 0.88f

    /**
     * Floor for a caption fitted to the slot under its group.
     *
     * A caption is centered under its group and owns the group's width plus the
     * gap on either side. When the widest caption still does not fit, the plan
     * shrinks the caption face — never the tiles — down to this floor. Below it
     * the caption would stop being readable, so a pathological string keeps the
     * floor size and may touch its neighbour.
     */
    private const val CAPTION_FIT_FLOOR = 0.55f

    // ── Three suits (1–9 × Characters / Dots / Bamboos) ────────────────────

    data class ThreeSuitsPlan(
        val tiles: List<TileRect>,
        val suitLabels: List<LabelSpec>,
        /** Rank numeral under each tile (same order as [tiles]: 3×9). */
        val rankLabels: List<LabelSpec>,
        val labelScale: Float,
        val rankScale: Float,
    )

    /**
     * @param suitLabelTexts localized names for the three suits (top → bottom)
     * @param measureLabelW width of [text] at [scale] (single line)
     * @param labelLineHeight base line height of the label face at scale 1
     * @param firstTypes tile type of rank-1 for each suit row
     */
    fun planThreeSuits(
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        suitLabelTexts: List<String>,
        firstTypes: List<Int>,
        measureLabelW: (text: String, scale: Float) -> Float,
        labelLineHeight: Float,
    ): ThreeSuitsPlan {
        require(suitLabelTexts.size == 3 && firstTypes.size == 3)
        val nSuits = 3
        val count = 9
        val tileGap = defaultTileGap(vp)
        val rowGap = defaultRowGap(vp)

        var labelScale = labelScaleMax(vp)

        fun maxLabelW(scale: Float): Float {
            var m = 0f
            for (t in suitLabelTexts) m = max(m, measureLabelW(t, scale))
            return m
        }

        var mode = chooseLabelMode(
            vp = vp,
            areaW = areaW,
            maxLabelW = maxLabelW(labelScale),
            tileCols = count,
            minTileHDesired = vp.px(MIN_READABLE_TILE_H_DESIGN),
        )

        // Per-tile rank subscript under every face (no shared 1..9 strip).
        fun rankBandFor(scale: Float): Float {
            val rankScale = scale * 0.88f
            return max(
                labelLineHeight * rankScale * 1.12f,
                vp.px(if (vp.isCompact) 12f else 16f),
            )
        }

        // Snug gap between the right edge of a suit name and the first tile.
        fun labelToTilesGap(): Float = vp.px(if (vp.touch) 8f else 10f)

        fun layoutWith(scale: Float, labelMode: LabelMode): Triple<Float, Float, Float> {
            // returns tileH, sideNeed (label gutter reserved for fit), aboveBand
            val labelW = maxLabelW(scale)
            val labelToTiles = labelToTilesGap()
            val sideNeed = when (labelMode) {
                // Reserve only what labels actually need — not a wide % gutter.
                LabelMode.Beside -> (labelW + labelToTiles).coerceAtMost(areaW * 0.34f)
                LabelMode.Above -> 0f
            }
            val aboveBand = when (labelMode) {
                LabelMode.Above -> max(labelLineHeight * scale * 1.1f, vp.px(14f))
                LabelMode.Beside -> 0f
            }
            val rankBandH = rankBandFor(scale)
            val tileAreaW = (areaW - sideNeed).coerceAtLeast(0f)
            // Each suit row = optional above-label + tile + rank subscript.
            val perRowChrome = aboveBand + rankBandH
            val gapsY = rowGap * (nSuits - 1)
            val tileAreaH = (areaH - gapsY - perRowChrome * nSuits).coerceAtLeast(0f)
            val tileH = fitTileHeight(tileAreaW, tileAreaH, count, nSuits, tileGap, 0f)
            return Triple(tileH, sideNeed, aboveBand)
        }

        var (tileH, sideNeed, aboveBand) = layoutWith(labelScale, mode)
        // If tiles are tiny, or side labels cannot fit in the reserved gutter, stack above.
        if (mode == LabelMode.Beside) {
            val labelsFit = maxLabelW(labelScale) + labelToTilesGap() <= sideNeed + 0.5f
            if (tileH < vp.px(MIN_READABLE_TILE_H_DESIGN) || !labelsFit) {
                mode = LabelMode.Above
                val relayout = layoutWith(labelScale, mode)
                tileH = relayout.first
                sideNeed = relayout.second
                aboveBand = relayout.third
            }
        }
        var guard = 0
        while (guard++ < 8 && tileH < vp.px(18f) && labelScale > 0.70f) {
            labelScale = max(0.70f, labelScale - 0.05f)
            val relayout = layoutWith(labelScale, mode)
            tileH = relayout.first
            sideNeed = relayout.second
            aboveBand = relayout.third
        }

        val rankScale = labelScale * 0.88f
        val rankBandH = rankBandFor(labelScale)
        val tileW = tileWidth(tileH)
        val rowW = count * tileW + (count - 1) * tileGap
        val labelToTiles = labelToTilesGap()
        // Center the (labels + tiles) unit as a whole so labels stay snug to tiles.
        // Old layout centered only the tile row inside areaW−sideCol, which left a
        // large empty band between right-aligned names and the first tile.
        val tilesLeft = if (mode == LabelMode.Beside) {
            val blockW = sideNeed + rowW
            val blockLeft = left + max(0f, (areaW - blockW) * 0.5f)
            blockLeft + sideNeed
        } else {
            left + max(0f, (areaW - rowW) * 0.5f)
        }
        val rowH = aboveBand + tileH + rankBandH

        val tiles = ArrayList<TileRect>(27)
        val suitLabels = ArrayList<LabelSpec>(3)
        // One numeral under each tile: 3 suits × 9 ranks.
        val rankLabels = ArrayList<LabelSpec>(27)

        // Stack from top: optional above-label + tiles + rank subscripts per suit.
        var yTop = bottom + areaH
        for (i in 0 until nSuits) {
            val rowBottom = yTop - rowH
            if (mode == LabelMode.Above && aboveBand > 0f) {
                val text = suitLabelTexts[i]
                val tw = measureLabelW(text, labelScale)
                val baseline = yTop - aboveBand * 0.5f + labelLineHeight * labelScale * 0.35f
                suitLabels += LabelSpec(
                    text = text,
                    x = tilesLeft + (rowW - tw) * 0.5f,
                    baselineY = baseline,
                    scale = labelScale,
                )
            }
            if (mode == LabelMode.Beside) {
                val text = suitLabelTexts[i]
                val tw = measureLabelW(text, labelScale)
                // Right-align against the first tile of this row (tight gap).
                val labelX = tilesLeft - labelToTiles - tw
                // Vertically center on the tile band (above the rank subscripts).
                val tileMidY = rowBottom + rankBandH + tileH * 0.5f
                val baseline = tileMidY + labelLineHeight * labelScale * 0.35f
                suitLabels += LabelSpec(
                    text = text,
                    x = labelX.coerceAtLeast(left),
                    baselineY = baseline,
                    scale = labelScale,
                )
            }
            val tileBottom = rowBottom + rankBandH
            val drawH = tileH * 0.92f
            val drawW = tileW * 0.92f
            var x = tilesLeft
            for (r in 0 until count) {
                val rank = r + 1
                val type = firstTypes[i] + r
                tiles += TileRect(
                    type = type,
                    left = x + (tileW - drawW) * 0.5f,
                    bottom = tileBottom + (tileH - drawH) * 0.5f,
                    w = drawW,
                    h = drawH,
                )
                val rankText = rank.toString()
                val tw = measureLabelW(rankText, rankScale)
                rankLabels += LabelSpec(
                    text = rankText,
                    x = x + (tileW - tw) * 0.5f,
                    baselineY = rowBottom + rankBandH * 0.72f,
                    scale = rankScale,
                )
                x += tileW + tileGap
            }
            yTop = rowBottom - rowGap
        }

        return ThreeSuitsPlan(
            tiles = tiles,
            suitLabels = suitLabels,
            rankLabels = rankLabels,
            labelScale = labelScale,
            rankScale = rankScale,
        )
    }

    // ── Honors (winds + dragons with per-tile names) ───────────────────────

    data class HonorRowModel(
        val groupLabel: String,
        val tiles: List<Pair<Int, String>>, // type to localized name
    )

    data class HonorsPlan(
        val tiles: List<TileRect>,
        val groupLabels: List<LabelSpec>,
        val nameLabels: List<LabelSpec>,
        val groupLabelScale: Float,
        val nameScale: Float,
    )

    fun planHonors(
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        rows: List<HonorRowModel>,
        measureLabelW: (text: String, scale: Float) -> Float,
        labelLineHeight: Float,
    ): HonorsPlan {
        require(rows.isNotEmpty())
        val nRows = rows.size
        val maxCount = rows.maxOf { it.tiles.size }
        val tileGap = defaultTileGap(vp) * 1.1f
        val rowGap = defaultRowGap(vp)

        var groupScale = labelScaleMax(vp)
        var nameScale = captionScaleMax(vp)

        fun maxGroupW(scale: Float): Float {
            var m = 0f
            for ((groupLabel) in rows) m = max(m, measureLabelW(groupLabel, scale))
            return m
        }

        var mode = chooseLabelMode(
            vp = vp,
            areaW = areaW,
            maxLabelW = maxGroupW(groupScale),
            tileCols = maxCount,
            minTileHDesired = vp.px(MIN_READABLE_TILE_H_DESIGN + 4f),
        )

        fun nameBandFor(scale: Float): Float =
            max(labelLineHeight * scale * 1.15f, vp.px(if (vp.isCompact) 14f else 18f))

        fun labelToTilesGap(): Float = vp.px(if (vp.touch) 8f else 10f)

        fun layout(gScale: Float, nScale: Float, labelMode: LabelMode): Pair<Float, Float> {
            val labelToTiles = labelToTilesGap()
            val sideNeed = when (labelMode) {
                LabelMode.Beside -> (maxGroupW(gScale) + labelToTiles).coerceAtMost(areaW * 0.34f)
                LabelMode.Above -> 0f
            }
            val aboveBand = when (labelMode) {
                LabelMode.Above -> max(labelLineHeight * gScale * 1.1f, vp.px(14f))
                LabelMode.Beside -> 0f
            }
            val nameBand = nameBandFor(nScale)
            val tileAreaW = (areaW - sideNeed).coerceAtLeast(0f)
            val perRowChrome = nameBand + aboveBand
            val tileAreaH = (areaH - rowGap * (nRows - 1) - perRowChrome * nRows)
                .coerceAtLeast(0f)
            val tileH = fitTileHeight(tileAreaW, tileAreaH, maxCount, nRows, tileGap, 0f)
            return tileH to sideNeed
        }

        var (tileH, sideNeed) = layout(groupScale, nameScale, mode)
        if (mode == LabelMode.Beside) {
            val labelsFit = maxGroupW(groupScale) + labelToTilesGap() <= sideNeed + 0.5f
            if (tileH < vp.px(MIN_READABLE_TILE_H_DESIGN) || !labelsFit) {
                mode = LabelMode.Above
                val rel = layout(groupScale, nameScale, mode)
                tileH = rel.first
                sideNeed = rel.second
            }
        }
        var guard = 0
        while (guard++ < 10 && tileH < vp.px(18f) && (groupScale > 0.70f || nameScale > 0.65f)) {
            groupScale = max(0.70f, groupScale - 0.05f)
            nameScale = max(0.65f, nameScale - 0.05f)
            val rel = layout(groupScale, nameScale, mode)
            tileH = rel.first
            sideNeed = rel.second
        }

        // Shrink name labels so each caption fits under its tile width.
        val tileW = tileWidth(tileH)
        fun maxNameW(scale: Float): Float {
            var m = 0f
            for ((_, tiles) in rows) for ((_, name) in tiles) m = max(m, measureLabelW(name, scale))
            return m
        }
        guard = 0
        while (guard++ < 10 && maxNameW(nameScale) > tileW * 1.05f && nameScale > 0.60f) {
            nameScale = max(0.60f, nameScale - 0.04f)
        }
        // Recompute tileH after name band change (name scale affects band height).
        run {
            val rel = layout(groupScale, nameScale, mode)
            tileH = rel.first
            sideNeed = rel.second
        }
        val finalTileW = tileWidth(tileH)
        val nameBandH = nameBandFor(nameScale)
        val aboveBand = when (mode) {
            LabelMode.Above -> max(labelLineHeight * groupScale * 1.1f, vp.px(14f))
            LabelMode.Beside -> 0f
        }
        val rowH = tileH + nameBandH + aboveBand
        val labelToTiles = labelToTilesGap()

        val maxRowW = maxCount * finalTileW + (maxCount - 1) * tileGap
        // Center (labels + widest tile row) as one unit so names stay snug to tiles.
        val tilesLeftShared = if (mode == LabelMode.Beside) {
            val blockW = sideNeed + maxRowW
            val blockLeft = left + max(0f, (areaW - blockW) * 0.5f)
            blockLeft + sideNeed
        } else {
            left + max(0f, (areaW - maxRowW) * 0.5f)
        }

        val tiles = ArrayList<TileRect>(8)
        val groupLabels = ArrayList<LabelSpec>(nRows)
        val nameLabels = ArrayList<LabelSpec>(8)

        var yTop = bottom + areaH
        for ((groupLabel, tiles1) in rows) {
            val rowBottom = yTop - rowH
            val rowW = tiles1.size * finalTileW + (tiles1.size - 1).coerceAtLeast(0) * tileGap
            val rowTilesLeft = tilesLeftShared + max(0f, (maxRowW - rowW) * 0.5f)

            when (mode) {
                LabelMode.Above -> {
                    val tw = measureLabelW(groupLabel, groupScale)
                    groupLabels += LabelSpec(
                        text = groupLabel,
                        x = rowTilesLeft + (rowW - tw) * 0.5f,
                        baselineY = yTop - aboveBand * 0.5f + labelLineHeight * groupScale * 0.35f,
                        scale = groupScale,
                    )
                }
                LabelMode.Beside -> {
                    val tw = measureLabelW(groupLabel, groupScale)
                    groupLabels += LabelSpec(
                        text = groupLabel,
                        x = (rowTilesLeft - labelToTiles - tw).coerceAtLeast(left),
                        baselineY = rowBottom + nameBandH + tileH * 0.5f +
                            labelLineHeight * groupScale * 0.35f,
                        scale = groupScale,
                    )
                }
            }

            val tileBottom = rowBottom + nameBandH
            val drawH = tileH * 0.92f
            val drawW = finalTileW * 0.92f
            var x = rowTilesLeft
            for ((type, name) in tiles1) {
                tiles += TileRect(
                    type = type,
                    left = x + (finalTileW - drawW) * 0.5f,
                    bottom = tileBottom + (tileH - drawH) * 0.5f,
                    w = drawW,
                    h = drawH,
                )
                val nw = measureLabelW(name, nameScale)
                nameLabels += LabelSpec(
                    text = name,
                    x = x + (finalTileW - nw) * 0.5f,
                    baselineY = rowBottom + nameBandH * 0.72f,
                    scale = nameScale,
                )
                x += finalTileW + tileGap
            }
            yTop = rowBottom - rowGap
        }

        return HonorsPlan(
            tiles = tiles,
            groupLabels = groupLabels,
            nameLabels = nameLabels,
            groupLabelScale = groupScale,
            nameScale = nameScale,
        )
    }

    // ── Generic labeled tile rows (TileTypes, Chow, Not-a-Chow, …) ─────────

    data class TileGroupModel(
        val tiles: List<Int>,
        val caption: String? = null,
    )

    data class LabeledRowModel(
        val label: String,
        val groups: List<TileGroupModel>,
    ) {
        val tileCount: Int get() = groups.sumOf { it.tiles.size }
        val groupCount: Int get() = groups.size
        val hasCaptions: Boolean get() = groups.any { it.caption != null }
    }

    data class LabeledRowsPlan(
        val tiles: List<TileRect>,
        val rowLabels: List<LabelSpec>,
        val captions: List<LabelSpec>,
        val labelScale: Float,
        val captionScale: Float,
    )

    fun planLabeledRows(
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        rows: List<LabeledRowModel>,
        measureLabelW: (text: String, scale: Float) -> Float,
        labelLineHeight: Float,
        extraGapAfterRow: Int = -1,
        extraGap: Float = 0f,
    ): LabeledRowsPlan {
        require(rows.isNotEmpty())
        val nRows = rows.size
        val extra = if (extraGapAfterRow in 0 until nRows - 1) extraGap.coerceAtLeast(0f) else 0f
        val maxTiles = rows.maxOf { it.tileCount }
        val maxGroups = rows.maxOf { it.groupCount }
        // Multi-group rows are sets/melds: hairline within a group, groupGap
        // between groups. Single-group rows keep the default tile gap.
        val multiGroup = rows.any { it.groupCount > 1 }
        val tileGap = if (multiGroup) vp.px(1f) else defaultTileGap(vp)
        val rowGap = defaultRowGap(vp)
        val groupGapSlots = (maxGroups - 1).coerceAtLeast(0)

        var labelScale = labelScaleMax(vp)
        var captionScale = captionScaleMax(vp)

        fun maxLabelW(scale: Float): Float {
            var m = 0f
            for ((label) in rows) {
                if (label.isNotBlank()) m = max(m, measureLabelW(label, scale))
            }
            return m
        }

        val anyRowLabels = rows.any { it.label.isNotBlank() }

        var mode = if (!anyRowLabels) {
            // Unlabeled rows (group captions only) — no side/above chrome.
            LabelMode.Beside
        } else {
            chooseLabelMode(
                vp = vp,
                areaW = areaW,
                maxLabelW = maxLabelW(labelScale),
                tileCols = maxTiles,
                minTileHDesired = vp.px(MIN_READABLE_TILE_H_DESIGN + 2f),
            )
        }

        val anyCaptions = rows.any { it.hasCaptions }
        fun captionBand(scale: Float): Float =
            if (!anyCaptions) 0f
            else max(labelLineHeight * scale * 1.1f, vp.px(if (vp.isCompact) 12f else 16f))

        fun labelToTilesGap(): Float = vp.px(if (vp.touch) 8f else 10f)

        fun layout(lScale: Float, cScale: Float, labelMode: LabelMode): Pair<Float, Float> {
            val labelToTiles = labelToTilesGap()
            val sideNeed = when {
                !anyRowLabels -> 0f
                labelMode == LabelMode.Beside ->
                    (maxLabelW(lScale) + labelToTiles).coerceAtMost(areaW * 0.34f)
                else -> 0f
            }
            val aboveBand = when {
                !anyRowLabels -> 0f
                labelMode == LabelMode.Above ->
                    max(labelLineHeight * lScale * 1.1f, vp.px(14f))
                else -> 0f
            }
            val capH = captionBand(cScale)
            val tileAreaW = (areaW - sideNeed).coerceAtLeast(0f)
            // Group gap ≈ 0.3 tile width (see groupGap below) — keep fit in sync.
            val fitUnits = maxTiles + groupGapSlots * 0.30f
            val fromW = if (tileAreaW > 0f && fitUnits > 0f) {
                (tileAreaW - tileGap * (maxTiles - 1).coerceAtLeast(0)) / fitUnits / TILE_ASPECT
            } else {
                0f
            }
            val rowChrome = capH + aboveBand
            val tileBandTotal = (areaH - rowGap * (nRows - 1) - extra - rowChrome * nRows)
                .coerceAtLeast(0f)
            val fromH = tileBandTotal / nRows
            val tileH = min(fromW, fromH).coerceAtLeast(0f)
            return tileH to sideNeed
        }

        var (tileH, sideNeed) = layout(labelScale, captionScale, mode)
        if (anyRowLabels && mode == LabelMode.Beside) {
            val labelsFit = maxLabelW(labelScale) + labelToTilesGap() <= sideNeed + 0.5f
            if (tileH < vp.px(MIN_READABLE_TILE_H_DESIGN) || !labelsFit) {
                mode = LabelMode.Above
                val rel = layout(labelScale, captionScale, mode)
                tileH = rel.first
                sideNeed = rel.second
            }
        }
        var guard = 0
        while (guard++ < 10 && tileH < vp.px(18f) && labelScale > 0.70f) {
            labelScale = max(0.70f, labelScale - 0.05f)
            captionScale = max(0.62f, captionScale - 0.05f)
            val rel = layout(labelScale, captionScale, mode)
            tileH = rel.first
            sideNeed = rel.second
        }

        var tileW = tileWidth(tileH)
        // Modest separation between sets — not a near-tile-wide gutter.
        var groupGap = max(tileW * 0.30f, vp.px(if (vp.touch) 8f else 10f))

        /** Width of one group's tiles at [width] per tile. */
        fun groupWidth(group: TileGroupModel, width: Float): Float =
            group.tiles.size * width +
                (group.tiles.size - 1).coerceAtLeast(0) * tileGap

        // A caption is centered under its group and owns the group's width plus
        // the gap on either side — its slot. Two full-width captions in
        // adjacent groups then meet in the middle of the gap instead of running
        // into each other. One caption scale serves the whole plan, solved from
        // the tightest slot, so every caption keeps the same size; only the
        // caption face gives, never the tiles or the row's geometry.
        if (anyCaptions) {
            var fit = 1f
            for (row in rows) {
                for (group in row.groups) {
                    val caption = group.caption ?: continue
                    val captionW = measureLabelW(caption, captionScale)
                    if (captionW <= 0.5f) continue
                    val slotW = groupWidth(group, tileW) + groupGap
                    fit = min(fit, slotW / captionW)
                }
            }
            if (fit < 1f) {
                captionScale = (captionScale * fit).coerceAtLeast(
                    min(CAPTION_FIT_FLOOR, captionScale),
                )
                // The caption band just got shorter; give that height back to
                // the tiles. A wider tile only widens the slots, so the fit
                // above still holds.
                val rel = layout(labelScale, captionScale, mode)
                tileH = rel.first
                sideNeed = rel.second
                tileW = tileWidth(tileH)
                groupGap = max(tileW * 0.30f, vp.px(if (vp.touch) 8f else 10f))
            }
        }

        val nameBandH = captionBand(captionScale)
        val aboveBand = when {
            !anyRowLabels -> 0f
            mode == LabelMode.Above -> max(labelLineHeight * labelScale * 1.1f, vp.px(14f))
            else -> 0f
        }
        val rowH = tileH + nameBandH + aboveBand
        val labelToTiles = labelToTilesGap()

        fun widthOf(groups: List<TileGroupModel>): Float {
            var w = 0f
            for ((i, g) in groups.withIndex()) {
                if (i > 0) w += groupGap
                w += groupWidth(g, tileW)
            }
            return w
        }

        val maxRowW = rows.maxOf { widthOf(it.groups) }
        val tilesLeftShared = if (mode == LabelMode.Beside && sideNeed > 0f) {
            val blockW = sideNeed + maxRowW
            val blockLeft = left + max(0f, (areaW - blockW) * 0.5f)
            blockLeft + sideNeed
        } else {
            left + max(0f, (areaW - maxRowW) * 0.5f)
        }

        val tiles = ArrayList<TileRect>(16)
        val rowLabels = ArrayList<LabelSpec>(nRows)
        val captions = ArrayList<LabelSpec>(8)

        var yTop = bottom + areaH
        for ((ri, row) in rows.withIndex()) {
            val rowBottom = yTop - rowH
            val rowW = widthOf(row.groups)
            val rowTilesLeft = tilesLeftShared + max(0f, (maxRowW - rowW) * 0.5f)

            if (anyRowLabels && row.label.isNotBlank()) {
                when (mode) {
                    LabelMode.Above -> {
                        val tw = measureLabelW(row.label, labelScale)
                        rowLabels += LabelSpec(
                            text = row.label,
                            x = rowTilesLeft + (rowW - tw) * 0.5f,
                            baselineY = yTop - aboveBand * 0.5f + labelLineHeight * labelScale * 0.35f,
                            scale = labelScale,
                        )
                    }
                    LabelMode.Beside -> {
                        val tw = measureLabelW(row.label, labelScale)
                        rowLabels += LabelSpec(
                            text = row.label,
                            x = (rowTilesLeft - labelToTiles - tw).coerceAtLeast(left),
                            baselineY = rowBottom + nameBandH + tileH * 0.5f +
                                labelLineHeight * labelScale * 0.35f,
                            scale = labelScale,
                        )
                    }
                }
            }

            val tileBottom = rowBottom + nameBandH
            // Multi-group melds draw full cell so the hairline tileGap is the only
            // separation; single-group rows keep a slight inset for breathing room.
            val faceScale = if (multiGroup) 1f else 0.92f
            val drawH = tileH * faceScale
            val drawW = tileW * faceScale
            var x = rowTilesLeft
            for ((gi, group) in row.groups.withIndex()) {
                if (gi > 0) x += groupGap
                val groupLeft = x
                for ((ti, type) in group.tiles.withIndex()) {
                    if (ti > 0) x += tileGap
                    tiles += TileRect(
                        type = type,
                        left = x + (tileW - drawW) * 0.5f,
                        bottom = tileBottom + (tileH - drawH) * 0.5f,
                        w = drawW,
                        h = drawH,
                    )
                    x += tileW
                }
                val groupW = group.tiles.size * tileW +
                    (group.tiles.size - 1).coerceAtLeast(0) * tileGap
                val cap = group.caption
                if (cap != null) {
                    val cw = measureLabelW(cap, captionScale)
                    captions += LabelSpec(
                        text = cap,
                        x = groupLeft + (groupW - cw) * 0.5f,
                        baselineY = rowBottom + nameBandH * 0.72f,
                        scale = captionScale,
                    )
                }
            }
            yTop = rowBottom - rowGap
            if (ri == extraGapAfterRow) yTop -= extra
        }

        return LabeledRowsPlan(
            tiles = tiles,
            rowLabels = rowLabels,
            captions = captions,
            labelScale = labelScale,
            captionScale = captionScale,
        )
    }

    // ── Not-a-sequence peel geometry ───────────────────────────────────────────

    data class PeelRowPlan(
        val tileRects: List<TileRect>,
        /** Spare caption + sequence caption when [peelT] is high enough to draw. */
        val spareCaption: LabelSpec?,
        val sequenceCaption: LabelSpec?,
        /** 0..1 peel progress used for caption fade (per row). */
        val peelT: Float,
    )

    data class NotASequencePeelsPlan(
        val rows: List<PeelRowPlan>,
        val captionScale: Float,
    )

    typealias NotAChowPeelsPlan = NotASequencePeelsPlan

    /**
     * @param phase 1 = peel 3 left only; 2 = settled peel-3 + peel 6 right
     * @param peelT 0..1 animation progress for the active peel
     */
    fun planNotASequencePeels(
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        phase: Int,
        peelT: Float,
        types: IntArray,
        spareCaption: String,
        sequenceCaption: String,
        measureLabelW: (text: String, scale: Float) -> Float,
        labelLineHeight: Float,
    ): NotASequencePeelsPlan {
        val nRows = if (phase >= 2) 2 else 1
        val tileGap = defaultTileGap(vp)
        val rowGap = defaultRowGap(vp)
        var captionScale = captionScaleMax(vp)
        fun capH(scale: Float) =
            max(labelLineHeight * scale * 1.1f, vp.px(if (vp.isCompact) 12f else 16f))

        // Content-sized rows (tile + caption snug under it). Do not stretch each
        // row to fill areaH — that pinned captions to the bottom of a tall
        // portrait diagram while width-limited tiles sat much higher.
        fun tileHFor(capH: Float): Float {
            val tileBandTotal =
                (areaH - capH * nRows - rowGap * (nRows - 1)).coerceAtLeast(0f)
            val tileBandPerRow = tileBandTotal / nRows
            val fitUnits = 4f + 0.95f
            val fromW =
                if (areaW > 0f) (areaW - tileGap * 3) / fitUnits / TILE_ASPECT else 0f
            return min(tileBandPerRow, fromW).coerceAtLeast(0f)
        }

        var captionH = capH(captionScale)
        var tileH = tileHFor(captionH)
        // Shrink captions if tiles are crushed.
        var guard = 0
        while (guard++ < 8 && tileH < vp.px(18f) && captionScale > 0.62f) {
            captionScale = max(0.62f, captionScale - 0.05f)
            captionH = capH(captionScale)
            tileH = tileHFor(captionH)
        }
        val tileW = tileWidth(tileH)
        val groupGap = max(tileW * 0.95f, vp.px(if (vp.touch) 22f else 28f))
        val settledW = 4f * tileW + 2f * tileGap + groupGap
        val tilesLeft = left + max(0f, (areaW - settledW) * 0.5f)
        val rowH = tileH + captionH
        val contentH = nRows * rowH + (nRows - 1) * rowGap
        // Vertically center the content block in the diagram rect.
        val yTop = bottom + areaH - max(0f, (areaH - contentH) * 0.5f)

        fun rowPlan(
            rowBottom: Float,
            peel: Float,
            spareLeft: Boolean,
        ): PeelRowPlan {
            val gap = tileGap + (groupGap - tileGap) * peel.coerceIn(0f, 1f)
            val xs = FloatArray(4)
            if (spareLeft) {
                xs[0] = tilesLeft
                xs[1] = tilesLeft + tileW + gap
                xs[2] = xs[1] + tileW + tileGap
                xs[3] = xs[2] + tileW + tileGap
            } else {
                xs[0] = tilesLeft
                xs[1] = xs[0] + tileW + tileGap
                xs[2] = xs[1] + tileW + tileGap
                xs[3] = xs[2] + tileW + gap
            }
            val tileBottom = rowBottom + captionH
            val drawW = tileW * 0.92f
            val drawH = tileH * 0.92f
            val rects = (0 until 4).map { i ->
                TileRect(
                    type = types[i],
                    left = xs[i] + (tileW - drawW) * 0.5f,
                    bottom = tileBottom + (tileH - drawH) * 0.5f,
                    w = drawW,
                    h = drawH,
                )
            }
            val showCap = peel > 0.35f
            val sequenceW = 3f * tileW + 2f * tileGap
            val baseline = rowBottom + captionH * 0.72f
            fun cap(text: String, groupLeft: Float, groupW: Float): LabelSpec {
                val tw = measureLabelW(text, captionScale)
                return LabelSpec(
                    text = text,
                    x = groupLeft + (groupW - tw) * 0.5f,
                    baselineY = baseline,
                    scale = captionScale,
                )
            }
            return if (!showCap) {
                PeelRowPlan(rects, null, null, peel)
            } else if (spareLeft) {
                PeelRowPlan(
                    tileRects = rects,
                    spareCaption = cap(spareCaption, xs[0], tileW),
                    sequenceCaption = cap(sequenceCaption, xs[1], sequenceW),
                    peelT = peel,
                )
            } else {
                PeelRowPlan(
                    tileRects = rects,
                    spareCaption = cap(spareCaption, xs[3], tileW),
                    sequenceCaption = cap(sequenceCaption, xs[0], sequenceW),
                    peelT = peel,
                )
            }
        }

        val out = ArrayList<PeelRowPlan>(2)
        var yCursor = yTop
        val topPeel = if (phase == 1) peelT else 1f
        out += rowPlan(yCursor - rowH, topPeel, spareLeft = true)
        yCursor -= rowH + rowGap
        if (phase >= 2) {
            out += rowPlan(yCursor - rowH, peelT, spareLeft = false)
        }
        return NotASequencePeelsPlan(out, captionScale)
    }

    @Deprecated("Renamed to planNotASequencePeels", ReplaceWith("planNotASequencePeels(vp, left, bottom, areaW, areaH, phase, peelT, types, spareCaption, chowCaption, measureLabelW, labelLineHeight)"))
    fun planNotAChowPeels(
        vp: SlideViewport,
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        phase: Int,
        peelT: Float,
        types: IntArray,
        spareCaption: String,
        chowCaption: String,
        measureLabelW: (text: String, scale: Float) -> Float,
        labelLineHeight: Float,
    ): NotAChowPeelsPlan = planNotASequencePeels(
        vp = vp,
        left = left,
        bottom = bottom,
        areaW = areaW,
        areaH = areaH,
        phase = phase,
        peelT = peelT,
        types = types,
        spareCaption = spareCaption,
        sequenceCaption = chowCaption,
        measureLabelW = measureLabelW,
        labelLineHeight = labelLineHeight,
    )
}
