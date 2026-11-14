package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: a glyph-atlas rebuild slower than the background-gap threshold used
 * to re-trigger itself on every frame.
 *
 * The runtime stamps its frame clock at the *start* of a frame, so a rebuild's
 * own duration is what the next frame measures as its gap. On a software-rendered
 * browser (2026-09-17) the rebuild took **2151 ms** against a **1500 ms**
 * threshold, so every frame stalled, rebuilt and stalled again — ~2.4 s per frame
 * forever. The same stall also delayed the presence heartbeat's completion
 * callback, which made the in-game latency readout show ~2200 ms while ICMP to
 * that server was 27 ms.
 *
 * These are the numbers from that machine, replayed.
 */
class FrameStallRecoveryTest {

    private val threshold = 1500L

    /** Non-zero on purpose: 0 is the "no previous frame yet" sentinel. */
    private val t0 = 1_000_000_000L

    /** One frame at 60 Hz, in nanos. */
    private val frame16Ms = 16_000_000L

    @Test
    fun theFirstFrameNeverCountsAsABackgrounding() {
        val gate = FrameStallRecovery(threshold)
        val first = gate.onFrame(t0)
        assertFalse(first.stalled, "there is no previous frame to have been away from")
        assertFalse(first.rebuild)
        assertEquals(0L, first.gapMs)
    }

    @Test
    fun aRequestIsHonouredEvenOnTheFirstFrame() {
        // There is no previous frame, so no gap can qualify — but a real context
        // recreation must still repair itself if it arrives before first paint.
        val gate = FrameStallRecovery(threshold)
        val first = gate.onFrame(t0, recoveryRequested = true)
        assertTrue(first.rebuild, "an authoritative loss cannot wait for a second frame")
        assertEquals(0L, first.gapMs)
    }

    @Test
    fun aDurablePlatformIgnoresAGapEntirely() {
        // `setContinuousRendering(false)` makes an untouched idle
        // menu produce 1.7–5.9 s gaps by design, so on a platform whose context
        // survives backgrounding the gap carries no information at all.
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        var t = t0
        repeat(6) {
            t += 5_900_000_000L
            val decision = gate.onFrame(t, gapTriggers = false)
            assertFalse(decision.stalled, "a durable platform must not read idle as a loss")
            assertFalse(decision.rebuild)
        }
        assertEquals(0, gate.consecutive)
        assertFalse(gate.capped, "ignoring gaps must not accumulate a run")
    }

    @Test
    fun aVolatilePlatformStillRecoversOnAGap() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        val stall = gate.onFrame(t0 + 5_257_000_000L, gapTriggers = true)
        assertTrue(stall.stalled)
        assertTrue(stall.rebuild, "on Android the gap is the only signal there is")
        assertEquals(5257L, stall.gapMs)
    }

    @Test
    fun aRequestRebuildsWithoutQualifyingAGap() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        // 3 ms since the last frame: far below any threshold. The request is the
        // whole reason, which is what lets a platform stop trusting gaps.
        val decision = gate.onFrame(t0 + frame16Ms, recoveryRequested = true)
        assertTrue(decision.rebuild)
        assertEquals(16L, decision.gapMs)
    }

    @Test
    fun aSuppressedCandidateKeepsTheRunAlive() {
        // A source that asks on every frame must not get a fresh budget each time
        // *within* the run. clearRun() is the explicit escape, and the coordinator
        // only calls it for a new event, not per frame.
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        var t = t0
        val rebuilds = (1..6).map {
            t += frame16Ms
            gate.onFrame(t, recoveryRequested = true).rebuild
        }
        assertTrue(rebuilds.take(3).all { it }, "the first three are still honoured")
        assertTrue(rebuilds.drop(3).none { it }, "past the cap the faces we have must be kept")
        assertEquals(
            FrameStallRecovery.DEFAULT_MAX_CONSECUTIVE,
            gate.consecutive,
            "a suppressed candidate is still part of the run; resetting here would make the cap decorative",
        )
    }

    @Test
    fun clearRunGivesTheNextEventAFullBudget() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        var t = t0
        repeat(6) {
            t += frame16Ms
            gate.onFrame(t, recoveryRequested = true)
        }
        assertTrue(gate.capped)

        gate.clearRun()
        assertEquals(0, gate.consecutive)
        assertFalse(gate.capped, "a new event is not blocked by the previous one's cap")
        assertTrue(
            gate.onFrame(t + frame16Ms, recoveryRequested = true).rebuild,
            "without this the cap would deadlock: a pending request makes every frame a candidate",
        )
    }

    @Test
    fun anOrdinaryBackgroundingRebuildsOnce() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        // From the Android notes: a 5257 ms hole, then a ~300 ms rebuild on
        // hardware-accelerated devices. That case must keep working.
        val stall = gate.onFrame(t0 + 5_257_000_000L)
        assertTrue(stall.stalled)
        assertTrue(stall.rebuild, "a 5.2 s hole is a backgrounding; the atlases must be rebuilt")
        assertEquals(5257L, stall.gapMs)
    }

    @Test
    fun aRebuildSlowerThanTheThresholdDoesNotRearmTheTrigger() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        val away = t0 + 2_441_000_000L
        assertTrue(gate.onFrame(away).rebuild)

        // The rebuild cost 2151 ms. Charged to the next gap it reads as "we were
        // away for 2151 ms" on every frame; onRebuilt charges it to itself.
        val rebuildEnds = away + 2_151_000_000L
        gate.onRebuilt(rebuildEnds)

        val next = gate.onFrame(rebuildEnds + frame16Ms)
        assertFalse(next.stalled, "the rebuild's own duration must not read as a backgrounding")
        assertFalse(next.rebuild)
        assertEquals(16L, next.gapMs)
        assertEquals(0, gate.consecutive, "a normal frame clears the run")
    }

    @Test
    fun withoutTheRestampTheSameNumbersLoopAndAreCapped() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        // The old behaviour, kept as the reason the cap exists: no onRebuilt, so
        // every frame measures the previous rebuild in full.
        var t = t0
        val decisions = (1..6).map {
            t += 2_441_000_000L
            gate.onFrame(t)
        }
        assertEquals(
            FrameStallRecovery.DEFAULT_MAX_CONSECUTIVE,
            decisions.count { it.rebuild },
            "a rebuild per frame must be bounded, not unbounded",
        )
        assertTrue(decisions.take(3).all { it.rebuild }, "the first three are still honoured")
        assertTrue(gate.capped)
        assertFalse(decisions.last().rebuild, "past the cap the faces we have must be kept")
        assertTrue(decisions.last().stalled, "the stall is still reported for diagnostics")
        assertEquals(1, decisions.count { it.capTripped }, "the caller is told once, not per frame")
    }

    @Test
    fun theCapClearsSoALaterBackgroundingStillRecovers() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        var t = t0
        repeat(6) {
            t += 2_441_000_000L
            gate.onFrame(t)
        }
        assertTrue(gate.capped)

        val quiet = gate.onFrame(t + frame16Ms)
        assertFalse(quiet.rebuild)
        assertFalse(gate.capped, "a machine that caught up is no longer capped")
        assertEquals(0, gate.consecutive)

        val later = gate.onFrame(t + frame16Ms + 9_000_000_000L)
        assertTrue(later.rebuild, "a real 9 s backgrounding must still rebuild the atlases")
    }

    @Test
    fun theThresholdIsInclusiveAndSubThresholdGapsAreIgnored() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        var t = t0
        t += 1_499_000_000L
        assertFalse(gate.onFrame(t).stalled, "1499 ms is not a backgrounding")
        t += 1_499_000_000L
        assertFalse(gate.onFrame(t).stalled)
        t += 1_500_000_000L
        assertTrue(gate.onFrame(t).stalled, "the threshold itself counts")
    }

    @Test
    fun consecutiveCountsCandidatesOnly() {
        val gate = FrameStallRecovery(threshold)
        gate.onFrame(t0)
        gate.onFrame(t0 + 2_000_000_000L)
        gate.onFrame(t0 + 4_000_000_000L)
        assertEquals(2, gate.consecutive)
        // A frame that is not a candidate resets the run, which is what keeps a
        // one-off hiccup from ever reaching the cap.
        gate.onFrame(t0 + 4_000_000_000L + frame16Ms)
        assertEquals(0, gate.consecutive)
    }
}
