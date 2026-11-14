package inego.zungjunggame.tutorial.slide.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagramLayoutTest {

    private fun vp(
        w: Float,
        h: Float,
        touch: Boolean = false,
        uiScale: Float = 1f,
    ) = SlideViewport(w, h, touch, uiScale)

    /** Approximate proportional width for tests (no FreeType). */
    private fun measureApprox(text: String, scale: Float): Float =
        text.length * 10f * scale

    @Test
    fun fitTileHeightNeverExceedsArea() {
        val areaW = 400f
        val areaH = 200f
        val cols = 9
        val rows = 3
        val gap = 6f
        val tileH = DiagramLayout.fitTileHeight(areaW, areaH, cols, rows, gap, gap)
        val tileW = DiagramLayout.tileWidth(tileH)
        assertTrue(tileH > 0f)
        assertTrue(cols * tileW + (cols - 1) * gap <= areaW + 0.5f)
        assertTrue(rows * tileH + (rows - 1) * gap <= areaH + 0.5f)
    }

    @Test
    fun fitTileHeightZeroWhenNoRoom() {
        val tileH = DiagramLayout.fitTileHeight(10f, 10f, 9, 3, 8f, 8f)
        assertEquals(0f, tileH, 0.01f)
    }

    @Test
    fun threeSuitsTilesStayInsideRect_portraitCompact() {
        val areaLeft = 12f
        val areaBottom = 60f
        val areaW = 320f
        val areaH = 280f
        val plan = DiagramLayout.planThreeSuits(
            vp = vp(360f, 640f, touch = true),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            suitLabelTexts = listOf("Characters", "Dots", "Bamboos"),
            firstTypes = listOf(0, 9, 18),
            measureLabelW = ::measureApprox,
            labelLineHeight = 22f,
        )
        assertEquals(27, plan.tiles.size)
        // Rank numeral under every tile (3 suits × 9), not a shared bottom strip.
        assertEquals(27, plan.rankLabels.size)
        assertEquals(3, plan.suitLabels.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"),
            plan.rankLabels.take(9).map { it.text },
        )
        val right = areaLeft + areaW
        val top = areaBottom + areaH
        for ((_, left, bottom, w, h) in plan.tiles) {
            assertTrue("tile left $left", left >= areaLeft - 1f)
            assertTrue("tile right ${left + w}", left + w <= right + 1f)
            assertTrue("tile bottom $bottom", bottom >= areaBottom - 1f)
            assertTrue("tile top ${bottom + h}", bottom + h <= top + 1f)
        }
        assertTrue(plan.labelScale <= DiagramLayout.labelScaleMax(vp(360f, 640f, true)) + 0.001f)
    }

    @Test
    fun threeSuitsTilesStayInsideRect_landscapeDesktop() {
        val areaLeft = 28f
        val areaBottom = 80f
        val areaW = 900f
        val areaH = 420f
        val plan = DiagramLayout.planThreeSuits(
            vp = vp(1280f, 720f, touch = false),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            suitLabelTexts = listOf("Characters", "Dots", "Bamboos"),
            firstTypes = listOf(0, 9, 18),
            measureLabelW = ::measureApprox,
            labelLineHeight = 28f,
        )
        assertEquals(27, plan.tiles.size)
        val right = areaLeft + areaW
        val top = areaBottom + areaH
        for ((_, left, bottom, w, h) in plan.tiles) {
            assertTrue(left >= areaLeft - 1f)
            assertTrue(left + w <= right + 1f)
            assertTrue(bottom >= areaBottom - 1f)
            assertTrue(bottom + h <= top + 1f)
        }
        // Beside labels sit snug left of the tile block (not a wide gutter).
        val firstTileLeft = plan.tiles.minOf { it.left }
        for ((text, x, _, scale) in plan.suitLabels) {
            val labelRight = x + measureApprox(text, scale)
            val gap = firstTileLeft - labelRight
            assertTrue("label-tile gap $gap too large for '$text'", gap in 0f..20f)
        }
    }

    @Test
    fun honorsNamesDoNotOverflowTileBand_narrow() {
        val areaLeft = 10f
        val areaBottom = 50f
        val areaW = 300f
        val areaH = 240f
        val rows = listOf(
            DiagramLayout.HonorRowModel(
                "Winds",
                listOf(
                    27 to "East",
                    28 to "South",
                    29 to "West",
                    30 to "North",
                ),
            ),
            DiagramLayout.HonorRowModel(
                "Dragons",
                listOf(
                    31 to "Red",
                    32 to "Green",
                    33 to "White",
                ),
            ),
        )
        val plan = DiagramLayout.planHonors(
            vp = vp(340f, 600f, touch = true),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            rows = rows,
            measureLabelW = ::measureApprox,
            labelLineHeight = 22f,
        )
        assertEquals(7, plan.tiles.size)
        val right = areaLeft + areaW
        val top = areaBottom + areaH
        for ((_, left, bottom, w, h) in plan.tiles) {
            assertTrue(left >= areaLeft - 1f)
            assertTrue(left + w <= right + 1f)
            assertTrue(bottom >= areaBottom - 1f)
            assertTrue(bottom + h <= top + 1f)
        }
        // Name scale must stay at or below caption max (no old 1.4× inflation).
        assertTrue(plan.nameScale <= 1.1f)
        assertTrue(plan.groupLabelScale <= 1.1f)
    }

    @Test
    fun labeledRowsFitSequenceExample() {
        val rows = listOf(
            DiagramLayout.LabeledRowModel(
                "Sequence",
                listOf(DiagramLayout.TileGroupModel(listOf(1, 2, 3))),
            ),
            DiagramLayout.LabeledRowModel(
                "Sequence",
                listOf(DiagramLayout.TileGroupModel(listOf(4, 5, 6))),
            ),
            DiagramLayout.LabeledRowModel(
                "Sequence",
                listOf(DiagramLayout.TileGroupModel(listOf(7, 8, 9))),
            ),
        )
        val areaLeft = 20f
        val areaBottom = 70f
        val areaW = 500f
        val areaH = 300f
        val plan = DiagramLayout.planLabeledRows(
            vp = vp(800f, 600f),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            rows = rows,
            measureLabelW = ::measureApprox,
            labelLineHeight = 24f,
        )
        assertEquals(9, plan.tiles.size)
        assertEquals(3, plan.rowLabels.size)
        val right = areaLeft + areaW
        val top = areaBottom + areaH
        for ((_, left, bottom, w, h) in plan.tiles) {
            assertTrue(left + w <= right + 1f)
            assertTrue(bottom + h <= top + 1f)
            assertTrue(left >= areaLeft - 1f)
            assertTrue(bottom >= areaBottom - 1f)
        }
    }

    @Test
    fun chooseLabelModeStacksOnPortraitCompact() {
        val mode = DiagramLayout.chooseLabelMode(
            vp = vp(360f, 720f, touch = true),
            areaW = 320f,
            maxLabelW = 140f,
            tileCols = 9,
            minTileHDesired = 28f,
        )
        assertEquals(DiagramLayout.LabelMode.Above, mode)
    }

    @Test
    fun notASequencePeelCaptionsSitSnugUnderTiles_portraitTall() {
        // Tall diagram rect (vertical/compact coach) used to stretch the row
        // so Spare/Sequence sat on the bottom while tiles floated mid-screen.
        val areaLeft = 16f
        val areaBottom = 80f
        val areaW = 320f
        val areaH = 360f
        val plan = DiagramLayout.planNotASequencePeels(
            vp = vp(360f, 720f, touch = true),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            phase = 1,
            peelT = 1f,
            types = intArrayOf(2, 3, 4, 5),
            spareCaption = "Spare",
            sequenceCaption = "Sequence",
            measureLabelW = ::measureApprox,
            labelLineHeight = 22f,
        )
        assertEquals(1, plan.rows.size)
        val row = plan.rows.single()
        val spare = requireNotNull(row.spareCaption)
        val sequence = requireNotNull(row.sequenceCaption)
        val tileBottom = row.tileRects.minOf { it.bottom }
        val tileTop = row.tileRects.maxOf { it.bottom + it.h }
        // Captions live in the band just under the tiles, not the diagram floor.
        val gapUnderTiles = tileBottom - spare.baselineY
        assertTrue("caption too far under tiles: gap=$gapUnderTiles", gapUnderTiles in 0f..40f)
        assertTrue(sequence.baselineY in spare.baselineY - 1f..spare.baselineY + 1f)
        // Content is not pinned to the diagram bottom.
        assertTrue(spare.baselineY > areaBottom + 40f)
        // Tiles stay inside the rect.
        assertTrue(tileTop <= areaBottom + areaH + 1f)
        assertTrue(tileBottom >= areaBottom - 1f)
    }

    @Test
    fun notASequencePeelTwoRowsStayInside_phase2() {
        val areaLeft = 20f
        val areaBottom = 60f
        val areaW = 500f
        val areaH = 280f
        val plan = DiagramLayout.planNotASequencePeels(
            vp = vp(800f, 600f),
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            phase = 2,
            peelT = 1f,
            types = intArrayOf(2, 3, 4, 5),
            spareCaption = "Spare",
            sequenceCaption = "Sequence",
            measureLabelW = ::measureApprox,
            labelLineHeight = 24f,
        )
        assertEquals(2, plan.rows.size)
        val right = areaLeft + areaW
        val top = areaBottom + areaH
        for ((tileRects, spareCaption) in plan.rows) {
            for ((_, left, bottom, w, h) in tileRects) {
                assertTrue(left >= areaLeft - 1f)
                assertTrue(left + w <= right + 1f)
                assertTrue(bottom >= areaBottom - 1f)
                assertTrue(bottom + h <= top + 1f)
            }
            val spare = requireNotNull(spareCaption)
            val tileBottom = tileRects.minOf { it.bottom }
            assertTrue(tileBottom - spare.baselineY in 0f..40f)
        }
    }

    @Test
    fun viewportClassifiesOrientationAndSize() {
        val phone = vp(360f, 720f, touch = true)
        assertEquals(SlideOrientation.Portrait, phone.orientation)
        assertEquals(SlideSizeClass.Compact, phone.sizeClass)

        val desktop = vp(1600f, 900f, touch = false)
        assertEquals(SlideOrientation.Landscape, desktop.orientation)
        assertEquals(SlideSizeClass.Comfortable, desktop.sizeClass)
    }
}
