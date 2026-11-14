package inego.zungjunggame.lwjgl3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDataDirTest {
    @Test
    fun `parses the separated and joined forms`() {
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir", "C:/tmp/userA")),
            emptyList<String>() to "C:/tmp/userA",
        )
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir=C:/tmp/userA")),
            emptyList<String>() to "C:/tmp/userA",
        )
    }

    @Test
    fun `a flag without a value is dropped and imports nothing`() {
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir")),
            emptyList<String>() to null
        )
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir=")),
            emptyList<String>() to null
        )
        // A blank token is not consumed as the value; it stays an ordinary arg.
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir", "")),
            listOf("") to null,
        )
    }

    @Test
    fun `unrelated arguments are kept and the last value wins`() {
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--other", "--data-dir", "first", "y")),
            listOf("--other", "y") to "first",
        )
        assertEquals(
            DesktopDataDir.splitOverride(arrayOf("--data-dir=first", "--data-dir=second")),
            emptyList<String>() to "second",
        )
    }

    @Test
    fun `defaultRoot ends with the well-known folder name`() {
        assertEquals(DesktopDataDir.DEFAULT_DIR_NAME, DesktopDataDir.defaultRoot().name)
        assertNull(DesktopDataDir.splitOverride(emptyArray()).second)
    }
}
