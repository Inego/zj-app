package inego.zungjunggame.render

import inego.zungjunggame.render.tiles.TileHighlight
import inego.zungjunggame.render.tiles.TileHighlightBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileHighlightTest {

    private fun box(
        cx: Float,
        cy: Float = 0f,
        w: Float = 36f,
        h: Float = 48f,
        rot: Float = 0f,
        key: Int = 0,
    ) = TileHighlightBox(cx, cy, w, h, rot, key)

    @Test
    fun emptyInputYieldsNoClusters() {
        assertTrue(TileHighlight.cluster(emptyList()).isEmpty())
    }

    @Test
    fun singleBoxKeepsPose() {
        val c = TileHighlight.cluster(listOf(box(100f, 40f, 36f, 48f))).single()
        assertEquals(100f, c.cx, 0.01f)
        assertEquals(40f, c.cy, 0.01f)
        assertEquals(36f, c.w, 0.01f)
        assertEquals(48f, c.h, 0.01f)
        assertEquals(0f, c.rotationDeg, 0.01f)
    }

    @Test
    fun adjacentHandTilesMergeToOneOutline() {
        // Centers 40 apart, width 36 → 4px gap (hand tileGap ≈ 8% of w).
        val clusters = TileHighlight.cluster(
            listOf(box(0f), box(40f), box(80f)),
        )
        assertEquals(1, clusters.size)
        val c = clusters.single()
        assertEquals(40f, c.cx, 0.01f)
        assertEquals(0f, c.cy, 0.01f)
        assertEquals(116f, c.w, 0.01f) // 80 + 18 − (−18)
        assertEquals(48f, c.h, 0.01f)
    }

    @Test
    fun drawnTileGapDoesNotMerge() {
        // Drawn slot sits ~0.55w past the hand; join pad is 0.12 × min-side.
        val clusters = TileHighlight.cluster(
            listOf(box(0f), box(36f + 36f * 0.55f + 18f)),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun differentGroupKeysDoNotMerge() {
        val clusters = TileHighlight.cluster(
            listOf(box(0f, key = 0), box(40f, key = 1)),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun farApartOrthogonalTilesDoNotMerge() {
        val clusters = TileHighlight.cluster(
            listOf(box(0f, rot = 0f), box(100f, rot = 90f)),
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun pungWithSidewaysClaimTileMergesToOneOutline() {
        // Upright 36×48, sideways (footprint 48), upright; 4px gaps like a hand row.
        val clusters = TileHighlight.cluster(
            listOf(
                box(18f, rot = 0f),
                box(64f, rot = 90f),
                box(110f, rot = 0f),
            ),
        )
        assertEquals(1, clusters.size)
        val c = clusters.single()
        assertEquals(0f, c.rotationDeg, 0.01f)
        assertEquals(64f, c.cx, 0.01f)
        assertEquals(0f, c.cy, 0.01f)
        assertEquals(128f, c.w, 0.01f)
        assertEquals(48f, c.h, 0.01f)
    }

    @Test
    fun twoByThreeGridMergesToOneRect() {
        val boxes = ArrayList<TileHighlightBox>()
        for (row in 0..1) {
            for (col in 0..2) {
                boxes += box(col * 40f, row * 50f, w = 36f, h = 48f)
            }
        }
        val c = TileHighlight.cluster(boxes).single()
        assertEquals(40f, c.cx, 0.01f)
        assertEquals(25f, c.cy, 0.01f)
        assertEquals(116f, c.w, 0.01f)
        assertEquals(98f, c.h, 0.01f)
    }

    @Test
    fun rotatedRowUnionsInLocalFrame() {
        // Right-seat hand: 90° tiles stacked along +Y, same world X.
        val clusters = TileHighlight.cluster(
            listOf(
                box(100f, 0f, rot = 90f),
                box(100f, 40f, rot = 90f),
                box(100f, 80f, rot = 90f),
            ),
        )
        assertEquals(1, clusters.size)
        val c = clusters.single()
        assertEquals(90f, c.rotationDeg, 0.01f)
        assertEquals(100f, c.cx, 0.01f)
        assertEquals(40f, c.cy, 0.01f)
        assertEquals(116f, c.w, 0.01f)
        assertEquals(48f, c.h, 0.01f)
    }
}
