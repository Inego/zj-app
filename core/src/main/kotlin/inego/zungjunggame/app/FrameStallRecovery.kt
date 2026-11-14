package inego.zungjunggame.app

/**
 * Decides when a long frame gap means "the app was not being rendered, so the
 * driver may have discarded the EGL context" and when it means "this machine is
 * simply too slow to rebuild the glyph atlases".
 *
 * Both look identical from the gap alone, and the second case is self-sustaining
 * if it is treated as the first. The runtime stamps its frame clock at the
 * *start* of a frame, so a rebuild's own duration is what the next frame
 * measures as its gap. Where a rebuild costs more than [thresholdMs] the trigger
 * re-arms itself and the app rebuilds on every single frame.
 *
 * Measured on a software-rendered browser (2026-09-17), the failure this class
 * exists to prevent:
 *
 * ```
 * gap 2441ms -> rebuild (2151ms) -> gap 2441ms -> rebuild (2151ms) -> ...
 * ```
 *
 * i.e. ~2.4 s per frame, forever, with the same stall also delaying the presence
 * heartbeat's completion callback so the in-game latency readout showed
 * ~2200 ms against 27 ms of ICMP to the same server.
 *
 * This class owns the frame clock so both defences are testable without a live
 * Gdx context:
 *
 *  - [onRebuilt] restarts the clock after a rebuild, so the rebuild is charged
 *    to itself and cannot be mistaken for the next backgrounding;
 *  - [DEFAULT_MAX_CONSECUTIVE] caps a run of rebuilds, so a machine genuinely
 *    slower than the threshold falls back to whatever frame rate it can manage
 *    instead of thrashing.
 *
 * **What may ask for a rebuild.** A rebuild is a *candidate* when
 * either something requested one ([recoveryRequested] — an event that knows the
 * context is gone) or the frame gap crossed the threshold *and* [gapTriggers] is
 * set. [gapTriggers] is the platform's [inego.zungjunggame.platform.GpuContextPolicy.contextsAreDurable]
 * turned into a switch by the caller: a durable platform passes `false`, because
 * on-demand rendering makes an idle, untouched menu produce gaps of 1.7–5.9 s
 * *by design* and a threshold cannot tell that from a backgrounding. A volatile
 * platform passes `true`, since there the gap is the only signal it has.
 *
 * The run resets only on a frame that was **not a candidate**. A candidate
 * suppressed by the cap is still part of the same run: resetting there would hand
 * a runaway source a fresh budget every frame and make the cap decorative.
 *
 * Render thread only; not thread-safe.
 */
internal class FrameStallRecovery(
    private val thresholdMs: Long,
    private val maxConsecutive: Int = DEFAULT_MAX_CONSECUTIVE,
) {
    /** What one frame's gap decided. */
    data class Decision(
        /** The frame carried a rebuild candidate — a request, or a qualifying gap. */
        val stalled: Boolean,
        /** Rebuild the glyph atlases and re-upload textures on this frame. */
        val rebuild: Boolean,
        /** The cap was reached on this frame; the caller reports it once. */
        val capTripped: Boolean,
        /** The gap that was measured, in ms. 0 on the first frame. */
        val gapMs: Long,
    )

    private var lastFrameNs = 0L

    /** Rebuild candidates in the current run, whether or not the cap let them through. */
    var consecutive = 0
        private set

    /** True once the cap is reached; cleared by the next frame that is not a candidate. */
    var capped = false
        private set

    /**
     * The first call of a runtime establishes the clock and never reports a
     * stall: there is no previous frame to have been away from.
     *
     * A *request* is still honoured on that first frame, though — [gapTriggers] is
     * irrelevant to it, and a real context recreation must repair itself even if it
     * arrives before anything has been painted.
     *
     * @param recoveryRequested something asked for a rebuild this frame.
     * @param gapTriggers whether a frame gap may ask for one on this platform.
     */
    fun onFrame(
        nowNs: Long,
        recoveryRequested: Boolean = false,
        gapTriggers: Boolean = true,
    ): Decision {
        val gapMs = if (lastFrameNs == 0L) 0L else (nowNs - lastFrameNs) / 1_000_000L
        val hadPreviousFrame = lastFrameNs != 0L
        val stalled =
            (hadPreviousFrame && gapMs >= thresholdMs && gapTriggers) || recoveryRequested
        lastFrameNs = nowNs
        if (!stalled) {
            consecutive = 0
            capped = false
            return Decision(stalled = false, rebuild = false, capTripped = false, gapMs = gapMs)
        }
        if (consecutive >= maxConsecutive) {
            val tripped = !capped
            capped = true
            return Decision(stalled = true, rebuild = false, capTripped = tripped, gapMs = gapMs)
        }
        consecutive++
        return Decision(stalled = true, rebuild = true, capTripped = false, gapMs = gapMs)
    }

    /**
     * Call after a rebuild, with the clock taken at its end. The rebuild's
     * duration is the natural next gap, and charging it to the trigger is what
     * made the recovery self-sustaining.
     */
    fun onRebuilt(nowNs: Long) {
        lastFrameNs = nowNs
    }

    /**
     * End the current run: the next rebuild starts from a full budget.
     *
     * The gap trigger calls this itself, since its candidates are gaps and a
     * clean frame already ends the run. The coordinator calls it when a *request*
     * was refused by the cap — a pending request makes every frame a candidate,
     * so the run would never clear itself and the cap would deadlock: the very
     * loss that needed repairing would be the one thing keeping the cap closed.
     * The cap is meant to bound rebuilds within one backgrounding, not to hold a
     * lock on the next one.
     */
    fun clearRun() {
        consecutive = 0
        capped = false
    }

    companion object {
        /**
         * Rebuilds allowed back to back. Beyond this the trigger is not a
         * backgrounding but a slow machine or a source that keeps asking, and a
         * low frame rate beats a rebuild per frame.
         */
        const val DEFAULT_MAX_CONSECUTIVE = 3
    }
}
