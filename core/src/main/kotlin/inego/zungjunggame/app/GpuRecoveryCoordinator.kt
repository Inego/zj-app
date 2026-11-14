package inego.zungjunggame.app

/**
 * The single entry point for "the GPU resources may be gone, rebuild them".
 *
 * Three places can ask for that — a long frame gap ([FrameStallRecovery]), a
 * `resume()` ([AppRuntime.resume]), and a freshly-created runtime
 * ([AppRuntime.requestFontRebuild]) — and this class gives them one pending flag
 * and one rebuild per frame.
 *
 * ### What coalescing does and does not buy — measured 2026-09-29
 *
 * The intent was to kill the E3+E4 duplicate: a desktop minimize/restore paying
 * **two** rebuilds (~430 ms) for one absence. **It does not, and the trace says
 * so plainly.** Three consecutive minimize/restore cycles on desktop each still
 * produced two:
 *
 * ```
 * 13:51:21.577 frame 88   gap=17423ms  ->  rebuild (gap)      197 ms
 * 13:51:21.795 present
 * 13:51:21.886 listener.resume          <- arrives ~90 ms LATER, next frame
 * 13:51:22.065           ->  rebuild (resume)                168 ms
 * ```
 *
 * E3 and E4 are not concurrent. The gap rebuild lands on the first frame back;
 * the resume request arrives on the *following* frame, when the pending flag has
 * already been spent. Coalescing requests **within one frame** cannot see that
 * as one event, and a window-based merge would only hide it behind a guessed
 * duration — the kind of guess the design explicitly rejects.
 *
 * The real fix is upstream and already designed: a resume carries no information
 * on a platform whose context is durable, so it should not ask at all. That is
 * [AppRuntime.requestRecovery], and it is what Stage B (desktop) and W (web)
 * activate. Until then the duplicate is **expected**, and A5's "timings
 * unchanged" holds because nothing got slower — the count is simply the same as
 * before, not as low as it could be.
 *
 * Coalescing still earns its place: N requests landing before one frame cost one
 * rebuild, and the single pending flag is the only place a rebuild is requested
 * from, so a platform that stops asking (B, W) needs no further change here.
 *
 * GL-free and unit-testable by construction — it holds no `Gdx` reference — so
 * the coalescing is testable without a live context. Render thread only, like the
 * [FrameStallRecovery] it owns; not thread-safe.
 */
internal class GpuRecoveryCoordinator(
    private val stall: FrameStallRecovery,
) {
    /** A rebuild that has been asked for but not yet spent. */
    private class Pending(val reason: String)

    private var pending: Pending? = null

    /**
     * True while a request is outstanding. Diagnostics only — the render loop
     * spends the flag through [consume], which is the only thing that clears it.
     */
    val isPending: Boolean get() = pending != null

    /**
     * Ask for a rebuild on the next frame. A second request before the next
     * [consume] does not queue a second rebuild; the two collapse into one.
     */
    fun request(reason: String) {
        // First writer wins, so the reason that survives is the one that arrived
        // first — the same backgrounding is usually both a gap and a resume, and
        // the earlier asker is the more specific of the two.
        if (pending == null) pending = Pending(reason)
    }

    /**
     * Decide this frame, spending the pending flag if there is one and if
     * [FrameStallRecovery]'s consecutive cap allows it. Always returns an
     * [Outcome] — including when nothing is to be done — because the frame's
     * diagnostics need the measured gap and the candidate verdict either way.
     *
     * [nowNs] is the frame clock. [gapTriggers] is `false` on a durable platform,
     * which is the only difference between the two platforms' behaviour and is
     * passed in rather than read here so this class stays policy-free.
     */
    fun consume(nowNs: Long, gapTriggers: Boolean): Outcome {
        val asked = pending
        val decision = stall.onFrame(
            nowNs = nowNs,
            recoveryRequested = asked != null,
            gapTriggers = gapTriggers,
        )
        if (asked == null) {
            // No event: the frame gap is the only thing that can ask, which is
            // exactly the volatile-platform path and exactly what A must not
            // change. A clean frame ends the run by itself inside [onFrame]; a
            // cap-tripped one is reported and left capped, so a machine slower
            // than the threshold keeps the faces it has instead of thrashing.
            return Outcome(
                rebuild = decision.rebuild,
                stalled = decision.stalled,
                reason = "gap ${decision.gapMs}ms",
                gapTriggered = decision.rebuild,
                gapMs = decision.gapMs,
                consecutive = stall.consecutive,
                capTripped = decision.capTripped,
            )
        }
        if (!decision.rebuild) {
            // Capped. The request stays pending, but the run is ended so the very
            // next frame can honour it.
            //
            // Holding it without this would deadlock: a pending request makes every
            // frame a candidate, so the run could never clear itself and the cap
            // would refuse the rebuild forever — the loss that needed repairing
            // would be the thing keeping the cap closed. A durable platform has no
            // gap path to fall back on, so that request cannot simply be dropped.
            //
            // Clearing per frame is safe here because every requester is a
            // one-shot event — a `resume`, a new runtime, one `contextrestored` —
            // none of them fires per frame. The source that *can* fire per frame is
            // the frame gap, and that has no pending flag, so it keeps the hard cap
            // this recovery path was built for.
            stall.clearRun()
            return Outcome(
                rebuild = false,
                stalled = true,
                reason = asked.reason,
                gapTriggered = false,
                gapMs = decision.gapMs,
                consecutive = stall.consecutive,
                capTripped = decision.capTripped,
            )
        }
        pending = null
        return Outcome(
            rebuild = true,
            stalled = true,
            reason = asked.reason,
            gapTriggered = false,
            gapMs = decision.gapMs,
            consecutive = stall.consecutive,
            capTripped = decision.capTripped,
        )
    }

    /**
     * Tell the frame clock a rebuild happened on this frame, so its own duration
     * is charged to itself rather than reading as the next backgrounding.
     *
     * Called for every frame that spent a rebuild. Skipping it would let the
     * rebuild's own duration rearm the trigger, which is the 2026-09-17 failure.
     */
    fun chargedRebuild(nowNs: Long) {
        stall.onRebuilt(nowNs)
    }

    /** What one frame's decision came to. */
    data class Outcome(
        /** Rebuild the glyph atlases and re-upload textures on this frame. */
        val rebuild: Boolean,
        /** The frame carried a candidate — a request, or a qualifying gap. */
        val stalled: Boolean,
        /** Log/trace text for this rebuild. */
        val reason: String,
        /** True when no request was pending and the frame gap asked for it instead. */
        val gapTriggered: Boolean,
        /** The measured frame gap, in ms. Diagnostic detail under both triggers. */
        val gapMs: Long,
        /** Rebuild candidates in the current run, as counted by [FrameStallRecovery]. */
        val consecutive: Int,
        /** The run hit [FrameStallRecovery]'s cap; the caller reports it once. */
        val capTripped: Boolean,
    )
}
