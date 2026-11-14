package inego.zungjunggame.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage E: a presented frame is a complete frame.
 *
 * ### The defect
 *
 * `renderFrame` used to `ScreenUtils.clear(...)` and *then* test
 * `screenW <= 0f || screenH <= 0f`. libGDX swaps the buffer when `render()`
 * returns whether or not anything was drawn, so a zero-sized frame presented the
 * bare felt clear and nothing else. Measured 2026-09-29: a 28 s minimize
 * produced exactly two such frames and then the platform stopped delivering
 * frames entirely — so those two clears were the only thing that ever touched
 * the swapchain, and they are what the compositor had to show on return.
 *
 * The visible symptom and its cost were separable. The *cost* was the glyph
 * rebuild Stage B removed, so after B the return frame is fast. The *picture*
 * was this clear, and B could not touch it: the rebuild was never what put the
 * felt there.
 *
 * ### Why one of these tests reads the source
 *
 * E1 is an **ordering** claim — the guard must sit above the clear, not below
 * it. Inlining the condition back into `renderFrame` as `if (worldWidth <= 0f)`
 * satisfies [shouldSkipPaint] while reintroducing the whole bug, and no JVM
 * test in this module can execute a `renderFrame` of that size. So the ordering
 * is asserted on the source text, the same concession
 * [DesktopGpuContextPolicyTest] makes for the launcher install.
 *
 * The behavioural half is still tested for real ([shouldSkipPaint]), because
 * that is the part a reader would otherwise take on faith.
 */
class PresentInvariantTest {

    private fun appRuntimeSource(): String {
        val file = File("src/main/kotlin/inego/zungjunggame/app/AppRuntime.kt")
        assertTrue(file.exists(), "expected AppRuntime.kt (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    private fun renderFrameBody(): String {
        val source = appRuntimeSource()
        val start = source.indexOf("    fun renderFrame() {")
        assertTrue(start > 0, "could not find renderFrame() in AppRuntime.kt")
        // The body up to the next top-level function; good enough to locate the
        // clear/guard pair, and it fails loudly if the layout changes.
        val end = source.indexOf("\n    /**", start)
        return source.substring(start, if (end > start) end else source.length)
    }

    // ---- the rule --------------------------------------------------------

    @Test
    fun aZeroSizedWorldIsSkipped() {
        assertTrue(shouldSkipPaint(0f, 0f), "E1: a 0x0 world must not paint")
        assertTrue(shouldSkipPaint(0f, 1009f), "E1: zero width alone is enough")
        assertTrue(shouldSkipPaint(1920f, 0f), "E1: zero height alone is enough")
        // Negative sizes are what a minimized driver can report; they are not a
        // rendering size either.
        assertTrue(shouldSkipPaint(-1f, 1009f), "E1: a negative extent is not paintable")
        assertTrue(shouldSkipPaint(1920f, -1f), "E1: a negative extent is not paintable")
    }

    @Test
    fun aRealWorldIsPainted() {
        assertEquals(false, shouldSkipPaint(1920f, 1009f))
        assertEquals(false, shouldSkipPaint(1f, 1f), "1x1 is degenerate but paintable")
    }

    // ---- the ordering, which is the actual bug ---------------------------

    @Test
    fun theZeroSizeGuardPrecedesTheClear() {
        val body = renderFrameBody()
        val guardAt = body.indexOf("if (shouldSkipPaint(")
        val clearAt = body.indexOf("ScreenUtils.clear(")
        assertTrue(guardAt > 0, "E1: renderFrame must test shouldSkipPaint before clearing")
        assertTrue(clearAt > 0, "E1: expected the felt clear in renderFrame")
        assertTrue(
            guardAt < clearAt,
            "E1: the zero-size guard must come BEFORE ScreenUtils.clear. libGDX swaps " +
                "the buffer when render() returns, so clearing first and bailing after " +
                "presents a felt-only frame — measured 2026-09-29 as the only thing that " +
                "touched the swapchain across a 28 s minimize.",
        )
    }

    @Test
    fun theSkippedFrameDoesNotAskForAPresent() {
        // E1's second half. `scheduleNextPresent` on the bail-out path was
        // harmless while the rebuild was still there; now that the path does
        // nothing at all, asking for a frame only burns a present the
        // compositor will not show, and consuming a paint request would let a
        // settled app come back showing whatever the swapchain already held.
        val body = renderFrameBody()
        val guardAt = body.indexOf("if (shouldSkipPaint(")
        assertTrue(guardAt > 0, "E1: expected the shouldSkipPaint guard in renderFrame")
        val bailPath = stripLineComments(body.substring(guardAt))
        val bailAt = bailPath.indexOf("return")
        assertTrue(bailAt > 0, "E1: expected the skipped frame to return early")
        val statements = bailPath.substring(0, bailAt)
        assertTrue(
            !statements.contains("scheduleNextPresent"),
            "E1: the skipped frame must not call scheduleNextPresent — a minimized " +
                "window receives no frames anyway, and consuming a paint request " +
                "here would suppress the repaint the return edge needs.",
        )
        assertTrue(
            !statements.contains("ScreenUtils.clear"),
            "E1: the skipped frame must not clear. That is the whole defect.",
        )
    }

    /**
     * Drops `//` comments, trailing content included.
     *
     * Not cosmetic: this file's own guard mentions `scheduleNextPresent` in a
     * comment explaining why it is *not* called, and a substring scan over the
     * raw text fails on that. A source-shape assertion must look at code.
     */
    private fun stripLineComments(code: String): String =
        code.lines().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at >= 0) line.substring(0, at) else line
        }
}
