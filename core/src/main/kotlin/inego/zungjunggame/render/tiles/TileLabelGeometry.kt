package inego.zungjunggame.render.tiles

import kotlin.math.min

/**
 * Where and how big a tile's label tag is, in the tile's local space.
 *
 * Pure arithmetic around three conventions, each of which has already been broken once
 * on screen — which is why all three are pinned by `TileLabelGeometryTest`:
 *
 * 1. **Scale with the tile.** The ink fills a fixed *fraction* of the face, so a bigger
 *    tile gets a bigger tag; an earlier version capped it at the font's rasterised size,
 *    leaving a constant pixel size that only ever shrank.
 * 2. **Stay modest, because it annotates.** A third of the tile's height read as a
 *    badge competing with the suit art.
 * 3. **Hand the font a top line, not a baseline.** `BitmapFont.draw`'s Y is the
 *    cap-height top, so [LabelPlacement.topY] goes to the font verbatim; subtracting
 *    the ink height from it sinks the tag a full line.
 *
 * A single face suffices: [inego.zungjunggame.render.fonts.Fonts.tileLabel] is rasterised without the drop shadow the
 * chrome faces bake (an outline needs shadow-free glyphs), and since the tag is only
 * ever a fraction of a tile it is drawn scaled to the tile it annotates.
 */
internal object TileLabelGeometry {
    /**
     * Share of the face the ink may take. Deliberately modest: the tag is a corner
     * mark, not a badge. These fractions keep it proportional to the tile while staying
     * clearly secondary to the face it annotates, and they are wide enough for the
     * two-character `Wh` to fit on its own axis.
     */
    const val MAX_WIDTH_FRACTION = 0.28f
    const val MAX_HEIGHT_FRACTION = 0.15f

    /** Corner inset, per axis, as a fraction of the face. */
    const val INSET_X_FRACTION = 0.09f
    const val INSET_Y_FRACTION = 0.07f

    /**
     * Below this rendered ink height a tag is noise rather than a label.
     *
     * Density-scaled by the caller, while the tag it judges is a fraction of a tile that is
     * itself a fraction of the screen's short side — so this constant alone decides whether
     * the feature is reachable on a phone. Ink is at most
     * `MAX_HEIGHT_FRACTION * handTileHeightFraction` of the short side, so a tag can only
     * appear when `shortSidePx >= 470.6 * MIN_INK_HEIGHT_DESIGN_PX * uiScale`. At 6f that
     * demanded a 1412px short side — more than a 1224x2700 @density 3.25 phone has, so
     * Android silently drew nothing while desktop (uiScale 1, dense fonts clamped to 1)
     * passed. 3f restores every phone with margin and leaves desktop unchanged: its ink
     * already cleared both values.
     */
    const val MIN_INK_HEIGHT_DESIGN_PX = 3f

    /**
     * Placement for a [tileW] x [tileH] face given the label's natural ink box on
     * [face], or null when nothing should be drawn: degenerate input, or so small a
     * tile that the ink would be shorter than [minInkHeight].
     */
    fun place(
        tileW: Float,
        tileH: Float,
        face: LabelFace,
        minInkHeight: Float,
    ): LabelPlacement? {
        if (tileW <= 0f || tileH <= 0f || face.inkW <= 0f || face.inkH <= 0f) return null
        val scale = min(
            tileW * MAX_WIDTH_FRACTION / face.inkW,
            tileH * MAX_HEIGHT_FRACTION / face.inkH,
        )
        if (scale <= 0f) return null

        val inkW = face.inkW * scale
        val inkH = face.inkH * scale
        if (inkH < minInkHeight) return null

        return LabelPlacement(
            leftX = -tileW * 0.5f + tileW * INSET_X_FRACTION,
            topY = tileH * 0.5f - tileH * INSET_Y_FRACTION,
            inkW = inkW,
            inkH = inkH,
            scale = scale,
        )
    }
}

/** A label's natural (resting-scale) ink box on the label face. */
internal data class LabelFace(val inkW: Float, val inkH: Float)

/**
 * A tag's placement in tile-local space (origin at the tile centre, y up).
 *
 * [topY] is a **cap-height top** — pass it to the font unchanged. [scale] is the factor
 * the label face is drawn at, so the ink measures exactly [inkW] x [inkH].
 */
internal data class LabelPlacement(
    val leftX: Float,
    val topY: Float,
    val inkW: Float,
    val inkH: Float,
    val scale: Float,
) {
    val rightX: Float get() = leftX + inkW
    val bottomY: Float get() = topY - inkH
}
