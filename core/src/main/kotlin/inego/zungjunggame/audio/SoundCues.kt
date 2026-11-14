package inego.zungjunggame.audio

import inego.zungjunggame.session.online.ClaimBidder

/**
 * Pure derivation of a cue's axes. A producer hands seat numbers and a hand score
 * in; the banding and the who / from-whom resolution live here so every producer
 * agrees and the whole cell set can be swept in one test.
 */
object SoundCues {
    /**
     * Win-size band for a winning hand's pattern score:
     *
     * `1` · `2..9` · `10..29` · `30..59` · `60..119` · `120..299` · `300+`.
     *
     * The cuts are integer thresholds, not multiples of five: flower/season
     * bonuses make any score reachable, so the gaps between the named pattern
     * scores snap **down** to the lower band.
     *
     * `0` (an exhaustive draw) bands as [WinSize.Chicken]; callers only ask for a
     * win cue when the score is positive.
     */
    fun winSizeFor(handScore: Int): WinSize = when {
        handScore <= 1 -> WinSize.Chicken
        handScore < 10 -> WinSize.Small
        handScore < 30 -> WinSize.Medium
        handScore < 60 -> WinSize.Big
        handScore < 120 -> WinSize.Large
        handScore < 300 -> WinSize.Huge
        else -> WinSize.Epic
    }

    /**
     * The win cue from the seats the engine reports: [winnerSeat], [winSource]
     * (`-1` = self-draw), and the viewer's [mySeat].
     *
     * A contested window is not this cue's business: it reports itself at the
     * moment it resolved ([SoundCue.Sting]), so the win band here plays alone.
     *
     * `isMe && FromMe` cannot occur for a legal hand — a seat cannot win off its
     * own discard — so the caller may rely on that cell being absent.
     */
    fun win(
        handScore: Int,
        winnerSeat: Int,
        winSource: Int,
        mySeat: Int,
    ): SoundCue.Win {
        val source = when {
            winSource < 0 -> WinSource.SelfDraw
            winSource == mySeat -> WinSource.FromMe
            else -> WinSource.FromOther
        }
        return SoundCue.Win(
            size = winSizeFor(handScore),
            isMe = winnerSeat == mySeat,
            source = source,
        )
    }

    /**
     * The viewer's stake in the reaction window that [winnerSeat] won: [ClaimStake.Stolen]
     * when the viewer bid on that discard and was outbid, [ClaimStake.Thief] when the
     * viewer's own bid took it, [ClaimStake.None] otherwise.
     *
     * [bidders] is the backend's explicit participant list for the resolved
     * window — every seat that bid, not only the winner — and it is the *only*
     * input that says the window was contested. Never infer the contest from a
     * count of anything else, from what the viewer was prompted with, or from
     * decision state: those are what made the two producers answer "what just
     * happened" differently. A one-seat list is a lone bid, not a contest, so an
     * absent or uncontested list reads as [ClaimStake.None] for everyone.
     */
    fun stakeOf(winnerSeat: Int, bidders: List<ClaimBidder>, mySeat: Int): ClaimStake {
        val seats = bidders.mapTo(mutableSetOf()) { it.seat }
        return when {
            seats.size < 2 -> ClaimStake.None
            mySeat in seats && mySeat != winnerSeat -> ClaimStake.Stolen
            mySeat == winnerSeat -> ClaimStake.Thief
            else -> ClaimStake.None
        }
    }

    /**
     * The claim cue from the claimant, the seat that discarded the claimed tile,
     * and the viewer's [stake] in the window that awarded it.
     */
    fun claim(
        kind: ClaimSoundKind,
        claimantSeat: Int,
        sourceSeat: Int,
        mySeat: Int,
        stake: ClaimStake,
    ): SoundCue.Claim = SoundCue.Claim(
        kind = kind,
        isMe = claimantSeat == mySeat,
        fromMe = sourceSeat == mySeat,
        stake = stake,
    )

    /**
     * The prompt-open cue for the viewer: a Hu offer gets its own sting — claimed
     * off a discard or completing a self-drawn hand alike — any other claim panel
     * an attention blip, and everything that shows no prompt stays silent.
     *
     * [offersHu] outranks [isClaimPanel] because a self-drawn Hu is an `ActOnHand`
     * prompt, not a claim: the Hu is the fact worth sounding, and the panel kind
     * only separates a non-Hu claim from silence.
     *
     * Both producers — the local board emitter and the online presentation
     * sequencer — classify through here, so Hu-vs-attention is decided once.
     */
    fun promptOpen(isClaimPanel: Boolean, offersHu: Boolean): SoundCue? = when {
        offersHu -> SoundCue.HuPrompt
        isClaimPanel -> SoundCue.ClaimPrompt
        else -> null
    }

    /** A wall draw by [seat], from [mySeat]'s point of view. */
    fun draw(seat: Int, mySeat: Int): SoundCue.Draw = SoundCue.Draw(isMe = seat == mySeat)

    /** A discard by [seat], from [mySeat]'s point of view. */
    fun discard(seat: Int, mySeat: Int): SoundCue.Discard = SoundCue.Discard(isMe = seat == mySeat)

    /** A countdown threshold cue. */
    fun timer(stage: TimerStage): SoundCue.Timer = SoundCue.Timer(stage)
}
