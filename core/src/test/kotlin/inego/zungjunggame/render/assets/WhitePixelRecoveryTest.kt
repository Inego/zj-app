package inego.zungjunggame.render.assets

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant Stage G got wrong the first time, which shipped a green felt
 * instead of the menu on **first load** — with no context loss ever firing.
 *
 * ### What happened
 *
 * Stage G consolidated 28 per-component 1×1 white textures into one shared
 * [WhitePixel] and registered its recovery. The pre-G build served its menu from
 * an equivalent dist; the Stage G build showed green felt from the first frame.
 *
 * The cause was not the sharing. It was **caching**. A cold start runs
 * `rebuildFaces("new runtime")`, which includes [WhitePixel.invalidate]; every
 * component that had *already* captured `WhitePixel.get()` into a field —
 * `ConnectorCurveDrawer`, `ClaimActionPanel` — was left holding a GL name the
 * recovery had just declared dead. The next `batch.draw(thatTexture, …)` failed
 * with `bindTexture: INVALID_OPERATION` and the frame painted the felt and
 * nothing else.
 *
 * This is exactly the trap the `Canvas` KDoc warns about, and it is trivially
 * reintroducible: `val x: Texture = WhitePixel.get()` reads as harmless.
 *
 * ### What these pin
 *
 * The *decision* — a shared, invalidatable resource is read through a provider,
 * never captured. There is no GL context in a JVM test, so it cannot observe a
 * dead texture; it asserts the source-level property that makes the runtime bug
 * impossible, so a future field capture fails review *and* CI.
 */
class WhitePixelRecoveryTest {

    private fun source(relativePath: String): String {
        val file = File("../core/src/main/kotlin/inego/zungjunggame/$relativePath")
        assertTrue(file.exists(), "expected source at $relativePath (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    private val fieldCapture = Regex(
        """(?m)^\s*(?:private\s+|internal\s+)?val\s+\w+\s*:\s*Texture\s*=\s*WhitePixel\.get\(\)""",
    )

    private val getterShape = Regex("""val\s+whitePixel\s*:\s*Texture\s+get\(\)\s*=\s*WhitePixel\.get\(\)""")

    @Test
    fun noSourceCapturesTheSharedPixelIntoAField() {
        val offenders = listOf(
            "render/table/ConnectorCurveDrawer.kt",
            "render/claim/ClaimActionPanel.kt",
            "ui/Canvas.kt",
        ).filter { fieldCapture.containsMatchIn(source(it)) }

        assertEquals(
            emptyList(),
            offenders,
            "These files capture the shared white pixel in a field. Stage G's green-" +
                "felt-on-first-load was exactly this: recovery invalidated the shared " +
                "instance while these held the dead reference. Read it through a " +
                "getter or a provider instead.",
        )
    }

    @Test
    fun theCurveDrawerReadsItFreshOnEveryUse() {
        assertTrue(
            getterShape.containsMatchIn(source("render/table/ConnectorCurveDrawer.kt")),
            "ConnectorCurveDrawer.whitePixel must be a getter, not a field: it is " +
                "constructed once and outlives every context recovery.",
        )
    }

    @Test
    fun theClaimPanelReadsItFreshOnEveryUse() {
        assertTrue(
            getterShape.containsMatchIn(source("render/claim/ClaimActionPanel.kt")),
            "ClaimActionPanel.whitePixel must be a getter, not a field.",
        )
    }

    @Test
    fun canvasTakesAProviderRatherThanAValue() {
        assertTrue(
            Regex("""whitePixel\s*:\s*\(\)\s*->\s*Texture""").containsMatchIn(source("ui/Canvas.kt")),
            "Canvas must receive a provider. It is also the only way to keep ui/ free " +
                "of a render/ import (ArchitectureRatchet.UI_PURE).",
        )
    }
}
