package inego.zungjunggame.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Stage 0 instrumentation is gone, and what it was guarding is not.
 *
 * ### What this change was
 *
 * §7 of the plan said to remove the temporary diagnostics "once Stage D and E are
 * verified", in a dedicated change. Both closed, so this is that change. It
 * deleted `ResumeDiagnostics` (235 lines), its 4 tests, 15 mark call sites across
 * three files, and the web's `document.title` ring.
 *
 * ### Why deleting it needed a test
 *
 * Two of the things in that neighbourhood are not instrumentation, and the
 * removal had to keep both. Neither can be checked by running the app from a
 * unit test, so they are asserted on the source — the same concession
 * [PresentInvariantTest] and [DesktopGpuContextPolicyTest] already make, and for
 * the same reason: the thing is a `main()`-time side effect or a browser
 * listener, and there is no JVM in which to observe it.
 *
 * The risk is asymmetric. A leftover diagnostic is noise; a deleted load-bearing
 * line is a window that resumes and never repaints, or a web tab that goes felt
 * on a real GPU reset. So the assertions here are all "this must still be
 * present", and the removal itself is asserted only where it is cheap.
 */
class DiagnosticsRemovedTest {

    private fun source(relative: String): String {
        val file = File(relative)
        assertTrue(file.exists(), "expected $relative (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    /**
     * The file's code, with comments removed.
     *
     * Not cosmetic. Several assertions below are "this text must be gone", and
     * the files that *should* be gone from are the ones whose KDoc explains the
     * removal — so the KDoc mentions `document.title`, `ResumeDiagnostics` and
     * `logFocus` by name. A substring scan over raw text fails on its own
     * documentation. [PresentInvariantTest.stripLineComments] makes the same
     * point about a naive scan; this strips block comments as well, since the
     * prose that matters here is KDoc.
     */
    private fun code(relative: String): String = stripComments(source(relative))

    private fun stripComments(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { line ->
                val at = line.indexOf("//")
                if (at >= 0) line.substring(0, at) else line
            }

    private val coreRoot = "src/main/kotlin/inego/zungjunggame/"

    // ---- the load-bearing survivors ---------------------------------------

    @Test
    fun theResumeEdgeStillAsksForAFrame() {
        // With `setContinuousRendering(false)` a backgrounded app
        // is by definition not animating, and the other two `requestRendering()`
        // callers only cover the first frame and the not-yet-ready boot phase. So
        // this is the *only* thing that repaints the window on return, and the
        // diagnostic that used to sit beside it is what was removed — not this.
        val main = source("${coreRoot}Main.kt")
        // Bounded to the function, not "everything after the signature". The first
        // version of this used substringAfter, which also captured render()'s
        // `finally` — and render() has its own requestRendering() call, so deleting
        // the one in resume() left the assertion GREEN. Falsification mutation A
        // is what found it; a substring scan that reaches past the claim tests
        // nothing.
        val start = main.indexOf("override fun resume() {")
        assertTrue(start > 0, "expected Main.resume() in Main.kt")
        val resume = main.substring(start, main.indexOf("\n    override fun", start + 1))
        assertTrue(
            resume.contains("Gdx.graphics.requestRendering()"),
            "Main.resume() must still call requestRendering(). The " +
                "diagnostic mark beside it was removable; the call was not. Removing " +
                "it leaves the window resumed and never repainting.",
        )
        assertTrue(
            !resume.contains("ResumeDiagnostics"),
            "§7: Main.resume() should carry no diagnostics left.",
        )
    }

    @Test
    fun theWebStillCancelsTheLostContext() {
        // The single most important line in the web backend. `webglcontextlost`
        // is only followed by `webglcontextrestored` if the lost event is
        // cancelled — which is what makes the durable install safe, because it
        // switches off the frame-gap rebuild on the assumption that a real loss
        // repairs itself. Take this out and a GPU driver reset is a permanent
        // black canvas with nothing reporting it.
        val signal = source(
            "../teavm/src/main/java/com/github/xpenatan/gdx/teavm/backends/web/WebGpuContextSignal.java",
        )
        assertTrue(
            signal.contains("evt.preventDefault()"),
            "The web's only context-loss repair path depends on cancelling the " +
                "lost event. Without preventDefault() webglcontextrestored never " +
                "fires and a real context loss is unrecoverable.",
        )
        assertTrue(
            signal.contains("webglcontextlost") && signal.contains("webglcontextrestored"),
            "Both context listeners must survive the §7 cleanup.",
        )
    }

    @Test
    fun theWebStillReportsContextLoss() {
        // §7 removed the `document.title` mirror but deliberately kept these
        // lines: they are the only evidence that the listeners attached, and
        // §9.1's measured negative ("listener live, nothing fired" — which is
        // what proved the web stall was ours, not the browser's) stops being
        // reproducible from a build that stays silent.
        val signal = source(
            "../teavm/src/main/java/com/github/xpenatan/gdx/teavm/backends/web/WebGpuContextSignal.java",
        )
        assertTrue(
            signal.contains("""WebResumeDiagnostics.log("listeners"""") &&
                signal.contains("""WebResumeDiagnostics.log("contextlost"""") &&
                signal.contains("""WebResumeDiagnostics.log("contextrestored""""),
            "The context-loss log lines are the control for §9.1 and must survive.",
        )
    }

    @Test
    fun theWebStillInstallsTheDurablePolicy() {
        val launcher = source("../teavm/src/main/kotlin/inego/zungjunggame/teavm/TeaLauncher.kt")
        assertTrue(
            launcher.contains("GpuContextPolicy.factory") && launcher.contains("WasmGpuContexts"),
            "W1/W2: the web installs the durable policy precisely because " +
                "WebGpuContextSignal repairs a real loss. Removing the diagnostic " +
                "seam next to it must not take the factory with it.",
        )
    }

    @Test
    fun theZeroSizeGuardStillPrecedesTheClear() {
        // Stage E's actual bug, restated here because the cleanup edited the same
        // method. PresentInvariantTest owns the deep version; this is the tripwire
        // that says a future §7-style edit did not reorder it.
        val body = source("${coreRoot}app/AppRuntime.kt")
            .substringAfter("    fun renderFrame() {")
        val guardAt = body.indexOf("if (shouldSkipPaint(")
        val clearAt = body.indexOf("ScreenUtils.clear(")
        assertTrue(guardAt > 0 && clearAt > 0 && guardAt < clearAt, "E1: guard before clear")
    }

    // ---- the removals ------------------------------------------------------

    @Test
    fun noResumeDiagnosticsSurvives() {
        val stale = listOf(
            "${coreRoot}Main.kt",
            "${coreRoot}app/AppRuntime.kt",
            "../lwjgl3/src/main/kotlin/inego/zungjunggame/lwjgl3/DesktopWindowController.kt",
            "../teavm/src/main/kotlin/inego/zungjunggame/teavm/TeaLauncher.kt",
        ).filter { path ->
            val text = code(path)
            text.contains("ResumeDiagnostics") || text.contains("ZjResume")
        }

        assertTrue(
            stale.isEmpty(),
            "§7: these still reference the deleted timeline: $stale",
        )
        assertTrue(
            !File("${coreRoot}app/ResumeDiagnostics.kt").exists(),
            "§7: ResumeDiagnostics.kt should be deleted.",
        )
        // A bare identifier is not the only way to fail this: a stale `import` or
        // a leftover fully-qualified call would both be a compile error, and an
        // unused import is a warning Gradle may not surface. The mutation for
        // this test puts a *call site* in TeaLauncher.kt, because that is the
        // only surviving module that cannot see core's package.
    }

    @Test
    fun theTitleMirrorIsGoneButTheLoggerIsNot() {
        val web = code(
            "../teavm/src/main/java/com/github/xpenatan/gdx/teavm/backends/web/WebResumeDiagnostics.java",
        )
        assertTrue(
            !web.contains("__zjDiagAppend") && !web.contains("document.title"),
            "§7: the document.title ring is removed — a global window hook on the " +
                "boot path, for a mirror nothing reads.",
        )
        assertTrue(
            web.contains("static void log(") && web.contains("static String describe("),
            "The context-loss logger must survive; only the mirror goes.",
        )
    }

    @Test
    fun theDesktopFocusMarksAreGone() {
        val controller = code(
            "../lwjgl3/src/main/kotlin/inego/zungjunggame/lwjgl3/DesktopWindowController.kt",
        )
        // `logFocus` is the removed timeline helper. Its output was exactly
        // `"ZjFocus: $event ..."` — colon, no brackets — so that format is the
        // timeline's signature. The foreground-grab feature that later landed
        // logs `[ZjFocus]` (brackets) when it cannot take the foreground; that
        // survivor is window-state behaviour, not instrumentation, so the ban
        // targets the timeline's colon format rather than the bare tag.
        assertTrue(
            !controller.contains("logFocus") && !controller.contains("ZjFocus:"),
            "§7: the alt-tab focus timeline is removable instrumentation.",
        )
        // The real work in the same file is not.
        assertTrue(
            controller.contains("override fun maximized(") &&
                controller.contains("captureWindowedGeometryIfSafe()"),
            "The maximize override and the geometry bookkeeping are window-state " +
                "persistence, not instrumentation — they must survive.",
        )
    }

    @Test
    fun theDeadTouchLogHooksAreGone() {
        val platformInput = code("${coreRoot}app/PlatformInput.kt")
        assertTrue(
            !platformInput.contains("touchHeldForLog") &&
                !platformInput.contains("touchStartedOnTargetForLog"),
            "Both returned a constant false into a log line; the retired gesture " +
                "state no longer tracks touch, so the fields were always false.",
        )
        assertTrue(
            platformInput.contains("fun logLifecycle("),
            "logLifecycle is NOT instrumentation — it has 24 live call " +
                "sites across AppGraph and AppRuntime. It stays.",
        )
    }
}
