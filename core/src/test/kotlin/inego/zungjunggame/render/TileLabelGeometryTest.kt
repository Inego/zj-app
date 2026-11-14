package inego.zungjunggame.render

import inego.zungjunggame.render.tiles.LabelFace
import inego.zungjunggame.render.tiles.LabelPlacement
import inego.zungjunggame.render.tiles.TileLabelGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tag's placement contract, provable with no GL frame. Every convention below has
 * been broken once on screen already, so each has a falsifier here:
 *
 * - the tag did not scale with its tile (a fixed pixel size that only ever shrank);
 * - the tag then filled too much of the tile and read as a badge;
 * - the draw Y was treated as a baseline, sinking the tag one line;
 * - the tag could outgrow its share of the face.
 */
class TileLabelGeometryTest {

    /** One digit's ink box on the label face, at its resting scale. */
    private val face = LabelFace(inkW = 10f, inkH = 14f)

    private fun place(
        tileW: Float,
        tileH: Float,
        labelFace: LabelFace = face,
        minInkHeight: Float = 3f,
    ): LabelPlacement? = TileLabelGeometry.place(tileW, tileH, labelFace, minInkHeight)

    // --- how big it is ------------------------------------------------------


    /** Falsifier for the capped-size bug: four times the tile, four times the tag. */
    @Test
    fun tagGrowsWithTheTile() {
        val small = place(50f, 70f) ?: error("expected a tag")
        val big = place(200f, 280f) ?: error("expected a tag")
        assertEquals(small.inkH * 4f, big.inkH, 0.01f)
        assertEquals(small.inkW * 4f, big.inkW, 0.01f)
    }

    /** A huge tile must keep growing the tag rather than capping at the atlas size. */
    @Test
    fun aHugeTileKeepsScalingInsteadOfCapping() {
        val p = place(2000f, 2800f) ?: error("expected a tag")
        assertTrue(p.scale > 1f, "the face is drawn larger than it was rasterised")
        assertEquals(2800f * TileLabelGeometry.MAX_HEIGHT_FRACTION, p.inkH, 0.1f)
    }

    @Test
    fun inkFillsTheShareOfWhicheverAxisBinds() {
        // Wide face: height binds.
        val wide = place(100f, 140f) ?: error("expected a tag")
        assertEquals(140f * TileLabelGeometry.MAX_HEIGHT_FRACTION, wide.inkH, 0.01f)
        // Narrow face: width binds, and the height follows proportionally.
        val narrow = place(40f, 200f) ?: error("expected a tag")
        assertEquals(40f * TileLabelGeometry.MAX_WIDTH_FRACTION, narrow.inkW, 0.01f)
    }

    @Test
    fun tagNeverOutgrowsItsShareOfTheFace() {
        for ((w, h) in listOf(40f to 200f, 100f to 140f, 2000f to 2800f, 60f to 60f)) {
            val p = place(w, h) ?: continue
            assertTrue(p.inkW <= w * TileLabelGeometry.MAX_WIDTH_FRACTION + 0.01f, "width share $w x $h")
            assertTrue(p.inkH <= h * TileLabelGeometry.MAX_HEIGHT_FRACTION + 0.01f, "height share $w x $h")
            assertTrue(p.leftX >= -w * 0.5f && p.rightX <= w * 0.5f, "inside horizontally $w x $h")
            assertTrue(p.bottomY >= -h * 0.5f && p.topY <= h * 0.5f, "inside vertically $w x $h")
        }
    }

    // --- where it sits ------------------------------------------------------

    /** Falsifier for the sink: the cap top is the inset line, ink hangs below it. */
    @Test
    fun topLineIsTheCapTopOnTheInsetLine() {
        val p = place(100f, 140f) ?: error("expected a tag")
        assertEquals(140f * 0.5f - 140f * TileLabelGeometry.INSET_Y_FRACTION, p.topY, 0.001f)
        assertEquals(-100f * 0.5f + 100f * TileLabelGeometry.INSET_X_FRACTION, p.leftX, 0.001f)
        assertEquals(p.topY - p.inkH, p.bottomY)
    }

    // --- nothing to draw ----------------------------------------------------

    @Test
    fun aTileTooSmallForALegibleTagGetsNothing() {
        assertNull(place(30f, 42f, minInkHeight = 20f), "6.3px of ink is below a 20px floor")
        assertTrue(place(30f, 42f, minInkHeight = 5f) != null, "the same tile passes a 5px floor")
    }

    @Test
    fun degenerateInputDrawsNothing() {
        assertNull(place(0f, 140f), "zero-width tile")
        assertNull(place(100f, 0f), "zero-height tile")
        assertNull(place(-5f, -5f), "negative tile")
        assertNull(place(100f, 140f, labelFace = LabelFace(0f, 0f)), "a face with no metrics")
    }
}
