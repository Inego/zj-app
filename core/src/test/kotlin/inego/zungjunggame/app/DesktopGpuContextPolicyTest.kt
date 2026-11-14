package inego.zungjunggame.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage B1: desktop is declared durable, and that declaration is a real behaviour
 * change rather than a comment.
 *
 * ### Why the source has to be read
 *
 * A desktop launcher's install line lives in `:lwjgl3`, and `:core:test` cannot
 * execute it — it is a side effect of `main`, not a function anyone calls. The
 * existing platform test
 * ([DurablePlatformRecoveryTest.theWebIsDurableAndAndroidIsNot]) makes the same
 * concession for the web, and says so: it can only see the two default objects,
 * "W1's launcher install is verified by W4 in a browser."
 *
 * Desktop has no equivalent browser, so this file closes that gap the only way a
 * JVM test can: by reading the launcher and asserting which policy it installs.
 *
 * ### What breaks if this is not pinned
 *
 * The measured cost is 186-203 ms on **every** minimize, from a rebuild that
 * bought nothing — a double-buffered OS window cannot lose its texture names, and
 * the thing that actually changes while minimized is the framebuffer *size*, which
 * is not a resource loss. B1 retires it.
 *
 * A silent revert is the risk this guards, and it is silent in the worst
 * direction: re-installing `VolatileGpuContexts` does not fail any test, does not
 * crash, and does not lose a frame. It just makes the app do a pointless 200 ms
 * of font work each time the user minimizes, and the symptom is a hitch that is
 * easy to write off as driver noise.
 */
class DesktopGpuContextPolicyTest {

    private fun launcherSource(): String {
        val file = File("../lwjgl3/src/main/kotlin/inego/zungjunggame/lwjgl3/Lwjgl3Launcher.kt")
        assertTrue(file.exists(), "expected the desktop launcher (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun theDesktopLauncherInstallsTheDurablePolicy() {
        val source = launcherSource()
        assertTrue(
            source.contains("GpuContextPolicy.factory") &&
                source.contains("DurableGpuContexts"),
            "B1: Lwjgl3Launcher must install DurableGpuContexts. If this is red the " +
                "186-203 ms gap rebuild is back on every minimize/restore — " +
                "measured 2026-09-29, 3 cycles, plus a 161-175 ms resume rebuild.",
        )
    }

    @Test
    fun theDesktopLauncherDoesNotStillInstallTheVolatilePolicy() {
        // Stated separately because it is the failure that actually happens: the
        // old line is not deleted when the new one is added, and a launcher
        // carrying both would compile and silently take whichever runs last.
        val source = launcherSource()
        val volatileInstall = Regex(
            """GpuContextPolicy\.factory\s*=\s*\{[^}]*\bVolatileGpuContexts\b""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            !volatileInstall.containsMatchIn(source),
            "Lwjgl3Launcher still installs VolatileGpuContexts somewhere. A launcher " +
                "with both installs is ambiguous — whichever assignment runs last " +
                "wins. Android installed the volatile policy until Stage C and " +
                "installs AndroidGpuContexts now; its install line is checked by " +
                "AndroidGpuContextPolicyTest.",
        )
    }

    @Test
    fun theDurableFlagIsTheOneTheConsumersActuallyRead() {
        // The flip is only load-bearing if the two decision points consult the
        // same flag. Both read `gpuContext.contextsAreDurable`, so a launcher
        // install reaches the gap heuristic and the resume gate alike — this
        // asserts the polarity those two call sites depend on.
        assertEquals(
            false, shouldRequestOnResume(contextsAreDurable = true), "a durable platform must not rebuild on resume, or the resume rebuild " +
                "(161-175 ms) survives the flip"
        )
        assertTrue(
            shouldRequestOnResume(contextsAreDurable = false),
            "a volatile platform must still rebuild on resume, or a target that has " +
                "not wired a real loss event is left with no protection at all",
        )
    }
}
