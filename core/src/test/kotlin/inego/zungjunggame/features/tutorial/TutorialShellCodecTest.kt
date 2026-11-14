package inego.zungjunggame.features.tutorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tutorial shell route's state codec: the decode gates that keep an unreadable
 * value from reaching the widget.
 */
class TutorialShellCodecTest {

    @Test
    fun roundTripsEveryField() {
        val state = TutorialShellState(
            tocScrollY = 128f,
            drawerOpen = false,
            pullTabYFrac = 0.25f,
        )

        val loaded = TutorialShellCodec.decode(TutorialShellCodec.encode(state))
        assertEquals(state, loaded)
    }

    @Test
    fun negativeScrollAndOutOfRangePullTabClamp() {
        val loaded = TutorialShellCodec.decode("v1:-40.0:1:2.5")

        assertEquals(0f, loaded?.tocScrollY)
        assertEquals(true, loaded?.drawerOpen)
        assertEquals(1f, loaded?.pullTabYFrac)
    }

    @Test
    fun malformedAndUnknownVersionsDecodeAsNull() {
        assertNull(TutorialShellCodec.decode(""))
        assertNull(TutorialShellCodec.decode("junk"))
        assertNull(TutorialShellCodec.decode("v2:0.0:1:0.5"))
        assertNull(TutorialShellCodec.decode("v1:0.0:maybe:0.5"))
        assertNull(TutorialShellCodec.decode("v1:0.0:1"))
    }
}
