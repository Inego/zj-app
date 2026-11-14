package inego.zungjunggame.render.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderTest {

    @Test
    fun customRangeAndClampedInitialValue() {
        val sliderLow = Slider(min = 0, max = 5, initialValue = -2)
        assertEquals(0, sliderLow.value)

        val sliderHigh = Slider(min = 0, max = 5, initialValue = 10)
        assertEquals(5, sliderHigh.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRangeThrows() {
        Slider(min = 3, max = 1)
    }

    @Test
    fun setValueClampingAndReturnStatus() {
        val slider = Slider(min = 0, max = 3, initialValue = 1)

        // Same value returns false
        assertFalse(slider.setValue(1))
        assertEquals(1, slider.value)

        // New valid value returns true
        assertTrue(slider.setValue(2))
        assertEquals(2, slider.value)

        // Clamps high
        assertTrue(slider.setValue(10))
        assertEquals(3, slider.value)

        // Setting 3 again returns false
        assertFalse(slider.setValue(3))

        // Clamps low
        assertTrue(slider.setValue(-5))
        assertEquals(0, slider.value)
    }

    @Test
    fun layoutAndContains() {
        val slider = Slider(min = 0, max = 3, initialValue = 1)
        slider.layout(left = 50f, bottom = 100f, width = 200f, height = 50f)

        assertEquals(50f, slider.left, 0.001f)
        assertEquals(100f, slider.bottom, 0.001f)
        assertEquals(200f, slider.width, 0.001f)
        assertEquals(50f, slider.height, 0.001f)

        assertTrue(slider.contains(50f, 100f))
        assertTrue(slider.contains(150f, 125f))
        assertTrue(slider.contains(250f, 150f))

        assertFalse(slider.contains(49f, 125f))
        assertFalse(slider.contains(251f, 125f))
        assertFalse(slider.contains(150f, 99f))
        assertFalse(slider.contains(150f, 151f))
    }

    @Test
    fun valueForPositionMapsSteps() {
        val slider = Slider(min = 0, max = 3, initialValue = 0)
        slider.layout(left = 0f, bottom = 0f, width = 300f, height = 50f)

        val tLeft = slider.trackLeft
        val tW = slider.trackWidth

        // Exact step positions
        assertEquals(0, slider.valueForPosition(tLeft))
        assertEquals(1, slider.valueForPosition(tLeft + tW * (1f / 3f)))
        assertEquals(2, slider.valueForPosition(tLeft + tW * (2f / 3f)))
        assertEquals(3, slider.valueForPosition(tLeft + tW))

        // Beyond ends
        assertEquals(0, slider.valueForPosition(tLeft - 100f))
        assertEquals(3, slider.valueForPosition(tLeft + tW + 100f))

        // Midpoint rounding
        // Between 0 and 1/3 (midpoint = 1/6)
        assertEquals(0, slider.valueForPosition(tLeft + tW * 0.15f))
        assertEquals(1, slider.valueForPosition(tLeft + tW * 0.18f))

        // Between 1/3 and 2/3 (midpoint = 3/6 = 0.5)
        assertEquals(1, slider.valueForPosition(tLeft + tW * 0.48f))
        assertEquals(2, slider.valueForPosition(tLeft + tW * 0.52f))
    }

    @Test
    fun xForValueCalculatesCorrectPositions() {
        val slider = Slider(min = 0, max = 3, initialValue = 0)
        slider.layout(left = 0f, bottom = 0f, width = 300f, height = 50f)

        val tLeft = slider.trackLeft
        val tW = slider.trackWidth

        assertEquals(tLeft, slider.xForValue(0), 0.001f)
        assertEquals(tLeft + tW * (1f / 3f), slider.xForValue(1), 0.001f)
        assertEquals(tLeft + tW * (2f / 3f), slider.xForValue(2), 0.001f)
        assertEquals(tLeft + tW, slider.xForValue(3), 0.001f)

        // Clamped inputs
        assertEquals(tLeft, slider.xForValue(-1), 0.001f)
        assertEquals(tLeft + tW, slider.xForValue(10), 0.001f)
    }

    @Test
    fun pointerDownDragAndUp() {
        val slider = Slider(min = 0, max = 3, initialValue = 0)
        slider.layout(left = 0f, bottom = 0f, width = 300f, height = 50f)

        val tLeft = slider.trackLeft
        val tW = slider.trackWidth

        // Down outside does nothing
        assertNull(slider.onPointerDown(-50f, 25f))
        assertFalse(slider.isDragging)
        assertEquals(0, slider.value)

        // Down inside at position 2
        val downVal = slider.onPointerDown(tLeft + tW * (2f / 3f), 25f)
        assertEquals(2, downVal)
        assertEquals(2, slider.value)
        assertTrue(slider.isDragging)

        // Drag to position 3
        val dragVal = slider.onPointerDrag(tLeft + tW)
        assertEquals(3, dragVal)
        assertEquals(3, slider.value)
        assertTrue(slider.isDragging)

        // Pointer up finishes drag
        slider.onPointerUp()
        assertFalse(slider.isDragging)
        assertEquals(3, slider.value)

        // Drag after up returns null
        assertNull(slider.onPointerDrag(tLeft))
        assertEquals(3, slider.value)
    }
}
