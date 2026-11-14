package inego.zungjunggame.render

import inego.zungjunggame.render.tiles.TileEasing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileEasingTest {

    @Test
    fun easeOutCubicBounds() {
        assertEquals(0f, TileEasing.easeOutCubic(0f), 1e-5f)
        assertEquals(1f, TileEasing.easeOutCubic(1f), 1e-5f)
        assertEquals(1f, TileEasing.easeOutCubic(2f), 1e-5f)
        assertEquals(0f, TileEasing.easeOutCubic(-1f), 1e-5f)
        val mid = TileEasing.easeOutCubic(0.5f)
        assertTrue(mid > 0.5f) // ease-out is ahead of linear at mid
        assertTrue(mid < 1f)
    }

    @Test
    fun easeInOutCubicSymmetricAndDerivativePeak() {
        assertEquals(0f, TileEasing.easeInOutCubic(0f), 1e-5f)
        assertEquals(1f, TileEasing.easeInOutCubic(1f), 1e-5f)
        assertEquals(0.5f, TileEasing.easeInOutCubic(0.5f), 1e-5f)
        // Behind linear early, ahead late (S-curve).
        assertTrue(TileEasing.easeInOutCubic(0.25f) < 0.25f)
        assertTrue(TileEasing.easeInOutCubic(0.75f) > 0.75f)
        assertEquals(0f, TileEasing.easeInOutCubicDerivative(0f), 1e-5f)
        assertEquals(0f, TileEasing.easeInOutCubicDerivative(1f), 1e-5f)
        assertEquals(3f, TileEasing.easeInOutCubicDerivative(0.5f), 1e-4f)
    }

    @Test
    fun angleLerpTakesShortestPath() {
        // 350° → 10° should go +20°, not -340°.
        val mid = TileEasing.angleLerpDeg(350f, 10f, 0.5f)
        val delta = abs(((mid % 360f) + 360f) % 360f - 0f)
        // Midpoint near 0°.
        assertTrue(delta < 5f || abs(delta - 360f) < 5f)
        assertEquals(20f, TileEasing.angleDeltaDeg(350f, 10f), 1e-4f)
        // Shortest path 350° → 10° is +20° (not -340°).
        assertEquals(20f, TileEasing.angleDeltaSignedDeg(350f, 10f), 1e-4f)
        assertEquals(-20f, TileEasing.angleDeltaSignedDeg(10f, 350f), 1e-4f)
    }

    @Test
    fun faceDrawParamsFold() {
        val (open0, s0) = TileEasing.faceDrawParams(0f)
        assertFalse(open0)
        assertEquals(1f, s0, 1e-5f)

        val (openMid, sMid) = TileEasing.faceDrawParams(0.5f)
        assertTrue(openMid)
        assertEquals(0f, sMid, 1e-5f)

        val (open1, s1) = TileEasing.faceDrawParams(1f)
        assertTrue(open1)
        assertEquals(1f, s1, 1e-5f)
    }
}
