package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A missed blank or a pause must not fast-forward in-flight motion.
 */
class FrameDeltaTest {
    @Test
    fun clampLimitsAStallToOneShortStep() {
        assertEquals(0f, FrameDelta.clamp(-1f))
        assertEquals(0f, FrameDelta.clamp(Float.NaN))
        assertEquals(0.016f, FrameDelta.clamp(0.016f))
        assertEquals(FrameDelta.MAX_SEC, FrameDelta.clamp(2f))
    }
}
