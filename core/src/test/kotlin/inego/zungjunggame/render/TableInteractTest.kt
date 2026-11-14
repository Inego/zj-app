package inego.zungjunggame.render

import inego.zungjunggame.render.table.TableInteract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableInteractTest {

    @Test
    fun lectureOverlayIsReadOnly() {
        val mode = TableInteract.forOverlay(boardOverlay = true, actionPreamble = false)
        assertEquals(TableInteract.ReadOnly, mode)
        assertFalse(mode.allowsHover)
        assertFalse(mode.allowsHits)
    }

    @Test
    fun actionPreambleOverlayIsPlay() {
        val mode = TableInteract.forOverlay(boardOverlay = true, actionPreamble = true)
        assertEquals(TableInteract.Play, mode)
        assertTrue(mode.allowsHover)
        assertTrue(mode.allowsHits)
    }

    @Test
    fun nonOverlayIsPlay() {
        assertEquals(
            TableInteract.Play,
            TableInteract.forOverlay(boardOverlay = false, actionPreamble = false),
        )
        assertEquals(
            TableInteract.Play,
            TableInteract.forOverlay(boardOverlay = false, actionPreamble = true),
        )
    }
}
