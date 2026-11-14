package inego.zungjunggame.audio

/**
 * A semantic table sound: what happened, plus the axes that make the same event
 * sound different depending on who acted and how hard.
 *
 * The vocabulary is pure — no GL, no `session` types — so `session/` can produce
 * cues and `app/` can play them without either depending on the other. Which
 * sample, pitch, pan and layers a cue maps to is [SoundCatalog]'s job.
 *
 * The one place a cue *derivation* names a `session` value is
 * [SoundCues.stakeOf], which reads the transport-neutral
 * [inego.zungjunggame.session.online.ClaimBidder] so that both producers' bidder
 * lists become a stake through a single rule. The cues themselves stay free of it.
 */
sealed interface SoundCue {
    /** A tile taken from the wall; [isMe] is the drawer. */
    data class Draw(val isMe: Boolean) : SoundCue

    /** A tile discarded; [isMe] is the discarder. */
    data class Discard(val isMe: Boolean) : SoundCue

    /** A fresh table's wall is shuffled and dealt. */
    data object QuickShuffle : SoundCue

    /**
     * A meld claim resolved.
     *
     * [stake] is the viewer's own position in the window that resolved it. A
     * claim is only ever contested, so the sting for "you bid and lost it" (or
     * "you beat someone who bid") belongs on this cue rather than beside it: two
     * cues for one moment can be ordered wrong, and each would have to be mixed
     * blind against a sample it cannot see.
     */
    data class Claim(
        val kind: ClaimSoundKind,
        val isMe: Boolean,
        val fromMe: Boolean,
        val stake: ClaimStake = ClaimStake.None,
    ) : SoundCue

    /** The local player is offered a Hu: claiming a discard or completing a self-drawn hand. */
    data object HuPrompt : SoundCue

    /**
     * A reaction window that resolved to a Hu, reported at the moment it resolved.
     *
     * This is the one moment a stake is the whole cue. The claim sample does not
     * exist for a Hu — Hu is [SoundCue.Win], not a claim — and the win band is
     * deliberately deferred to the hand end, so nothing else sounds here for the
     * sting to be mixed or ordered against. [ClaimStake.None] is silent: an
     * uncontested window has nothing to sting about.
     */
    data class Sting(val stake: ClaimStake = ClaimStake.None) : SoundCue

    /** A claim panel opened without a Hu offer: a blip that says it is your turn. */
    data object ClaimPrompt : SoundCue

    /**
     * A hand was won — the band, at the hand end.
     *
     * A contested window is not this cue's business: it reported itself at the
     * moment it resolved ([SoundCue.Sting]), and the win band is deliberately
     * deferred to here. One moment, one cue.
     */
    data class Win(
        val size: WinSize,
        val isMe: Boolean,
        val source: WinSource,
    ) : SoundCue

    /** The wall ran out with no winner: an exhaustive draw. */
    data object ExhaustiveDraw : SoundCue

    /** A countdown threshold in an online match. */
    data class Timer(val stage: TimerStage) : SoundCue

    /** The Settings volume slider's audition blip. */
    data object Preview : SoundCue
}

/**
 * The claim kind, from the sound's point of view (Hu is a [SoundCue.Win], not a
 * claim — and its window sting is a [SoundCue.Sting], not a claim kind either).
 */
enum class ClaimSoundKind { Chow, Pung, Kong }

/**
 * The viewer's stake in a reaction window that more than one seat bid on.
 *
 * A priority override is one moment, so this is an axis of the cue that reports
 * that moment rather than a cue beside it: two cues for one moment can be ordered
 * wrong, and each would have to be mixed blind against a sample the catalog is not
 * emitting alongside it.
 *
 * Which cue that is depends on what resolved the window. A meld claim reports
 * itself as [SoundCue.Claim], which carries the sting ahead of the claim it
 * interrupted. A Hu is the [SoundCue.Win] sound and plays at the hand end, so
 * at the window a Hu outcome has no companion sample at all — the stake is the
 * whole cue, as [SoundCue.Sting]. That is the one moment the sting stands alone.
 */
enum class ClaimStake {
    /** The viewer bid on the discard and a higher-priority bid took it. */
    Stolen,

    /** The viewer's claim beat another seat's bid for the same discard. */
    Thief,

    /** The discard was uncontested, or the contest did not involve the viewer. */
    None,
}

/**
 * Winning-hand size, banded from the pattern score. Seven bands so the top of the
 * scoring table (chicken at 1 through the 320 cap) stays distinguishable.
 */
enum class WinSize { Chicken, Small, Medium, Big, Large, Huge, Epic }

/** Where the winning tile came from, relative to the viewer. */
enum class WinSource { SelfDraw, FromMe, FromOther }

/** A countdown threshold in an online match. */
enum class TimerStage { Warn, Urgent, Expired }
