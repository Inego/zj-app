package inego.zungjunggame.tutorial.slide

import kotlin.math.PI
import kotlin.math.sin

/**
 * UI-only clock for lesson-slide motion. Headless director never constructs this.
 *
 * Phases: Hidden → Entering → Idle → Exiting → (callback) then typically [show] again.
 * [requestAdvance] settles enter and starts exit in one gesture. No preset
 * animates an exit, so a leave completes in the same frame and the next beat's
 * copy is up at once; an authored exit still runs its own timeline first.
 */
class SlidePresenter {

    enum class Phase {
        Hidden,
        Entering,
        Idle,
        Exiting,
    }

    enum class AdvanceResult {
        /** Exit timeline started (or immediate exit with no effects). */
        StartedExit,
        /** Already exiting; ignore duplicate Next. */
        AlreadyExiting,
        /** Nothing on screen. */
        Ignored,
    }

    var phase: Phase = Phase.Hidden
        private set

    /** Enter, exit, or an idle ambient pulse still has to be presented. */
    fun needsFrames(): Boolean = when (phase) {
        Phase.Entering, Phase.Exiting -> true
        Phase.Idle -> ambientEffects.isNotEmpty()
        Phase.Hidden -> false
    }

    var scene: SlideScene? = null
        private set

    private var enterEffects: List<SlideEffect> = emptyList()
    private var exitEffects: List<SlideEffect> = emptyList()
    private var ambientEffects: List<SlideEffect> = emptyList()

    private var phaseElapsed: Float = 0f
    private var ambientElapsed: Float = 0f
    private var enterDuration: Float = 0f
    private var exitDuration: Float = 0f

    /** Channels forced to settled (continuity — diagram kept). */
    private var heldChannels: Set<SlideChannel> = emptySet()

    private var onExitComplete: (() -> Unit)? = null

    private var cachedFrame: SlideFrame = SlideFrame.SETTLED

    /**
     * Begin a beat. [previous] enables continuity (shared diagram keeps tiles).
     * [onExitComplete] fires once when exit finishes (after Next).
     */
    fun show(
        scene: SlideScene,
        presentation: SlidePresentation = SlidePresentation.None,
        previous: SlideScene? = null,
        onExitComplete: (() -> Unit)? = null,
        settleImmediately: Boolean = false,
    ) {
        this.scene = scene
        this.onExitComplete = onExitComplete

        val composed = SlideStyles.compose(presentation)
        val continuous = presentation.preserveContinuity &&
            previous != null &&
            sameContinuity(previous, scene)

        // A shared diagram stays put across the boundary: its shell and items
        // are held so a continuous beat does not re-cascade them. The copy is
        // not a channel — it swaps with the beat.
        heldChannels = if (continuous) continuityHeldChannels() else emptySet()

        enterEffects = if (continuous) {
            filterEnterForContinuity(composed.enter, previous, scene)
        } else {
            composed.enter
        }
        exitEffects = if (continuous) {
            // Do not fade a shared diagram away between continuous beats.
            composed.exit.mapNotNull { stripHeldFromExit(it) }
        } else {
            composed.exit
        }
        ambientEffects = composed.ambient

        enterDuration = maxTimelineDuration(enterEffects)
        exitDuration = maxTimelineDuration(exitEffects)
        phaseElapsed = 0f
        ambientElapsed = 0f

        phase = when {
            settleImmediately || (enterDuration <= 0f && enterEffects.isEmpty()) -> Phase.Idle
            enterDuration <= 0f -> Phase.Idle
            else -> Phase.Entering
        }
        // Instant enter effects still need end-state weights.
        if (phase == Phase.Idle && enterEffects.isNotEmpty()) {
            phaseElapsed = enterDuration
        }
        rebuildFrame()
    }

    fun hide() {
        phase = Phase.Hidden
        scene = null
        enterEffects = emptyList()
        exitEffects = emptyList()
        ambientEffects = emptyList()
        heldChannels = emptySet()
        phaseElapsed = 0f
        ambientElapsed = 0f
        enterDuration = 0f
        exitDuration = 0f
        onExitComplete = null
        cachedFrame = SlideFrame.SETTLED
    }

    /**
     * Leave the current beat toward [nextScene] (Next **or** Back).
     * Settle enter if needed, then start exit. Exit completion invokes the
     * callback from [show].
     *
     * Pass [nextScene] (and optionally [nextPreserveContinuity]) so a shared
     * diagram is not faded out before the following continuous beat enters.
     */
    fun requestAdvance(
        nextScene: SlideScene? = null,
        nextPreserveContinuity: Boolean = true,
    ): AdvanceResult {
        when (phase) {
            Phase.Hidden -> return AdvanceResult.Ignored
            Phase.Exiting -> return AdvanceResult.AlreadyExiting
            Phase.Entering -> {
                settleEnter()
                prepareContinuityExit(nextScene, nextPreserveContinuity)
                return beginExit()
            }
            Phase.Idle -> {
                prepareContinuityExit(nextScene, nextPreserveContinuity)
                return beginExit()
            }
        }
    }

    /**
     * Hold shared diagram channels through exit when the upcoming beat
     * continues the same family (see [sameContinuity]).
     */
    private fun prepareContinuityExit(next: SlideScene?, preserve: Boolean) {
        val current = scene ?: return
        if (next == null || !preserve) return
        if (!sameContinuity(current, next)) return
        heldChannels = heldChannels + continuityHeldChannels()
        exitEffects = exitEffects.mapNotNull { stripHeldFromExit(it) }
        exitDuration = maxTimelineDuration(exitEffects)
    }

    /**
     * Channels that must stay settled across a continuous beat boundary: the
     * diagram shell and its items.
     */
    private fun continuityHeldChannels(): Set<SlideChannel> =
        setOf(SlideChannel.Diagram, SlideChannel.DiagramItems)

    /**
     * Cancel an in-flight leave without invoking [onExitComplete].
     * Returns to Idle on the current scene so a new [requestAdvance] can leave
     * toward a different neighbor (Back while Next is exiting).
     */
    fun abortLeave() {
        if (phase != Phase.Exiting) return
        phase = Phase.Idle
        phaseElapsed = enterDuration
        rebuildFrame()
    }

    /** Jump current enter or exit timeline to its end weights. */
    fun settle() {
        when (phase) {
            Phase.Entering -> settleEnter()
            Phase.Exiting -> settleExit()
            Phase.Idle, Phase.Hidden -> Unit
        }
    }

    fun update(dtSec: Float) {
        if (dtSec < 0f) return
        when (phase) {
            Phase.Hidden -> return
            Phase.Entering -> {
                phaseElapsed += dtSec
                if (phaseElapsed >= enterDuration) {
                    phaseElapsed = enterDuration
                    phase = Phase.Idle
                    ambientElapsed = 0f
                }
                rebuildFrame()
            }
            Phase.Idle -> {
                if (ambientEffects.isNotEmpty()) {
                    ambientElapsed += dtSec
                    rebuildFrame()
                }
            }
            Phase.Exiting -> {
                phaseElapsed += dtSec
                if (phaseElapsed >= exitDuration) {
                    settleExit()
                } else {
                    rebuildFrame()
                }
            }
        }
    }

    fun frame(): SlideFrame = cachedFrame

    // ── internals ──────────────────────────────────────────────────────────

    private fun settleEnter() {
        phaseElapsed = enterDuration
        phase = Phase.Idle
        ambientElapsed = 0f
        rebuildFrame()
    }

    private fun beginExit(): AdvanceResult {
        if (exitDuration <= 0f && exitEffects.isEmpty()) {
            val cb = onExitComplete
            onExitComplete = null
            phase = Phase.Hidden
            cachedFrame = SlideFrame.SETTLED
            cb?.invoke()
            return AdvanceResult.StartedExit
        }
        phase = Phase.Exiting
        phaseElapsed = 0f
        rebuildFrame()
        if (exitDuration <= 0f) {
            settleExit()
        }
        return AdvanceResult.StartedExit
    }

    private fun settleExit() {
        phaseElapsed = exitDuration
        rebuildFrame()
        val cb = onExitComplete
        onExitComplete = null
        phase = Phase.Hidden
        cb?.invoke()
    }

    private fun rebuildFrame() {
        cachedFrame = when (phase) {
            Phase.Hidden -> SlideFrame.SETTLED
            Phase.Entering -> sampleTimeline(enterEffects, phaseElapsed, entering = true)
            Phase.Idle -> sampleTimeline(enterEffects, enterDuration, entering = true)
                .withAmbient(ambientEffects, ambientElapsed)
            Phase.Exiting -> sampleTimeline(exitEffects, phaseElapsed, entering = false)
                .holding(heldChannels, from = sampleTimeline(enterEffects, enterDuration, entering = true))
        }
    }

    private fun sampleTimeline(
        effects: List<SlideEffect>,
        elapsed: Float,
        entering: Boolean,
    ): SlideFrame {
        val alpha = HashMap<SlideChannel, Float>(8)
        val scale = HashMap<SlideChannel, Float>(4)
        var stagger: SlideEffect.StaggerItems? = null

        // Defaults: entering starts invisible for animated channels; settled otherwise.
        for (ch in SlideChannel.entries) {
            alpha[ch] = 1f
            scale[ch] = 1f
        }
        if (entering) {
            // Channels that appear in Fade from=0 start at 0 until sampled.
            for (e in effects) {
                when (e) {
                    is SlideEffect.Fade -> if (e.from < e.to) {
                        for (ch in e.channels) {
                            if (ch !in heldChannels) alpha[ch] = e.from
                        }
                    }
                    is SlideEffect.Pop -> {
                        if (e.channel !in heldChannels) {
                            alpha[e.channel] = 0f
                            scale[e.channel] = 0.85f
                        }
                    }
                    is SlideEffect.StaggerItems -> {
                        if (e.channel !in heldChannels) stagger = e
                    }
                    is SlideEffect.Pulse -> Unit
                }
            }
            if (stagger != null && SlideChannel.DiagramItems !in heldChannels) {
                // item weights handled below; diagram shell may still fade
            }
        }

        for (e in effects) {
            when (e) {
                is SlideEffect.Fade -> {
                    val u = unitProgress(elapsed, e.delaySec, e.durationSec, e.ease)
                    val a = lerp(e.from, e.to, u)
                    for (ch in e.channels) {
                        if (ch in heldChannels) continue
                        alpha[ch] = a
                    }
                }
                is SlideEffect.StaggerItems -> {
                    if (e.channel !in heldChannels) stagger = e
                }
                is SlideEffect.Pop -> {
                    if (e.channel in heldChannels) continue
                    val u = unitProgress(elapsed, e.delaySec, e.durationSec, e.ease)
                    alpha[e.channel] = u
                    scale[e.channel] = lerp(0.85f, 1f, u)
                }
                is SlideEffect.Pulse -> Unit // ambient only
            }
        }

        for (ch in heldChannels) {
            alpha[ch] = 1f
            scale[ch] = 1f
        }

        val staggerEff = stagger
        val itemWeight: (Int) -> Float = if (staggerEff == null || SlideChannel.DiagramItems in heldChannels) {
            { 1f }
        } else {
            { index ->
                val start = index * staggerEff.delayPerItemSec
                unitProgress(elapsed, start, staggerEff.itemDurationSec, staggerEff.ease)
            }
        }

        return SlideFrame(
            alpha = alpha,
            scale = scale,
            itemWeight = itemWeight,
        )
    }

    private fun SlideFrame.withAmbient(
        ambient: List<SlideEffect>,
        ambientT: Float,
    ): SlideFrame {
        if (ambient.isEmpty()) return this
        val scaleMut = scale.toMutableMap()
        val alphaMut = alpha.toMutableMap()
        for (e in ambient) {
            when (e) {
                is SlideEffect.Pulse -> {
                    val period = e.periodSec
                    val wave = sin(2.0 * PI * (ambientT / period)).toFloat()
                    val s = 1f + e.amplitude * wave
                    scaleMut[e.channel] = (scaleMut[e.channel] ?: 1f) * s
                    // Mild alpha breathe
                    val base = alphaMut[e.channel] ?: 1f
                    alphaMut[e.channel] = (base * (1f + 0.08f * wave)).coerceIn(0.75f, 1f)
                }
                else -> Unit // only Pulse ambient in v1
            }
        }
        return copy(scale = scaleMut, alpha = alphaMut)
    }

    private fun SlideFrame.holding(
        held: Set<SlideChannel>,
        from: SlideFrame,
    ): SlideFrame {
        if (held.isEmpty()) return this
        val a = alpha.toMutableMap()
        val s = scale.toMutableMap()
        for (ch in held) {
            a[ch] = from.alphaOf(ch, 1f)
            s[ch] = from.scaleOf(ch, 1f)
        }
        val items = if (SlideChannel.DiagramItems in held) {
            { _: Int -> 1f }
        } else {
            itemWeight
        }
        return copy(alpha = a, scale = s, itemWeight = items)
    }

    private fun filterEnterForContinuity(
        enter: List<SlideEffect>,
        previous: SlideScene,
        next: SlideScene,
    ): List<SlideEffect> {
        val focusChanged = previous.focus != next.focus
        val calloutChanged =
            previous.focus?.calloutKey != next.focus?.calloutKey ||
                (next.focus?.calloutKey != null && previous.focus?.calloutKey == null)

        return enter.mapNotNull { e ->
            when (e) {
                is SlideEffect.Fade -> {
                    val kept = e.channels.filter { ch ->
                        when (ch) {
                            SlideChannel.Diagram, SlideChannel.DiagramItems -> false
                            SlideChannel.Focus -> focusChanged
                            SlideChannel.Callout -> calloutChanged
                        }
                    }.toSet()
                    if (kept.isEmpty()) null else e.copy(channels = kept)
                }
                is SlideEffect.StaggerItems -> null // never re-stagger continuous diagram
                is SlideEffect.Pop -> if (calloutChanged || focusChanged) e else null
                is SlideEffect.Pulse -> null // ambient only
            }
        }
    }

    private fun stripHeldFromExit(effect: SlideEffect): SlideEffect? {
        if (heldChannels.isEmpty()) return effect
        return when (effect) {
            is SlideEffect.Fade -> {
                val kept = effect.channels - heldChannels
                if (kept.isEmpty()) null else effect.copy(channels = kept)
            }
            is SlideEffect.StaggerItems ->
                if (effect.channel in heldChannels) null else effect
            is SlideEffect.Pop ->
                if (effect.channel in heldChannels) null else effect
            is SlideEffect.Pulse -> effect
        }
    }

    companion object {
        fun sameContinuity(a: SlideScene, b: SlideScene): Boolean {
            val ka = a.resolvedContinuityKey() ?: return false
            val kb = b.resolvedContinuityKey() ?: return false
            return ka == kb
        }

        fun maxTimelineDuration(effects: List<SlideEffect>): Float {
            var max = 0f
            for (e in effects) {
                val end = when (e) {
                    is SlideEffect.Fade -> e.delaySec + e.durationSec
                    is SlideEffect.Pop -> e.delaySec + e.durationSec
                    is SlideEffect.StaggerItems -> {
                        // Unknown item count; budget for ~30 items (full suit grid).
                        val approxItems = 30
                        (approxItems - 1).coerceAtLeast(0) * e.delayPerItemSec + e.itemDurationSec
                    }
                    is SlideEffect.Pulse -> 0f
                }
                if (end > max) max = end
            }
            return max
        }

        fun unitProgress(
            elapsed: Float,
            delaySec: Float,
            durationSec: Float,
            ease: SlideEase,
        ): Float {
            if (elapsed <= delaySec) return 0f
            if (durationSec <= 0f) return 1f
            val t = ((elapsed - delaySec) / durationSec).coerceIn(0f, 1f)
            return ease.apply(t)
        }

        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}
