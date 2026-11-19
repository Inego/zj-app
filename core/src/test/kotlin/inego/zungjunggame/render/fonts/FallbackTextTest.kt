package inego.zungjunggame.render.fonts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FallbackTextTest {

    @Test
    fun aClusterNoFaceDrawsBecomesOneBox() {
        val painted = FallbackText.substituteUndrawable(
            "A\uD83D\uDE00B",
            canDraw = { it == 'A'.code || it == 'B'.code },
            box = '?',
        )

        assertEquals("A?B", painted)
    }

    @Test
    fun aFlagIsOneBox() {
        // Two regional indicators: one grapheme cluster, one stand-in.
        val painted = FallbackText.substituteUndrawable(
            "\uD83C\uDDE9\uD83C\uDDEA",
            canDraw = { false },
            box = '?',
        )

        assertEquals("?", painted)
    }

    @Test
    fun aDrawableStringIsReturnedUnchanged() {
        val text = "\u5B8C\u5168"

        assertSame(text, FallbackText.substituteUndrawable(text, { true }, '?'))
    }

    @Test
    fun aClusterWithOneUndrawableMarkIsOneBox() {
        assertEquals(
            "?",
            FallbackText.substituteUndrawable("e\u0301", { it != 0x0301 }, '?'),
        )
    }

    @Test
    fun anUnpairedSurrogateIsOneBox() {
        assertEquals(
            "a?b",
            FallbackText.substituteUndrawable("a\uD83Db", { it != 0xD83D }, '?'),
        )
    }
}
