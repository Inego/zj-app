package inego.zungjunggame.input

import kotlin.test.Test
import kotlin.test.assertEquals

class InputNormalizerTest {

    private class FakeRaw(
        override val justTouched: Boolean = false,
        override val justReleased: Boolean = false,
        private val tx: Float = 0f,
        private val ty: Float = 0f,
        override val isTouched: Boolean = justTouched,
        override val touchCancelled: Boolean = false,
        override val pointerCount: Int = if (isTouched) 1 else 0,
        scrollAmount: Float = 0f,
        override val scrollX: Float = 0f,
        override val scrollY: Float = 0f,
        private val keys: List<Int> = emptyList(),
        override val backPressed: Boolean = false,
        override val ctrl: Boolean = false,
        override val shift: Boolean = false,
    ) : RawInput {
        // One-shot, like the real [inego.zungjunggame.app.GdxRawInput]: the
        // normalizer must read it exactly once or it emits a zero amount.
        private var pendingScroll: Float = scrollAmount
        override val scrollAmount: Float get() = pendingScroll.also { pendingScroll = 0f }

        override fun touchX(): Float = tx
        override fun touchY(): Float = ty
        override fun keysJustPressed(): List<Int> = keys
    }

    private val normalizer = InputNormalizer()

    @Test
    fun emptyFrameYieldsEmptyList() {
        assertEquals(emptyList(), normalizer.normalize(FakeRaw()))
    }

    @Test
    fun touchDownNormalizesToPointerDown() {
        val events = normalizer.normalize(FakeRaw(justTouched = true, tx = 3f, ty = 4f))
        assertEquals(listOf(InputEvent.PointerDown(3f, 4f)), events)
    }

    @Test
    fun releaseNormalizesToPointerUpThenTap() {
        val events = normalizer.normalize(FakeRaw(justReleased = true, tx = 5f, ty = 6f))
        assertEquals(
            listOf(InputEvent.PointerUp(5f, 6f), InputEvent.Tap(5f, 6f)),
            events,
        )
    }

    @Test
    fun scrollNormalizesOnlyWhenNonZero() {
        assertEquals(
            listOf(InputEvent.Scroll(-2f, 10f, 20f)),
            normalizer.normalize(FakeRaw(scrollAmount = -2f, scrollX = 10f, scrollY = 20f)),
        )
        assertEquals(emptyList(), normalizer.normalize(FakeRaw(scrollAmount = 0f)))
    }

    @Test
    fun keysCarryModifiers() {
        val events = normalizer.normalize(FakeRaw(keys = listOf(7, 9), ctrl = true, shift = true))
        assertEquals(
            listOf(
                InputEvent.KeyDown(7, ctrl = true, shift = true),
                InputEvent.KeyDown(9, ctrl = true, shift = true),
            ),
            events,
        )
    }

    @Test
    fun backIsAppendedLast() {
        val events = normalizer.normalize(
            FakeRaw(justTouched = true, keys = listOf(1), backPressed = true),
        )
        assertEquals(
            listOf(
                InputEvent.PointerDown(0f, 0f),
                InputEvent.KeyDown(1),
                InputEvent.SystemBack,
            ),
            events,
        )
    }

    @Test
    fun heldPointerNormalizesToMoveNotDown() {
        val events = normalizer.normalize(FakeRaw(isTouched = true, tx = 8f, ty = 9f))
        assertEquals(listOf(InputEvent.PointerMove(8f, 9f)), events)
    }

    @Test
    fun pointerCountCarriesOnDownAndMove() {
        assertEquals(
            listOf(InputEvent.PointerDown(1f, 2f, pointerCount = 3)),
            normalizer.normalize(FakeRaw(justTouched = true, tx = 1f, ty = 2f, pointerCount = 3)),
        )
        assertEquals(
            listOf(InputEvent.PointerMove(1f, 2f, pointerCount = 2)),
            normalizer.normalize(FakeRaw(isTouched = true, tx = 1f, ty = 2f, pointerCount = 2)),
        )
    }

    @Test
    fun touchCancelSuppressesReleaseAndReportsStillTouched() {
        val events = normalizer.normalize(
            FakeRaw(justReleased = true, touchCancelled = true, isTouched = true, tx = 4f, ty = 5f),
        )
        assertEquals(listOf(InputEvent.PointerCancel(stillTouched = true)), events)
    }

    @Test
    fun cancelledFrameNeverEmitsTap() {
        val events = normalizer.normalize(
            FakeRaw(justReleased = true, touchCancelled = true, tx = 4f, ty = 5f),
        )
        assertEquals(listOf(InputEvent.PointerCancel(stillTouched = false)), events)
    }
}
