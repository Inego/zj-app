package inego.zungjunggame.render.table

import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.seats.SeatSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The brighter panel stretches from the measured tiles out to that seat's own
 * screen edge. On the other three sides the air is one fifth of the tile height,
 * which is the bounds' extent across that edge.
 */
class ActiveSeatFeltTest {

    @Test
    fun eachSideStretchesToItsOwnEdgeAndWrapsTheTiles() {
        val safe = SafeArea.Insets()
        // Bottom tiles are 60 tall, so the air above them and off both ends is 12.
        // The south edge is the screen.
        assertRect(
            floatArrayOf(388f, 0f, 324f, 152f),
            ActiveSeatFelt.band(SeatSide.Bottom, W, H, box(400f, 80f, 700f, 140f), safe),
        )
        // Opposite tiles are 80 tall: air is 16, and the panel runs up to the top edge.
        assertRect(
            floatArrayOf(384f, 624f, 332f, 176f),
            ActiveSeatFelt.band(SeatSide.Opposite, W, H, box(400f, 640f, 700f, 720f), safe),
        )
        // Left tiles are 60 across: air is 12 above and below, and the panel runs to x = 0.
        assertRect(
            floatArrayOf(0f, 288f, 142f, 224f),
            ActiveSeatFelt.band(SeatSide.Left, W, H, box(70f, 300f, 130f, 500f), safe),
        )
        // Right tiles are 70 across: air is 14, and the panel runs to the right edge.
        assertRect(
            floatArrayOf(846f, 286f, 154f, 228f),
            ActiveSeatFelt.band(SeatSide.Right, W, H, box(860f, 300f, 930f, 500f), safe),
        )
    }

    @Test
    fun onlyTheSeatsOwnInsetMovesItsPanel() {
        val tiles = box(400f, 80f, 700f, 140f)
        val plain = ActiveSeatFelt.band(SeatSide.Bottom, W, H, tiles, SafeArea.Insets())
        val foreign = ActiveSeatFelt.band(
            SeatSide.Bottom, W, H, tiles,
            SafeArea.Insets(left = 40f, top = 40f, right = 40f),
        )
        assertRect(floatArrayOf(388f, 0f, 324f, 152f), plain)
        assertRect(requireNotNull(plain), foreign)

        val notched = ActiveSeatFelt.band(
            SeatSide.Bottom, W, H, tiles,
            SafeArea.Insets(bottom = 40f),
        )
        assertRect(floatArrayOf(388f, 40f, 324f, 112f), notched)

        // The notch is past the tiles' far face: no inverted quad.
        assertNull(
            ActiveSeatFelt.band(
                SeatSide.Bottom, W, H, tiles,
                SafeArea.Insets(bottom = 200f),
            ),
        )

        val right = ActiveSeatFelt.band(
            SeatSide.Right, W, H, box(860f, 300f, 930f, 500f),
            SafeArea.Insets(right = 30f),
        )
        assertRect(floatArrayOf(846f, 286f, 124f, 228f), right)
    }

    @Test
    fun aMeldWidensThePanelAlongTheEdgeAndLeavesTheStretch() {
        for (side in SeatSide.entries) {
            val vertical = side == SeatSide.Left || side == SeatSide.Right
            val base = box(400f, 200f, 600f, 280f)
            val melded = if (vertical) {
                box(400f, 200f, 600f, 320f)
            } else {
                box(400f, 200f, 640f, 280f)
            }
            val a = requireNotNull(ActiveSeatFelt.band(side, W, H, base, SafeArea.Insets()))
            val b = requireNotNull(ActiveSeatFelt.band(side, W, H, melded, SafeArea.Insets()))
            if (vertical) {
                assertEquals(a[0], b[0], 0.01f, "$side stretch origin")
                assertEquals(a[2], b[2], 0.01f, "$side stretch depth")
                assertEquals(40f, b[3] - a[3], 0.01f, "$side along growth")
            } else {
                assertEquals(a[1], b[1], 0.01f, "$side stretch origin")
                assertEquals(a[3], b[3], 0.01f, "$side stretch depth")
                assertEquals(40f, b[2] - a[2], 0.01f, "$side along growth")
            }
        }
    }

    @Test
    fun nothingToDrawYieldsNoPanel() {
        val tiles = box(400f, 80f, 700f, 140f)
        val safe = SafeArea.Insets()
        assertNull(ActiveSeatFelt.band(SeatSide.Bottom, 0f, H, tiles, safe))
        assertNull(ActiveSeatFelt.band(SeatSide.Bottom, W, 0f, tiles, safe))
        assertNull(ActiveSeatFelt.band(SeatSide.Bottom, W, H, box(700f, 80f, 400f, 140f), safe))
        assertNull(ActiveSeatFelt.band(SeatSide.Left, W, H, floatArrayOf(1f, 2f, 3f), safe))
    }

    @Test
    fun theWashLightensTheFelt() {
        val wash = ActiveSeatFelt.wash()
        assertEquals(1f, wash[0], 0.001f)
        assertEquals(1f, wash[1], 0.001f)
        assertEquals(1f, wash[2], 0.001f)
        assertTrue(wash[3] > 0.05f && wash[3] < 0.5f, "a white wash, not a slab and not a tint of black")
    }

    private fun box(minX: Float, minY: Float, maxX: Float, maxY: Float) =
        floatArrayOf(minX, minY, maxX, maxY)

    private fun assertRect(expected: FloatArray, actual: FloatArray?) {
        assertNotNull(actual)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 0.01f, "rect[$i]")
        }
    }

    private companion object {
        const val W = 1000f
        const val H = 800f
    }
}
