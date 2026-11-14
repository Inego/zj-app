package inego.zungjunggame.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage B2: on a durable platform, a `resume` costs nothing — and the things a
 * resume is *for* still happen.
 *
 * ### Why this is a separate claim from B1
 *
 * B1 was a launcher flip. B2 is the behaviour that flip arms, and the plan is
 * explicit that the two are not two pieces of work:
 *
 * > The duplicate is retired by the **durable flip** (B2/W3), not by coalescing.
 *
 * So B2 has no new code. That makes it exactly the kind of stage that rots
 * quietly: there is nothing to review, so the next person to touch `resume()`
 * cannot tell that the gate there is load-bearing, and "resume should also
 * recover the batch" reads as a harmless addition rather than the ~175 ms of
 * first-frame cost this exists to prevent. Measured 2026-09-29 on desktop: a
 * 161–175 ms `requested resume` rebuild and a 186–203 ms `gapTriggered` rebuild
 * on **every** minimize, and a 329 ms return frame.
 *
 * ### What the log showed once the flip was live
 *
 * Three minimize/restore cycles, zero `gapTriggered`, zero `requested resume`, no
 * `stalled=true` at all, and the cold start as the session's only rebuild.
 *
 * ### What these pin
 *
 * The decision, at the level it is decided. `AppRuntime` needs a GL context and
 * a running game to test, so the two things worth protecting — that a durable
 * resume asks for nothing, and that a volatile one still asks — are asserted
 * through [shouldRequestOnResume] and through the coordinator's own response to
 * `gapTriggers`, which is the flag B1's flip reaches.
 */
class DurablePlatformResumeTest {

    private val threshold = 1500L
    private val t0 = 1_000_000_000L

    private fun coordinator() = GpuRecoveryCoordinator(FrameStallRecovery(threshold))

    // ---- the gate itself (B2) --------------------------------------------------------

    @Test
    fun aResumeOnADurablePlatformAsksForNothing() {
        assertFalse(
            shouldRequestOnResume(contextsAreDurable = true),
            "B2: `resume()` must not queue a rebuild on desktop or the web. If this " +
                "is red the 161-175 ms resume rebuild is back on the first frame after " +
                "every minimize, measured 2026-09-29.",
        )
    }

    @Test
    fun aResumeOnAVolatilePlatformStillAsks() {
        // The asymmetry is deliberate and load-bearing (see the KDoc on
        // shouldRequestOnResume). No launcher installs the volatile policy any
        // more — Stage C moved Android to `AndroidGpuContexts` — but this is
        // still the answer for a platform nobody has measured, which is what
        // `GpuContextPolicy.factory` hands out. Suppressing it there would be
        // the black-box-text regression.
        assertTrue(
            shouldRequestOnResume(contextsAreDurable = false),
            "a volatile platform must still ask on resume. Stage C replaced " +
                "Android's *guess* with `onSurfaceCreated`; it did not change " +
                "what an unmeasured platform is allowed to do.",
        )
    }

    // ---- what the flip actually reaches (B1's flag, B2's consequence) ---------------

    @Test
    fun aDurablePlatformIgnoresAGapButStillHonoursARequest() {
        // `renderFrame` passes `gapTriggers = !contextsAreDurable`. With it false,
        // a 5-second hole in the frame clock — the signature of every minimize —
        // must produce no rebuild. This is the `gapTriggered` line that used to
        // cost 186-203 ms while the window was still iconified at 0x0.
        val durable = coordinator()
        durable.consume(t0, gapTriggers = false)
        val outcome = durable.consume(t0 + 5_000_000_000L, gapTriggers = false)
        assertFalse(
            outcome.rebuild,
            "a 5 s gap on a durable platform must not rebuild: on-demand rendering " +
                "makes an untouched menu produce 1.7-5.9 s gaps by design, so the " +
                "threshold cannot tell idleness from a lost context",
        )
    }

    @Test
    fun aDurablePlatformStillRecoversFromARealContextEvent() {
        // The safety half. B2 suppresses a *guess*; it must not suppress an
        // event. `onContextRecreated` posts "contextRecreated", which has to be
        // serviced even though the gap path is off — otherwise the first driver
        // reset produces a silent blank window, which is strictly worse than the
        // freeze it replaced and far harder to diagnose.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        c.request("contextRecreated")
        val outcome = c.consume(t0 + 16_000_000L, gapTriggers = false)
        assertTrue(
            outcome.rebuild,
            "a real context recreation must rebuild on a durable platform. Stage W " +
                "depends on this: durable is only safe while a real loss still " +
                "repairs itself.",
        )
    }

    @Test
    fun aVolatilePlatformKeepsTheGapPath() {
        // The other half of "the trigger switches per platform, not globally".
        // A single global change would have been an Android regression while
        // Android was volatile, and the desktop A/B could not have caught it.
        //
        // No shipping platform is volatile any more (Stage C moved the last one),
        // so this is now the shape a *new* target inherits before anyone has
        // measured it. It still has to work, because `GpuContextPolicy.factory`
        // hands out exactly this.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        val outcome = c.consume(t0 + 5_000_000_000L, gapTriggers = true)
        assertTrue(
            outcome.rebuild,
            "a volatile platform's gap path is its only recovery trigger, since it " +
                "has no event to wait for. Suppressing it would leave an " +
                "unmeasured platform drawing nothing after a backgrounding.",
        )
    }

    // ---- the wiring, which the two tests above cannot reach --------------------------

    /**
     * The tests above pass a literal `gapTriggers`, so they would still be green
     * with `renderFrame` hard-coding either value — I checked, by flipping the
     * call site to `true` and watching all five pass. That is the honest limit
     * of testing the coordinator: the decision is made by the **caller**, and the
     * caller needs a live GL context and a running game.
     *
     * So the call site is pinned by reading it. This is the same concession
     * [DesktopGpuContextPolicyTest] makes for the launcher, and it is the reason
     * that test exists at all.
     */
    @Test
    fun renderFrameDerivesTheGapPathFromTheSameFlagAsTheResumeGate() {
        val source = File("../core/src/main/kotlin/inego/zungjunggame/app/AppRuntime.kt")
        assertTrue(source.exists(), "expected AppRuntime.kt (cwd=${File(".").absolutePath})")
        val text = source.readText()

        val gapCall = Regex("""consume\(\s*nowNs,\s*gapTriggers\s*=\s*([^)]*)\)""").find(text)
        assertTrue(
            gapCall != null,
            "AppRuntime must pass gapTriggers to the coordinator explicitly; a call " +
                "that omits it would silently pick the volatile default",
        )
        assertEquals(
            "!gpuContext.contextsAreDurable",
            gapCall.groupValues[1].trim(),
            "B2: renderFrame must derive the gap path from the declared durability. A " +
                "hard-coded true pays a rebuild on every backgrounding on all three " +
                "platforms; a hard-coded false leaves a volatile one — or any target " +
                "that has not wired a real loss event — with no recovery at all.",
        )
    }
}
