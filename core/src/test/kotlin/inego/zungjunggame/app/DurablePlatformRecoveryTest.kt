package inego.zungjunggame.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two halves of "a platform whose GL context is durable still has to be able
 * to recover".
 *
 * The web ships durable: §9.1 measured six away/return cycles up to 155 s with the
 * context-loss listener live and **zero** loss events, which is what identified
 * the reported ~1.2 s freeze as our own rebuild rather than the browser's. That
 * measurement is only safe to act on because the *other* half is real — a
 * `webglcontextrestored` still repairs itself. So both directions are pinned:
 *
 *  - the **cheap common case** must stay cheap, or Stage W has not shipped and the
 *    user's freeze is still there;
 *  - the **rare real loss** must still rebuild, or the first GPU driver reset
 *    produces a silent black canvas, which is strictly worse than the freeze was
 *    and much harder to diagnose.
 *
 * Neither is observable from `:core:test` alone, so these are about the *decision*;
 * W4's browser run is what proves the wiring delivers the event.
 */
class DurablePlatformRecoveryTest {

    private val t0 = 1_000_000_000L
    private val frame16Ms = 16_000_000L

    private fun coordinator() = GpuRecoveryCoordinator(FrameStallRecovery(1500L))

    // ---- the platform table (W3) ------------------------------------------------------

    @Test
    fun aDurablePlatformDoesNotRebuildOnResume() {
        assertFalse(
            shouldRequestOnResume(contextsAreDurable = true),
            "Stage W1/W3: a resume on the web must not cost a rebuild. If this is red " +
                "the ~1.2 s tab-return freeze is back — §9.1.",
        )
    }

    @Test
    fun aVolatilePlatformStillRebuildsOnResume() {
        // The `contextsAreDurable = false` branch. No launcher installs it any
        // more — Stage C moved Android to `AndroidGpuContexts` (durable) — but it
        // is still the shape a *new* platform gets before anyone has measured
        // it, and `GpuContextPolicy.factory` still defaults to it. Suppressing
        // the rebuild here would be the black-box-text regression for such a
        // platform, and the desktop A/B could not have caught it.
        assertTrue(shouldRequestOnResume(contextsAreDurable = false))
    }

    @Test
    fun theWebIsDurableAndAndroidIsNot() {
        // Deprecated in meaning by Stage C: Android *is* now durable, but it is
        // not one of these two objects — it installs `AndroidGpuContexts`, which
        // is durable **and** carries `onSurfaceCreated`. The two defaults below
        // therefore no longer describe any shipping platform; they are pinned
        // because they are what a headless test and an uninstalled target get,
        // and because the polarity is the mistake §6 Q1 exists to record.
        assertTrue(
            inego.zungjunggame.platform.DurableGpuContexts.contextsAreDurable,
            "DurableGpuContexts is the flag Lwjgl3Launcher installs for desktop",
        )
        assertFalse(
            inego.zungjunggame.platform.VolatileGpuContexts.contextsAreDurable,
            "VolatileGpuContexts is the conservative default, not a shipping " +
                "platform: Android moved to AndroidGpuContexts in Stage C",
        )
    }

    // ---- the recovery that replaces it (W2) -------------------------------------------

    @Test
    fun aRestoreEventStillRebuildsOnADurablePlatform() {
        // The safety net W1 depends on. If the gap path is off *and* this is off,
        // nothing on the web can ever rebuild.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        c.request("contextRecreated")
        val outcome = c.consume(t0 + frame16Ms, gapTriggers = false)
        assertTrue(
            outcome.rebuild,
            "a real contextrestored must repair itself even with the gap path retired",
        )
        assertEquals("contextRecreated", outcome.reason)
    }

    @Test
    fun aRestoreIsOneRebuildNotOnePerFrame() {
        // The subscriber runs on a BrowserFiber daemon fiber, not inside
        // renderFrame(). If the restore could re-request itself, the platform would
        // rebuild every frame forever — the 2026-09-17 failure with a new trigger.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        c.request("contextrestored")
        assertTrue(c.consume(t0 + frame16Ms, gapTriggers = false).rebuild)

        val after = c.consume(t0 + frame16Ms * 2, gapTriggers = false)
        assertFalse(after.rebuild, "the request is spent by one rebuild")
        assertFalse(c.isPending)
    }

    @Test
    fun aRestoreIsNotLostToTheConsecutiveCap() {
        // A durable platform has no gap path to fall back on, so a refused request
        // is a permanently unrepaired context. The plan found this by test: a
        // pending request makes every frame a candidate, so the run never clears.
        val c = coordinator()
        c.consume(t0, gapTriggers = true)
        var t = t0
        repeat(FrameStallRecovery.DEFAULT_MAX_CONSECUTIVE) {
            t += 2_000_000_000L
            assertTrue(c.consume(t, gapTriggers = true).rebuild)
        }
        t += 2_000_000_000L
        assertFalse(c.consume(t, gapTriggers = true).rebuild, "the fourth gap trips the cap")

        c.request("contextrestored")
        assertFalse(c.consume(t + frame16Ms, gapTriggers = false).rebuild, "capped")
        assertTrue(c.isPending, "a real loss must never be silently dropped")
        assertTrue(
            c.consume(t + frame16Ms * 2, gapTriggers = false).rebuild,
            "and it must land on the very next frame",
        )
    }

    @Test
    fun anIdleGapOnTheWebIsJustAPaint() {
        // The second finding in §9.1, and the bigger one: the rebuild also fired
        // while the tab was in the *foreground*, because `setContinuousRendering(false)`
        // makes an untouched menu produce 1.7–5.9 s gaps by design and a 1500 ms
        // threshold cannot tell that from backgrounding. Six such gaps, zero rebuilds.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        var t = t0
        for (gapMs in listOf(1746L, 1698L, 2538L, 5934L, 5900L, 1700L)) {
            t += gapMs * 1_000_000L
            val outcome = c.consume(t, gapTriggers = false)
            assertFalse(outcome.rebuild, "a ${gapMs}ms idle gap is not a context loss")
            assertFalse(outcome.stalled)
        }
    }

    @Test
    fun aLongAbsenceIsAlsoJustAPaint() {
        // The other half of W4's acceptance: a 41 s tab absence with no restore
        // event must log zero rebuilds. §9.1 measured 155 s with the listener live.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        val outcome = c.consume(t0 + 155_000_000_000L, gapTriggers = false)
        assertFalse(outcome.rebuild)
        assertFalse(outcome.stalled)
        assertEquals(155_000L, outcome.gapMs, "still measured, for the trace")
        assertFalse(c.isPending, "and nothing was queued behind it")
    }

    @Test
    fun aRequestWaitsForNoFrameAtAll() {
        // W4's own run produced this. A forced lose/restore on a settled web app
        // set the pending flag, and then nothing happened for **40 s** — because
        // `setContinuousRendering(false)` means a settled app schedules no frames,
        // and on a durable platform the gap heuristic that used to save us is now
        // off. The rebuild was correct and completely unserved until a synthetic
        // mouse move happened to schedule a frame.
        //
        // The coordinator cannot see this: `consume` is only ever called from
        // `renderFrame`, so "no frame" and "frame arrived" are indistinguishable to
        // it. The fix is at the call site — `requestRendering()` when a real event
        // arrives — so what is pinned here is that the flag really is pending and
        // really is one-shot, i.e. that the fix has something to spend on the very
        // first frame and nothing more.
        val c = coordinator()
        c.consume(t0, gapTriggers = false)
        c.request("contextRecreated")
        assertTrue(c.isPending, "the request must be waiting for a frame that had not come yet")

        // When a frame finally does arrive — the one requestRendering() forced —
        // it is spent immediately and exactly once.
        val first = c.consume(t0 + 40_000_000_000L, gapTriggers = false)
        assertTrue(first.rebuild, "however late the frame, the loss is repaired on it")
        assertFalse(c.consume(t0 + 40_016_000_000L, gapTriggers = false).rebuild)
        assertFalse(c.isPending, "and the request does not outlive its rebuild")
    }
}
