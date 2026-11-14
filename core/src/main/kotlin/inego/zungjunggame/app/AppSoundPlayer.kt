package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.audio.SoundCatalog
import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.audio.SoundSpec
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.state.StateSlot
import kotlin.math.pow

/** [System.nanoTime] ticks per millisecond, for turning a spec offset into a due time. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * libGDX-backed [SoundPlayer]. Owns the `Sound` handles it loads and releases them
 * on dispose; nothing else in the app touches `Gdx.audio`.
 *
 * The enabled flag and the level are read from `ui.prefs` at play time, so the
 * Settings card changes them without telling this class. A cue whose sample is
 * absent is skipped once and remembered: the feature ships and plays before its
 * assets land, and a missing file is not re-probed every frame.
 *
 * A cue's layers can be offset from each other. Undelayed layers start on the
 * frame that produced the cue — that is nearly every cue — and only offset ones
 * wait in [scheduled]; routing the rest through the queue would cost every sound
 * in the game a frame. Offsets are measured against the wall clock rather than an
 * accumulated delta, so a deferred layer keeps its intended start time across a
 * stalled or skipped frame, and the frame loop keeps presenting via
 * [hasScheduled] until the queue empties.
 */
internal class AppSoundPlayer(
    private val preferences: StateSlot<UiPreferences>,
) : SoundPlayer, Disposable {

    private val loaded = mutableMapOf<String, Sound>()
    private val missing = mutableSetOf<String>()
    private val dropped = mutableSetOf<String>()

    /** A layer waiting out its [SoundSpec.delayMs] before it starts. */
    private class Scheduled(val dueAtNanos: Long, val spec: SoundSpec, val volume: Float)

    private val scheduled = mutableListOf<Scheduled>()

    init {
        // Warm every sample at construction. Android's SoundPool.load is asynchronous,
        // and a play issued before the load completes is dropped, so loading on the
        // first cue would silence that cue. Starting the loads at app start moves the
        // window off the first draw / discard / win.
        SoundCatalog.files.keys.forEach(::sound)
    }

    override fun play(cue: SoundCue) {
        val prefs = preferences.requireValue()
        if (!prefs.soundsEnabled || prefs.soundVolume <= 0) return
        val level = levelFor(prefs.soundVolume)
        for (spec in SoundCatalog.specsFor(cue)) {
            if (spec.delayMs <= 0) {
                start(spec, level)
            } else {
                scheduled += Scheduled(
                    dueAtNanos = System.nanoTime() + spec.delayMs * NANOS_PER_MILLI,
                    spec = spec,
                    volume = level,
                )
            }
        }
    }

    override fun tick() {
        if (scheduled.isEmpty()) return
        val now = System.nanoTime()
        val due = ArrayList<Scheduled>(scheduled.size)
        val pending = scheduled.iterator()
        while (pending.hasNext()) {
            val entry = pending.next()
            if (entry.dueAtNanos <= now) {
                due += entry
                pending.remove()
            }
        }
        for (entry in due) start(entry.spec, entry.volume)
    }

    override fun hasScheduled(): Boolean = scheduled.isNotEmpty()

    /**
     * The one place a stored step becomes a playback gain.
     *
     * The step is a perceptual scale, not a percentage of amplitude: 0 is silent
     * and 1..10 spans -36 dB..0 dB in 4 dB steps. The obvious linear mapping
     * (`step / MAX`) was the bug: it made steps 1..2 the only quiet range, left
     * the top three within ~2 dB of each other, and made the default a mere 6 dB
     * below full scale. Each step here is the same amplitude ratio (~1.32x), so
     * one tick sounds like the same change anywhere on the track.
     */
    private fun levelFor(step: Int): Float {
        val clamped = step.coerceIn(0, UiPreferences.MAX_SOUND_VOLUME)
        if (clamped <= 0) return 0f
        val db = -36f + 4f * (clamped - 1)
        return 10f.pow(db / 20f)
    }

    private fun start(spec: SoundSpec, level: Float) {
        val sound = sound(spec.sampleId) ?: return
        val stream = sound.play((spec.gain * level).coerceIn(0f, 1f), spec.pitch, spec.pan)
        if (stream < 0 && dropped.add(spec.sampleId)) {
            TouchDiagnostics.log("sound not ready: ${spec.sampleId}")
        }
    }

    private fun sound(sampleId: String): Sound? {
        loaded[sampleId]?.let { return it }
        if (sampleId in missing) return null
        val file = SoundCatalog.files[sampleId]
        if (file == null) {
            missing += sampleId
            return null
        }
        return try {
            val handle = Gdx.files.internal(file)
            if (!handle.exists()) {
                missing += sampleId
                TouchDiagnostics.log("sound missing: $file")
                null
            } else {
                Gdx.audio.newSound(handle).also { loaded[sampleId] = it }
            }
        } catch (e: Exception) {
            missing += sampleId
            TouchDiagnostics.log("sound load failed: $file ${e.message}")
            null
        }
    }

    override fun dispose() {
        scheduled.clear()
        loaded.values.forEach { it.dispose() }
        loaded.clear()
    }
}
