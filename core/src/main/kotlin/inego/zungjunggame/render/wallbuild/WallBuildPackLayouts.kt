package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.min

/**
 * Early wall-build pack layouts: 34 open faces (×4 stacked), open copies grid,
 * and face-down pack at fixed physical mm scale.
 *
 * Writes only target poses via [setTarget]; the view owns easing/current state.
 * Tile index is stable: `type * 4 + copy` (4 of each of 34 types).
 */
object WallBuildPackLayouts {

    /** Group lengths 9,9,9,7 — first types 0,9,18,27. */
    private val groupLens = intArrayOf(9, 9, 9, 7)
    private val groupFirst = intArrayOf(0, 9, 18, 27)

    /**
     * Apply a target pose for one tile. [rot] degrees; [open] face-up when true.
     */
    fun interface TargetSink {
        fun setTarget(
            tileIndex: Int,
            cx: Float,
            cy: Float,
            w: Float,
            h: Float,
            rot: Float,
            open: Boolean,
        )
    }

    /**
     * Four suit rows of open faces; all four copies of each type share one center.
     */
    fun layoutFaces(
        areaLeft: Float,
        areaBottom: Float,
        areaW: Float,
        areaH: Float,
        tileAspect: Float,
        setTarget: TargetSink,
    ) {
        val pad = Fonts.px(4f)
        val gapX = Fonts.px(3f)
        val gapY = Fonts.px(5f)
        val rows = 4
        val maxCols = 9
        val availW = areaW - pad * 2f
        val availH = areaH - pad * 2f
        val tileH = fitRows(availW, availH, maxCols, rows, gapX, gapY, tileAspect)
        val tileW = tileH * tileAspect
        val blockH = rows * tileH + (rows - 1) * gapY
        val topCy = areaBottom + (areaH - blockH) * 0.5f + blockH - tileH * 0.5f

        for (g in 0 until 4) {
            val len = groupLens[g]
            val first = groupFirst[g]
            val rowW = len * tileW + (len - 1) * gapX
            val rowOriginX = areaLeft + (areaW - rowW) * 0.5f + tileW * 0.5f
            val cy = topCy - g * (tileH + gapY)
            for (r in 0 until len) {
                val type = first + r
                val cx = rowOriginX + r * (tileW + gapX)
                for (copy in 0 until 4) {
                    setTarget.setTarget(
                        type * 4 + copy, cx, cy, tileW, tileH, rot = 0f, open = true,
                    )
                }
            }
        }
    }

    /**
     * 16 rows = 4 groups × 4 copies, filling the diagram area (or an inset of
     * [squareLeft]/[squareBottom]/[squareSide] when [inSquare] is true).
     */
    fun layoutCopies(
        areaLeft: Float,
        areaBottom: Float,
        areaW: Float,
        areaH: Float,
        inSquare: Boolean,
        open: Boolean,
        tileAspect: Float,
        squareLeft: Float = 0f,
        squareBottom: Float = 0f,
        squareSide: Float = 0f,
        setTarget: TargetSink,
    ) {
        val pad = Fonts.px(if (inSquare) 8f else 4f)
        val gapX = Fonts.px(2f)
        val gapY = Fonts.px(2.5f)
        val totalRows = 16
        val maxCols = 9
        val boxL: Float
        val boxB: Float
        val boxW: Float
        val boxH: Float
        if (inSquare) {
            boxL = squareLeft + pad
            boxB = squareBottom + pad
            boxW = (squareSide - pad * 2f).coerceAtLeast(4f)
            boxH = boxW
        } else {
            boxL = areaLeft + pad
            boxB = areaBottom + pad
            boxW = (areaW - pad * 2f).coerceAtLeast(4f)
            boxH = (areaH - pad * 2f).coerceAtLeast(4f)
        }
        val tileH = fitRows(boxW, boxH, maxCols, totalRows, gapX, gapY, tileAspect)
        val tileW = tileH * tileAspect
        placeCopyRows(boxL, boxB, boxW, boxH, tileW, tileH, gapX, gapY, open, setTarget)
    }

    /**
     * Face-down pack at fixed physical scale: table [tableMm], tile long side
     * [tileMm]. Diagram square is only the view transform — tiles do not grow
     * to fill the square (unlike [layoutCopies]).
     */
    fun layoutFaceDown(
        squareLeft: Float,
        squareBottom: Float,
        squareSide: Float,
        tableMm: Float,
        tileMm: Float,
        tileAspect: Float,
        setTarget: TargetSink,
    ) {
        val pxPerMm = (squareSide / tableMm).coerceAtLeast(1e-4f)
        val tileH = tileMm * pxPerMm
        val tileW = tileH * tileAspect
        // ~1.5 mm felt gaps in the same millimetre map.
        val gapX = 1.5f * pxPerMm
        val gapY = 1.5f * pxPerMm
        placeCopyRows(
            boxL = squareLeft,
            boxB = squareBottom,
            boxW = squareSide,
            boxH = squareSide,
            tileW = tileW,
            tileH = tileH,
            gapX = gapX,
            gapY = gapY,
            open = false,
            setTarget = setTarget,
        )
    }

    /** 16 rows = 4 groups × 4 copies; centered in [boxL, boxB, boxW, boxH]. */
    private fun placeCopyRows(
        boxL: Float,
        boxB: Float,
        boxW: Float,
        boxH: Float,
        tileW: Float,
        tileH: Float,
        gapX: Float,
        gapY: Float,
        open: Boolean,
        setTarget: TargetSink,
    ) {
        val totalRows = 16
        val blockH = totalRows * tileH + (totalRows - 1) * gapY
        val topCy = boxB + (boxH - blockH) * 0.5f + blockH - tileH * 0.5f

        var row = 0
        for (g in 0 until 4) {
            val len = groupLens[g]
            val first = groupFirst[g]
            for (copy in 0 until 4) {
                val rowW = len * tileW + (len - 1) * gapX
                val rowOriginX = boxL + (boxW - rowW) * 0.5f + tileW * 0.5f
                val cy = topCy - row * (tileH + gapY)
                for (r in 0 until len) {
                    val type = first + r
                    setTarget.setTarget(
                        type * 4 + copy,
                        rowOriginX + r * (tileW + gapX),
                        cy,
                        tileW,
                        tileH,
                        rot = 0f,
                        open = open,
                    )
                }
                row++
            }
        }
    }

    private fun fitRows(
        areaW: Float,
        areaH: Float,
        cols: Int,
        rows: Int,
        gapX: Float,
        gapY: Float,
        tileAspect: Float,
    ): Float {
        if (cols <= 0 || rows <= 0 || areaW <= 0f || areaH <= 0f) return 8f
        val hFromW = (areaW - gapX * (cols - 1)) / cols / tileAspect
        val hFromH = (areaH - gapY * (rows - 1)) / rows
        return min(hFromW, hFromH).coerceAtLeast(4f)
    }
}
