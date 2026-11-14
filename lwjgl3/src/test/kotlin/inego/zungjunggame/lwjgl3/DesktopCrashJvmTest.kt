package inego.zungjunggame.lwjgl3

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCrashJvmTest {

    @Test
    fun `needsRelaunch when ErrorFile or OnError is missing`() {
        assertTrue(DesktopCrashJvm.needsRelaunch(emptyList(), alreadyChild = false))
        assertTrue(
            DesktopCrashJvm.needsRelaunch(
                listOf("-XX:ErrorFile=/tmp/hs_err_pid%p.log"),
                alreadyChild = false,
            ),
        )
        assertFalse(
            DesktopCrashJvm.needsRelaunch(
                listOf(
                    "-XX:ErrorFile=/tmp/hs_err_pid%p.log",
                    "-XX:OnError=echo pid=%p",
                ),
                alreadyChild = false,
            ),
        )
        assertFalse(
            DesktopCrashJvm.needsRelaunch(emptyList(), alreadyChild = true),
        )
    }

    @Test
    fun `mergeArgs replaces existing crash flags instead of duplicating`() {
        val extra = DesktopCrashJvm.extraJvmArgs(File("/tmp/zj"))
        val merged = DesktopCrashJvm.mergeArgs(
            listOf("--enable-native-access=ALL-UNNAMED", "-XX:ErrorFile=/old/hs_err.log"),
            extra,
        )
        assertEquals(1, merged.count { it.startsWith("-XX:ErrorFile=") })
        assertTrue(merged.contains("--enable-native-access=ALL-UNNAMED"))
        assertTrue(merged.any { it.startsWith("-XX:ErrorFile=") && it.contains("/tmp/zj") })
    }
}
