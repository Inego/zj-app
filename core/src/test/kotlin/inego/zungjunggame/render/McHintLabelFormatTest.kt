package inego.zungjunggame.render

import inego.zungjunggame.render.hint.McHintLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McHintLabelFormatTest {

    @Test
    fun formatMcHintAvgDeltaUsesFewerDecimalsAsMagnitudeGrows() {
        assertEquals("+0.45", McHintLabels.formatAvgDelta(0.45))
        assertEquals("-1.23", McHintLabels.formatAvgDelta(-1.234))
        assertEquals("+12.3", McHintLabels.formatAvgDelta(12.34))
        assertEquals("-99.9", McHintLabels.formatAvgDelta(-99.94))
        assertEquals("+100", McHintLabels.formatAvgDelta(100.4))
        assertEquals("-1390", McHintLabels.formatAvgDelta(-1390.0))
        assertEquals("+1440", McHintLabels.formatAvgDelta(1440.0))
    }

    @Test
    fun formatMcHintAvgDeltaStaysShorterThanFixedThreeDecimals() {
        // Portrait hand tiles cannot fit strings like "+1234.567".
        val compact = McHintLabels.formatAvgDelta(1234.567)
        assertEquals("+1235", compact)
        assertTrue(compact.length <= 6, "expected short label, got $compact")
    }

}
