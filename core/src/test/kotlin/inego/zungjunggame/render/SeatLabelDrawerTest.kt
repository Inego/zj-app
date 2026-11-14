package inego.zungjunggame.render

import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import inego.zungjunggame.render.seats.SeatLabelDrawer
import inego.zungjunggame.render.seats.SeatSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatLabelDrawerTest {

    @Test
    fun identityIncomingKeepsLabelCenter() {
        val dest = Matrix4()
        SeatLabelDrawer.composeCenteredTextTransform(
            dest = dest,
            incoming = Matrix4(),
            cx = 100f,
            cy = 50f,
            rotationDeg = 0f,
            scale = 1f,
        )
        val p = Vector3(0f, 0f, 0f).mul(dest)
        assertEquals(100f, p.x, 1e-4f)
        assertEquals(50f, p.y, 1e-4f)
    }

    @Test
    fun incomingContentOffsetIsPreserved() {
        // Tutorial shell translates the batch so (0,0) is the content-zone origin
        // (right of the TOC). Replacing that matrix would draw labels in world
        // space and shove them left by the rail width.
        val incoming = Matrix4().setToTranslation(240f, 16f, 0f)
        val dest = Matrix4()
        SeatLabelDrawer.composeCenteredTextTransform(
            dest = dest,
            incoming = incoming,
            cx = 100f,
            cy = 50f,
            rotationDeg = 0f,
            scale = 1f,
        )
        val p = Vector3(0f, 0f, 0f).mul(dest)
        assertEquals(340f, p.x, 1e-4f)
        assertEquals(66f, p.y, 1e-4f)
    }

    @Test
    fun leftAndRightUseTheSameGutterPlacementAsOpposite() {
        val args = arrayOf(SeatSide.Left, SeatSide.Right, SeatSide.Opposite)
        val ys = args.map {
            SeatLabelDrawer.labelLocalY(it, 80f, 6f, 16f, 80f)
        }
        assertEquals(ys[0], ys[1], 1e-4f)
        assertEquals(ys[0], ys[2], 1e-4f)
    }

    @Test
    fun tightGutterFallsBackToHandAdjacent() {
        val ly = SeatLabelDrawer.labelLocalY(
            side = SeatSide.Left,
            handH = 80f,
            tileGap = 6f,
            labelH = 16f,
            edgeInset = 50f,
        )
        assertEquals(-(40f + 6f + 8f), ly, 1e-4f)
    }

    @Test
    fun rotationIsAboutTheComposedCenter() {
        val incoming = Matrix4().setToTranslation(240f, 16f, 0f)
        val dest = Matrix4()
        SeatLabelDrawer.composeCenteredTextTransform(
            dest = dest,
            incoming = incoming,
            cx = 100f,
            cy = 50f,
            rotationDeg = 90f,
            scale = 1f,
        )
        val p = Vector3(10f, 0f, 0f).mul(dest)
        assertEquals(340f, p.x, 1e-4f)
        assertEquals(76f, p.y, 1e-4f)
    }

    @Test
    fun hanGlyphIsLargerThanTheWindName() {
        // 20px wind-name line next to the 48px [Fonts.display] atlas: shrunk to
        // hit the ratio, and still taller than the name it sits beside.
        val nameH = 23f
        val hanNativeH = 48f
        val hanScale = SeatLabelDrawer.hanScaleFor(nameH, hanNativeH)
        assertTrue(hanScale < 1f, "the display atlas must never be upscaled")
        assertTrue(hanNativeH * hanScale > nameH, "Han must read larger than the wind name")
    }

    @Test
    fun hanGlyphTargetsTheRatioWhenTheAtlasAllows() {
        val nameH = 20f
        val hanScale = SeatLabelDrawer.hanScaleFor(nameH, hanNativeH = 100f)
        assertEquals(
            nameH * SeatLabelDrawer.HAN_NAME_RATIO * SeatLabelDrawer.HAN_SIZE_FACTOR,
            100f * hanScale,
            1e-3f,
        )
    }

    @Test
    fun hanGlyphDrawsBelowAtlasSizeForATallName() {
        // Active label (28px face) already reaches the ratio, so the fit caps
        // at 1 and the glyph then draws at HAN_SIZE_FACTOR of the atlas.
        assertEquals(
            SeatLabelDrawer.HAN_SIZE_FACTOR,
            SeatLabelDrawer.hanScaleFor(nameLineH = 40f, hanNativeH = 48f),
        )
    }

    @Test
    fun hanGlyphIsDroppedWithoutAFaceOrALabel() {
        assertEquals(0f, SeatLabelDrawer.hanScaleFor(nameLineH = 23f, hanNativeH = 0f))
        assertEquals(0f, SeatLabelDrawer.hanScaleFor(nameLineH = 0f, hanNativeH = 48f))
    }

    @Test
    fun gutterFitScaleReturnsOneWhenLabelFits() {
        val scale = SeatLabelDrawer.gutterFitScale(
            boxH = 40f,
            baseScale = 1f,
            active = false,
            activeScaleBoost = 1.08f,
            gutter = 50f,
            tileGap = 3f,
        )
        assertEquals(1f, scale, 1e-4f)
    }

    @Test
    fun gutterFitScaleClampsWhenLabelExceedsGutter() {
        // Gutter is 50, tileGap is 3, available is 50 - 6 = 44.
        // boxH = 88, baseScale = 1f. Peak is 88. fitScale = 44 / 88 = 0.5.
        val scale = SeatLabelDrawer.gutterFitScale(
            boxH = 88f,
            baseScale = 1f,
            active = false,
            activeScaleBoost = 1.08f,
            gutter = 50f,
            tileGap = 3f,
        )
        assertEquals(0.5f, scale, 1e-4f)
    }

    /**
     * The active seat's boost is a fixed size, not a peak over a pulse.
     *
     * It was `1 + pulseAmplitude` — a *peak*, because the label animated up to it and
     * back. Nothing animates now, so the reserved height is the label's actual size
     * and the gutter fit divides by the same number [draw] multiplies by. If the two
     * disagree the label either clips the gutter or sits under-sized in it.
     */
    @Test
    fun gutterFitScaleConsidersTheStaticActiveBoost() {
        // Available gutter = 50 - 6 = 44.
        // boxH = 40, boost 1.10. Peak = 44. Scale = 44 / 44 = 1.0.
        val scaleFit = SeatLabelDrawer.gutterFitScale(
            boxH = 40f,
            baseScale = 1f,
            active = true,
            activeScaleBoost = 1.10f,
            gutter = 50f,
            tileGap = 3f,
        )
        assertEquals(1f, scaleFit, 1e-4f)

        // boxH = 40, boost 1.20. Peak = 48 > 44. Scale = 44 / 48.
        val scaleClamp = SeatLabelDrawer.gutterFitScale(
            boxH = 40f,
            baseScale = 1f,
            active = true,
            activeScaleBoost = 1.20f,
            gutter = 50f,
            tileGap = 3f,
        )
        assertEquals(44f / 48f, scaleClamp, 1e-4f)
    }

    /**
     * The boost is a constant, not a function of elapsed time.
     *
     * The regression this pins is why the clock was deleted. A seat is always
     * active, so an animated active label meant the board's "still changing" gate
     * was permanently true and the picture only advanced when the player happened
     * to move the mouse. There is deliberately no `advance()` to tick, so the draw
     * scale can depend only on `active` — never on a clock.
     */
    @Test
    fun activeLabelScaleIsTimeIndependent() {
        val drawer = SeatLabelDrawer()
        val boost = drawer.activeScaleBoost
        assertEquals(boost, drawer.activeScaleBoost)
        assertTrue(boost > 1f, "the active seat must still read as emphasised")
    }

    @Test
    fun labelLocalYWithSafeInsetCentersInSafeGutter() {
        val ly = SeatLabelDrawer.labelLocalY(
            side = SeatSide.Left,
            handH = 80f,
            tileGap = 6f,
            labelH = 16f,
            edgeInset = 120f, // hand tile center at 120, inner face at 80
            safeInset = 40f,  // notch at 40
        )
        // Hand face at 80, notch at 40. Gutter = 40.
        // Center between 40 and 80 is 60.
        // ly = 60 - 120 = -60. World X = edgeInset + ly = 60 (well clear of notch 40).
        assertEquals(60f - 120f, ly, 1e-4f)
    }

}


