package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coordinator is the single entry point every glyph/texture rebuild is
 * requested through.
 *
 * What it has to preserve, and why each of these is a test rather than a comment:
 *
 *  - **Coalescing.** ~430 ms of duplicated rebuild was measured on
 *    one desktop alt-tab, because a long frame gap and `resume()` each asked on
 *    their own. Two asks before one frame must cost one rebuild.
 *  - **Per-platform triggers.** A durable platform must stop rebuilding on an
 *    idle gap (that is the reported web freeze), while a volatile one must keep
 *    doing so, because there the gap is its only signal.
 *  - **No lost request.** A rebuild refused by the consecutive cap must stay
 *    pending, or a genuine context loss would be dropped exactly when the cap is
 *    protecting a slow machine from something else.
 */
class GpuRecoveryCoordinatorTest {

    private val threshold = 1500L

    /** Non-zero on purpose: 0 is the "no previous frame yet" sentinel. */
    private val t0 = 1_000_000_000L

    private val frame16Ms = 16_000_000L

    private fun coordinator() =
        GpuRecoveryCoordinator(FrameStallRecovery(threshold))

    @Test
    fun aFrameWithNothingPendingDoesNothing() {
        val c = coordinator()
        val outcome = c.consume(t0, gapTriggers = true)
        assertFalse(outcome.rebuild)
        assertFalse(outcome.stalled)
        assertEquals(0L, outcome.gapMs)
        assertFalse(c.isPending)
    }

    @Test
    fun aRequestBecomesExactlyOneRebuild() {
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        c.request("resume")

        val outcome = c.consume(t0 + frame16Ms, gapTriggers = true)
        assertTrue(outcome.rebuild)
        assertEquals("resume", outcome.reason)
        assertFalse(outcome.gapTriggered, "a request is not a gap, and the trace must say so")

        // And it is spent: the next frame is a clean paint.
        val next = c.consume(t0 + frame16Ms * 2, gapTriggers = true)
        assertFalse(next.rebuild, "a request is spent by one rebuild, not repeated each frame")
    }

    @Test
    fun severalRequestsInOneFrameCostOneRebuild() {
        // The E3+E4 case: a long gap and a resume arrive for the same backgrounding.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        c.request("resume")
        c.request("contextRecreated")

        val outcome = c.consume(t0 + 5_000_000_000L, gapTriggers = true)
        assertTrue(outcome.rebuild)
        assertEquals(1, outcome.consecutive, "one event, one rebuild")
        assertEquals(
            "resume",
            outcome.reason,
            "the first asker names the rebuild; the others collapsed into it",
        )
        assertFalse(c.isPending)
    }

    @Test
    fun aRequestSurvivesAFrameThatWasCapped() {
        // Drive the coordinator into the cap, then hold a request across it. The
        // request must not be dropped: it is the only recovery a durable platform
        // has, since it has no gap path left.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        var t = t0
        repeat(FrameStallRecovery.DEFAULT_MAX_CONSECUTIVE) {
            t += 2_000_000_000L
            assertTrue(
                c.consume(t, gapTriggers = true).rebuild,
                "the gap run is inside the cap for its first three frames",
            )
        }
        // One more gap trips the cap, so the following frame starts refused.
        t += 2_000_000_000L
        assertFalse(c.consume(t, gapTriggers = true).rebuild, "the fourth gap trips the cap")
        c.request("contextRecreated")

        val refused = c.consume(t + frame16Ms, gapTriggers = false)
        assertFalse(refused.rebuild, "the cap from the gap run still applies")
        assertTrue(c.isPending, "a refused rebuild must stay pending, not be lost")

        // The very next frame honours it: clearing per frame cannot deadlock,
        // because every requester is a one-shot event.
        val repaired = c.consume(t + frame16Ms * 2, gapTriggers = false)
        assertTrue(repaired.rebuild, "a durable platform has no other path; the request must land")
        assertEquals("contextRecreated", repaired.reason)
        assertFalse(c.isPending)
    }

    @Test
    fun aDurablePlatformDoesNotRebuildOnAnIdleGap() {
        // An untouched menu on a durable platform produces 1.7–5.9 s
        // gaps by design. Every one of them must be a plain paint.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        var t = t0
        repeat(6) {
            t += 5_900_000_000L
            val outcome = c.consume(t, gapTriggers = false)
            assertFalse(outcome.rebuild, "a 5.9 s idle gap is not a context loss")
            assertFalse(outcome.stalled)
            assertEquals(5_900L, outcome.gapMs, "the gap is still measured for diagnostics")
        }
    }

    @Test
    fun aVolatilePlatformStillRebuildsOnAGap() {
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        val outcome = c.consume(t0 + 5_257_000_000L, gapTriggers = true)
        assertTrue(outcome.rebuild, "Android has no other signal yet; this must keep working")
        assertTrue(outcome.gapTriggered)
        assertEquals("gap 5257ms", outcome.reason)
        assertEquals(5257L, outcome.gapMs)
    }

    @Test
    fun aDurablePlatformStillRebuildsOnARealEvent() {
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        c.request("contextRecreated")
        val outcome = c.consume(t0 + frame16Ms, gapTriggers = false)
        assertTrue(
            outcome.rebuild,
            "a driver reset is real on a durable platform; the gap path being off must not also mute the event",
        )
    }

    @Test
    fun aNewRuntimeRequestIsAnOrdinaryRequest() {
        // The cold-start rebuild was measured redundant *on desktop* and
        // kept anyway: the Android activity-recreation path builds a
        // second runtime inside one process and genuinely inherits faces, and that
        // case has no `onSurfaceCreated` signal until Stage C. So the call survives
        // the probe's removal as a plain request through the one coalescing entry
        // point — not as a special tag nothing reads.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        c.request("new runtime")
        val outcome = c.consume(t0 + frame16Ms, gapTriggers = true)
        assertTrue(outcome.rebuild)
        assertEquals("new runtime", outcome.reason)
    }

    @Test
    fun aRebuildIsChargedToItself() {
        // The 2026-09-17 failure: a rebuild slower than the threshold re-armed the
        // trigger on its own duration, ~2.4 s per frame forever.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        c.request("resume")
        assertTrue(c.consume(t0 + 2_441_000_000L, gapTriggers = true).rebuild)

        // The rebuild cost 2151 ms. Charging it to the clock is the caller's job.
        val rebuildEnds = t0 + 2_441_000_000L + 2_151_000_000L
        c.chargedRebuild(rebuildEnds)

        val next = c.consume(rebuildEnds + frame16Ms, gapTriggers = true)
        assertFalse(next.stalled, "the rebuild's own duration must not read as a backgrounding")
        assertEquals(16L, next.gapMs)
    }

    @Test
    fun aRequestArrivingAfterARebuildIsStillASeparateRebuild() {
        // **The E3+E4 shape, measured 2026-09-29 and NOT fixed here.** A desktop
        // minimize/restore rebuilds on the first frame back (the gap), then the
        // resume request lands on the *next* frame — ~90 ms later — when the
        // pending flag has already been spent. So two rebuilds, ~430 ms, exactly
        // as before Stage A.
        //
        // Asserted as documentation on purpose: a test that expected one rebuild
        // here is what I wrote first, and the desktop trace falsified it. The fix
        // is not in this class — it is `AppRuntime.requestRecovery`, which stops a
        // durable platform asking at all (Stages B and W).
        val c = coordinator()
        c.consume(t0, gapTriggers = true)

        // Frame 88: the gap fires, the rebuild runs.
        val first = c.consume(t0 + 17_423_000_000L, gapTriggers = true)
        assertTrue(first.rebuild)
        assertTrue(first.gapTriggered)
        c.chargedRebuild(t0 + 17_423_000_000L)

        // The resume arrives one frame later, after the flag was spent.
        c.request("resume")
        val second = c.consume(t0 + 17_423_000_000L + frame16Ms, gapTriggers = true)
        assertTrue(
            second.rebuild,
            "documented limitation, not an accident: one-frame coalescing cannot span two frames",
        )
        assertEquals("resume", second.reason)
    }

    @Test
    fun theGapCapStillBoundsASlowMachine() {
        // The original E3 defence, unchanged by A: a machine slower than the
        // threshold must fall back to a low frame rate, not rebuild every frame.
        // Only the *gap* path is capped like this — see the deadlock note in
        // [GpuRecoveryCoordinator.consume].
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        var t = t0
        val outcomes = (1..6).map {
            t += 2_441_000_000L
            c.consume(t, gapTriggers = true)
        }
        assertEquals(
            FrameStallRecovery.DEFAULT_MAX_CONSECUTIVE,
            outcomes.count { it.rebuild },
            "a rebuild per frame must be bounded, not unbounded",
        )
        assertTrue(outcomes.take(3).all { it.rebuild })
        assertTrue(outcomes.last().stalled, "the stall is still reported for diagnostics")
        assertFalse(outcomes.last().rebuild, "past the cap the faces we have must be kept")
        assertEquals(1, outcomes.count { it.capTripped }, "the caller is told once, not per frame")
    }
}
