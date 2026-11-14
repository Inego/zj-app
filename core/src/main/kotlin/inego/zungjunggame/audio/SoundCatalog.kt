package inego.zungjunggame.audio

/** One sample to play: which file, when, and how to pitch, pan and mix it. */
data class SoundSpec(
    val sampleId: String,
    val gain: Float = 1f,
    val pitch: Float = 1f,
    val pan: Float = 0f,
    /**
     * Offset from the start of the cue before this layer begins. `0` starts the
     * sample on the presenting frame that produced the cue; anything later is
     * dispatched by the player once the offset has elapsed.
     */
    val delayMs: Int = 0,
)

/**
 * The cue→sample rule. Every distinct outcome has its own sample: three claim
 * kinds, seven win bands, and a dedicated Hu-claim sting. The two prompt cues
 * share the neutral attention blip: Hu upgrades the open to the sting, anything
 * else just says it is your turn.
 *
 * A cue owns its layers, their order and their offsets. Only the who / from-whom
 * axes are applied at playback (gain, pitch, pan), because those are the same
 * event seen from a different seat; but *when* a layer sounds is decided here,
 * since two samples of one moment mixed blind against each other either bury one
 * another or arrive in an order the catalog cannot see.
 *
 * Pure — the app's player only plays what [specsFor] returns — so the whole
 * cue→sound mapping is testable without GL, and re-tuning is a data edit here.
 */
object SoundCatalog {
    /** Stable sample ids → asset filenames under `assets/`. */
    val files: Map<String, String> = mapOf(
        "tile_draw" to "audio/tile_draw.wav",
        "tile_discard" to "audio/tile_discard.wav",
        "shuffle_quick" to "audio/shuffle_quick.wav",
        "claim_chow" to "audio/claim_chow.wav",
        "claim_pung" to "audio/claim_pung.wav",
        "claim_kong" to "audio/claim_kong.wav",
        "claim_mine" to "audio/claim_mine.wav",
        "stolen_victim" to "audio/stolen_victim.wav",
        "stolen_thief" to "audio/stolen_thief.wav",
        "win_chicken" to "audio/win_chicken.wav",
        "win_small" to "audio/win_small.wav",
        "win_medium" to "audio/win_medium.wav",
        "win_big" to "audio/win_big.wav",
        "win_large" to "audio/win_large.wav",
        "win_huge" to "audio/win_huge.wav",
        "win_epic" to "audio/win_epic.wav",
        "draw_exhaustive" to "audio/draw_exhaustive.wav",
        "hu_claim" to "audio/hu_claim.wav",
        "timer_warn" to "audio/timer_warn.wav",
        "timer_urgent" to "audio/timer_urgent.wav",
        "timer_expired" to "audio/timer_expired.wav",
        "ui_blip" to "audio/ui_blip.wav",
    )

    /** How much softer another seat's action reads than the player's own. */
    private const val OTHER_GAIN = 0.7f

    /** Another seat's win is a touch darker than the player's own. */
    private const val OTHER_WIN_GAIN = 0.72f
    private const val OTHER_WIN_PITCH = 0.97f

    /** Which side of the table a claim's source pans to. */
    private const val CLAIM_PAN = 0.35f

    /** The extra "your claim" layer over a claim you made. */
    private const val CLAIM_MINE_GAIN = 0.9f

    /**
     * How long the stolen sting runs before the outcome it interrupts starts.
     *
     * Both stings are ~260-280 ms and 3-6 dB louder than the claim family, so
     * started together they buried the claim entirely — the viewer heard the
     * sting and never heard what beat them. As one cue the layers are ordered
     * here rather than left to two cues racing on the same frame.
     */
    private const val STOLEN_LEAD_MS = 300

    private fun claimSample(kind: ClaimSoundKind): String = when (kind) {
        ClaimSoundKind.Chow -> "claim_chow"
        ClaimSoundKind.Pung -> "claim_pung"
        ClaimSoundKind.Kong -> "claim_kong"
    }

    /**
     * Chow is the light claim: the same family as pung/kong, set just under them.
     *
     * The sources are already peak-normalized to the same ceiling, so their
     * relative loudness lives in each file's RMS — chow ~2 dB under the others.
     * The gap below is the deliberate part of that, and it is small on purpose:
     * at the 0.6 this used to carry, a chow that *won* a contested discard landed
     * ~11 dB under the sting and was effectively inaudible.
     */
    private fun claimGain(kind: ClaimSoundKind): Float = when (kind) {
        ClaimSoundKind.Chow -> 0.9f
        ClaimSoundKind.Pung, ClaimSoundKind.Kong -> 1f
    }

    /** The stolen sting, or nothing when the viewer was not in the contest. */
    private fun stolenSpec(stake: ClaimStake): SoundSpec? = when (stake) {
        ClaimStake.Stolen -> SoundSpec("stolen_victim")
        ClaimStake.Thief -> SoundSpec("stolen_thief")
        ClaimStake.None -> null
    }

    private fun winSample(size: WinSize): String = when (size) {
        WinSize.Chicken -> "win_chicken"
        WinSize.Small -> "win_small"
        WinSize.Medium -> "win_medium"
        WinSize.Big -> "win_big"
        WinSize.Large -> "win_large"
        WinSize.Huge -> "win_huge"
        WinSize.Epic -> "win_epic"
    }

    fun specsFor(cue: SoundCue): List<SoundSpec> = when (cue) {
        is SoundCue.Draw -> listOf(
            if (cue.isMe) {
                SoundSpec("tile_draw")
            } else {
                SoundSpec("tile_draw", gain = OTHER_GAIN, pitch = 0.94f)
            },
        )

        is SoundCue.Discard -> listOf(
            if (cue.isMe) {
                SoundSpec("tile_discard")
            } else {
                SoundSpec("tile_discard", gain = OTHER_GAIN, pitch = 0.95f)
            },
        )

        SoundCue.QuickShuffle -> listOf(SoundSpec("shuffle_quick"))

        is SoundCue.Claim -> buildList {
            val pan = if (cue.fromMe) -CLAIM_PAN else CLAIM_PAN
            // A contested claim is one moment in two beats: the sting that says the
            // window was fought, then the claim that actually landed.
            val claimDelay = if (cue.stake == ClaimStake.None) 0 else STOLEN_LEAD_MS
            stolenSpec(cue.stake)?.let { add(it) }
            add(SoundSpec(claimSample(cue.kind), gain = claimGain(cue.kind), pan = pan, delayMs = claimDelay))
            if (cue.isMe) add(SoundSpec("claim_mine", gain = CLAIM_MINE_GAIN, pan = pan, delayMs = claimDelay))
        }

        // A Hu window is the one moment the sting stands alone: there is no claim
        // sample for a Hu, and the win band it would otherwise lead is deferred to
        // the hand end. So no companion layer and no [STOLEN_LEAD_MS] — the sting
        // is the whole cue. Uncontested, it has nothing to sting about and is silent.
        is SoundCue.Sting -> listOfNotNull(stolenSpec(cue.stake))

        // The Hu sting is for the *prompt* — the moment the panel offers the player
        // a Hu, whether claimed off a discard or drawn — not for the win it may
        // become. Any other claim panel opening reuses the neutral attention blip.
        SoundCue.HuPrompt -> listOf(SoundSpec("hu_claim"))
        SoundCue.ClaimPrompt -> listOf(SoundSpec("ui_blip"))

        // The win band plays alone. A contested Hu already announced itself with a
        // [SoundCue.Sting] when its window resolved, well before the hand ended, so
        // there is no companion layer here and nothing to delay it behind.
        is SoundCue.Win -> listOf(
            SoundSpec(
                winSample(cue.size),
                gain = if (cue.isMe) 1f else OTHER_WIN_GAIN,
                pitch = if (cue.isMe) 1f else OTHER_WIN_PITCH,
            ),
        )

        // A draw is nobody's win: one neutral sample, no who / from-whom axes.
        SoundCue.ExhaustiveDraw -> listOf(SoundSpec("draw_exhaustive"))

        is SoundCue.Timer -> listOf(
            SoundSpec(
                when (cue.stage) {
                    TimerStage.Warn -> "timer_warn"
                    TimerStage.Urgent -> "timer_urgent"
                    TimerStage.Expired -> "timer_expired"
                },
            ),
        )

        SoundCue.Preview -> listOf(SoundSpec("ui_blip"))
    }
}
