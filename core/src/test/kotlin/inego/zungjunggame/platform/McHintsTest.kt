package inego.zungjunggame.platform

import kotlin.test.Test
import kotlin.test.assertFalse

class McHintsTest {
    /**
     * Off unless a launcher opts in. The default has to be the safe one because
     * the failure mode is not "no labels": on WasmGC `Thread.start` is a
     * cooperative fiber and the MC playout loop has no suspension point, so a
     * search started by default would freeze the whole tab.
     */
    @Test
    fun playoutsAreOffByDefault() {
        assertFalse(McHints.enabled)
    }
}
