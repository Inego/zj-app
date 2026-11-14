package inego.zungjunggame.render

import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.session.table.SeatView
import inego.zungjunggame.session.table.buildConcealedKongTiles
import inego.zungjunggame.session.table.buildOpenChowTiles
import inego.zungjunggame.session.table.buildOpenSetTiles
import inego.zungjunggame.session.table.relativeSeat
import inego.zungjunggame.session.table.sidewaysIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeldDisplayTest {

    @Test
    fun concealedKongShowsOuterClosedMiddleOpen() {
        val tiles = buildConcealedKongTiles(Tiles.EAST)
        assertEquals(4, tiles.size)
        assertFalse(tiles[0].open)
        assertTrue(tiles[1].open)
        assertTrue(tiles[2].open)
        assertFalse(tiles[3].open)
        assertTrue(tiles.none { it.rotated })
        assertTrue(tiles.all { it.tileType == Tiles.EAST })
    }

    @Test
    fun openPungSidewaysPointsAtDiscarder() {
        // Owner seat 0: from seat 3 (left) → first tile rotated
        val fromLeft = buildOpenSetTiles(Tiles.DOT_5, n = 3, relativeFrom = 3)
        assertTrue(fromLeft[0].rotated)
        assertFalse(fromLeft[1].rotated)
        assertFalse(fromLeft[2].rotated)

        // From opposite (seat 2) → middle
        val fromOpp = buildOpenSetTiles(Tiles.DOT_5, n = 3, relativeFrom = 2)
        assertFalse(fromOpp[0].rotated)
        assertTrue(fromOpp[1].rotated)
        assertFalse(fromOpp[2].rotated)

        // From right (seat 1) → last
        val fromRight = buildOpenSetTiles(Tiles.DOT_5, n = 3, relativeFrom = 1)
        assertFalse(fromRight[0].rotated)
        assertFalse(fromRight[1].rotated)
        assertTrue(fromRight[2].rotated)
    }

    @Test
    fun openKongSidewaysIndex() {
        assertEquals(0, sidewaysIndex(3, 4))
        assertEquals(1, sidewaysIndex(2, 4))
        assertEquals(3, sidewaysIndex(1, 4))
    }

    @Test
    fun chowClaimedTileIsSidewaysOnCallSide() {
        // Claim 3-char into 2-3-4, from left → claimed on the left
        val tiles = buildOpenChowTiles(
            first = Tiles.CHAR_2,
            claimedTile = Tiles.CHAR_3,
            relativeFrom = 3,
        )
        assertEquals(3, tiles.size)
        assertTrue(tiles[0].rotated)
        assertEquals(Tiles.CHAR_3, tiles[0].tileType)
        // Remaining tiles in ascending order in the free slots
        assertEquals(Tiles.CHAR_2, tiles[1].tileType)
        assertEquals(Tiles.CHAR_4, tiles[2].tileType)
    }

    @Test
    fun relativeSeatMatchesTurnOrder() {
        assertEquals(1, relativeSeat(0, 1))
        assertEquals(2, relativeSeat(0, 2))
        assertEquals(3, relativeSeat(0, 3))
        assertEquals(3, relativeSeat(1, 0))
    }

    @Test
    fun handWithParkedDrawIsCenteredLikeFourteenTileOpening() {
        val metrics = PlayerAreaLayout.Metrics(
            handTileW = 30f,
            handTileH = 40f,
            discardTileW = 24f,
            discardTileH = 32f,
            tileGap = 2f,
            meldGap = 10f,
            rowGap = 4f,
            discardsPerRow = 6,
            maxDiscardRows = 3,
            drawnGap = 16f,
        )

        fun edgeBalance(layout: List<PlayerAreaLayout.TilePlacement>): Float {
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (p in layout) {
                if (p.region == PlayerAreaLayout.Region.River) continue
                val half = if (p.rotationOffsetDeg != 0f) p.h * 0.5f else p.w * 0.5f
                left = minOf(left, p.lx - half)
                right = maxOf(right, p.lx + half)
            }
            // Perfectly centered row → left ≈ −right → sum ≈ 0.
            return left + right
        }

        val opening = PlayerState()
        opening.concealed[Tiles.CHAR_1] = 14
        opening.activeConcealedTileCount = -1
        val openLayout = PlayerAreaLayout.layout(
            player = opening,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
            drawnTile = -1,
        )
        assertEquals(0f, edgeBalance(openLayout), 0.01f, "14-tile opening is centered")

        val mid = PlayerState()
        mid.concealed[Tiles.CHAR_1] = 13
        mid.concealed[Tiles.DOT_1] = 1
        mid.activeConcealedTileCount = -1
        val midLayout = PlayerAreaLayout.layout(
            player = mid,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
            drawnTile = Tiles.DOT_1,
        )
        assertTrue(midLayout.any { it.isDrawnSlot })
        assertEquals(
            0f,
            edgeBalance(midLayout),
            0.01f,
            "13+parked-draw row must be centered on the full visual width",
        )
    }

    @Test
    fun waitingHandTilesKeepXWhenDrawParks() {
        val metrics = sampleMetrics()
        val waiting = PlayerState()
        waiting.concealed[Tiles.CHAR_1] = 4
        waiting.concealed[Tiles.CHAR_2] = 4
        waiting.concealed[Tiles.CHAR_3] = 4
        waiting.concealed[Tiles.DOT_1] = 1
        waiting.activeConcealedTileCount = -1

        val afterDraw = waiting.copy()
        afterDraw.concealed[Tiles.BAMBOO_9] = 1
        afterDraw.activeConcealedTileCount = -1

        val before = PlayerAreaLayout.layout(
            player = waiting,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
            drawnTile = -1,
        )
        val after = PlayerAreaLayout.layout(
            player = afterDraw,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
            drawnTile = Tiles.BAMBOO_9,
        )

        assertFalse(before.any { it.isDrawnSlot }, "waiting hand must not emit a ghost drawn tile")
        assertTrue(after.any { it.isDrawnSlot })
        val reserved = before.single { it.emptyDrawReservation }
        val parked = after.single { it.isDrawnSlot }
        assertEquals(parked.lx, reserved.lx, 0.01f, "the empty draw slot is where the tile parks")
        assertEquals(parked.ly, reserved.ly, 0.01f, "the empty draw slot is where the tile parks")
        val beforeHand = before.filter { it.region == PlayerAreaLayout.Region.Hand }
        val afterHand = after.filter { it.region == PlayerAreaLayout.Region.Hand }
        assertEquals(13, beforeHand.size)
        assertEquals(13, afterHand.size)
        for (i in beforeHand.indices) {
            assertEquals(beforeHand[i].tileType, afterHand[i].tileType, "hand[$i] type")
            assertEquals(beforeHand[i].lx, afterHand[i].lx, 0.01f, "hand[$i] must not slide when the draw parks")
        }
    }

    @Test
    fun waitingSeatViewHandKeepsXWhenDrawParks() {
        val metrics = sampleMetrics()
        val concealed = List(13) { it }
        val waiting = SeatView(
            seat = 0,
            concealedCount = 13,
            concealedTiles = concealed,
            drawnTile = -1,
            hasDrawnTile = false,
        )
        val drawn = waiting.copy(
            concealedCount = 14,
            drawnTile = Tiles.DOT_9,
            hasDrawnTile = true,
        )
        val before = PlayerAreaLayout.layout(
            seatView = waiting,
            showHandFaces = true,
            metrics = metrics,
        )
        val after = PlayerAreaLayout.layout(
            seatView = drawn,
            showHandFaces = true,
            metrics = metrics,
        )
        val beforeHand = before.filter { it.region == PlayerAreaLayout.Region.Hand }
        val afterHand = after.filter { it.region == PlayerAreaLayout.Region.Hand }
        assertEquals(13, beforeHand.size)
        assertFalse(before.any { it.isDrawnSlot })
        assertTrue(after.any { it.isDrawnSlot })
        for (i in beforeHand.indices) {
            assertEquals(beforeHand[i].lx, afterHand[i].lx, 0.01f)
        }
    }

    @Test
    fun layoutFittingAlongEdgeKeepsStableScaleAcrossParkedDraw() {
        val metrics = PlayerAreaLayout.Metrics(
            handTileW = 30f,
            handTileH = 40f,
            discardTileW = 24f,
            discardTileH = 32f,
            tileGap = 2f,
            meldGap = 10f,
            rowGap = 4f,
            discardsPerRow = 6,
            maxDiscardRows = 3,
            drawnGap = 16f,
        )
        // 13-tile row + parked draw exceeds the budget; fit must reserve that width
        // even before the draw appears (same scale for every seat).
        val ps = PlayerState()
        ps.concealed[Tiles.CHAR_1] = 13
        ps.activeConcealedTileCount = -1

        val maxHalf = 220f
        val withoutDrawUnfitted = PlayerAreaLayout.layout(
            player = ps,
            river = emptyList(),
            showHandFaces = false,
            metrics = metrics,
            ownerSeat = 1,
            drawnTile = -1,
        )

        val afterDraw = ps.copy()
        afterDraw.concealed[Tiles.DOT_1] = 1
        afterDraw.activeConcealedTileCount = -1
        val withDrawUnfitted = PlayerAreaLayout.layout(
            player = afterDraw,
            river = emptyList(),
            showHandFaces = false,
            metrics = metrics,
            ownerSeat = 1,
            drawnTile = Tiles.DOT_1,
        )
        val waitingSpan = PlayerAreaLayout.alongRowHalfSpan(withoutDrawUnfitted)
        val drawnSpan = PlayerAreaLayout.alongRowHalfSpan(withDrawUnfitted)
        assertEquals(
            drawnSpan,
            waitingSpan,
            0.01f,
            "waiting 13 already reserves parked-draw width, so span must match the parked row",
        )
        assertTrue(
            drawnSpan > maxHalf,
            "parked-draw width must push past the budget so the regression is meaningful",
        )

        val withoutDrawFitted = PlayerAreaLayout.layoutFittingAlongEdge(
            player = ps,
            river = emptyList(),
            showHandFaces = false,
            metrics = metrics,
            maxHalfSpan = maxHalf,
            ownerSeat = 1,
            drawnTile = -1,
        )
        val withDrawFitted = PlayerAreaLayout.layoutFittingAlongEdge(
            player = afterDraw,
            river = emptyList(),
            showHandFaces = false,
            metrics = metrics,
            maxHalfSpan = maxHalf,
            ownerSeat = 1,
            drawnTile = Tiles.DOT_1,
        )

        // Same tile size before/after the draw — no pulse; draw is always budgeted.
        assertEquals(withoutDrawFitted.first().w, withDrawFitted.first().w, 0.01f)
        assertEquals(withoutDrawFitted.first().h, withDrawFitted.first().h, 0.01f)
        // Parked draw must still fit the along-edge budget (no overflow / clip).
        assertTrue(
            PlayerAreaLayout.alongRowHalfSpan(withDrawFitted) <= maxHalf + 0.5f,
            "fitted parked-draw row must stay within budget",
        )
        assertTrue(withDrawFitted.any { it.isDrawnSlot })
        // Pre-draw fit already shrank vs unfitted (reserved parked-draw width).
        assertTrue(withoutDrawFitted.first().w < withoutDrawUnfitted.first().w)
    }

    private fun sampleMetrics() = PlayerAreaLayout.Metrics(
        handTileW = 30f,
        handTileH = 40f,
        discardTileW = 24f,
        discardTileH = 32f,
        tileGap = 2f,
        meldGap = 10f,
        rowGap = 4f,
        discardsPerRow = 6,
        maxDiscardRows = 3,
        drawnGap = 16f,
    )

    @Test
    fun layoutFittingAlongEdgeLeavesRoomyRowsUnscaled() {
        val metrics = PlayerAreaLayout.Metrics(
            handTileW = 30f,
            handTileH = 40f,
            discardTileW = 24f,
            discardTileH = 32f,
            tileGap = 2f,
            meldGap = 10f,
            rowGap = 4f,
            discardsPerRow = 6,
            maxDiscardRows = 3,
            drawnGap = 16f,
        )
        val ps = PlayerState()
        ps.concealed[Tiles.CHAR_1] = 13
        ps.activeConcealedTileCount = -1

        val full = PlayerAreaLayout.layout(
            player = ps,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
        )
        val fitted = PlayerAreaLayout.layoutFittingAlongEdge(
            player = ps,
            river = emptyList(),
            showHandFaces = true,
            metrics = metrics,
            maxHalfSpan = 10_000f,
            ownerSeat = 0,
        )
        assertEquals(full.map { it.lx to it.w }, fitted.map { it.lx to it.w })
    }

    @Test
    fun discardNewestRowStaysNearestHandOlderRowsShiftUp() {
        val metrics = PlayerAreaLayout.Metrics(
            handTileW = 30f,
            handTileH = 40f,
            discardTileW = 24f,
            discardTileH = 32f,
            tileGap = 2f,
            meldGap = 10f,
            rowGap = 4f,
            discardsPerRow = 6,
            maxDiscardRows = 3,
            drawnGap = 16f,
        )
        val ps = PlayerState()
        // Empty hand so river placement is easy to isolate.
        ps.activeConcealedTileCount = -1

        val oneRow = PlayerAreaLayout.layout(
            player = ps,
            river = List(6) { Tiles.CHAR_1 + it },
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
        ).filter { it.region == PlayerAreaLayout.Region.River }
        assertEquals(6, oneRow.size)
        val baseY = oneRow.minOf { it.ly }
        assertTrue(oneRow.all { it.ly == baseY }, "single row stays on the hand-side line")

        val twoRows = PlayerAreaLayout.layout(
            player = ps,
            river = List(7) { Tiles.CHAR_1 + (it % 9) },
            showHandFaces = true,
            metrics = metrics,
            ownerSeat = 0,
        ).filter { it.region == PlayerAreaLayout.Region.River }
        assertEquals(7, twoRows.size)

        val rowPitch = metrics.discardTileH + metrics.rowGap
        val newestY = twoRows.filter { it.regionIndex >= 6 }.minOf { it.ly }
        val oldestY = twoRows.filter { it.regionIndex < 6 }.minOf { it.ly }
        assertEquals(baseY, newestY, 0.01f, "new row starts where the first row began")
        assertEquals(baseY + rowPitch, oldestY, 0.01f, "filled first row shifts toward center")
    }
}
