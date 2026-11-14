package inego.zungjunggame.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure cue logic: the win-size bands (including the score gaps between them)
 * and the who / from-whom resolution over the whole legal cell set, plus the
 * invariant that every cue maps to a sample the catalog actually declares.
 */
class SoundCuesTest {

    @Test
    fun winSizeBandsSnapTheGapsDown() {
        assertEquals(WinSize.Chicken, SoundCues.winSizeFor(1))
        assertEquals(WinSize.Small, SoundCues.winSizeFor(2))
        assertEquals(WinSize.Small, SoundCues.winSizeFor(9))
        assertEquals(WinSize.Medium, SoundCues.winSizeFor(10))
        assertEquals(WinSize.Medium, SoundCues.winSizeFor(29), "26..29 snaps down")
        assertEquals(WinSize.Big, SoundCues.winSizeFor(30))
        assertEquals(WinSize.Big, SoundCues.winSizeFor(59), "56..59 snaps down")
        assertEquals(WinSize.Large, SoundCues.winSizeFor(60))
        assertEquals(WinSize.Large, SoundCues.winSizeFor(119), "116..119 snaps down")
        assertEquals(WinSize.Huge, SoundCues.winSizeFor(120))
        assertEquals(WinSize.Huge, SoundCues.winSizeFor(299), "296..299 snaps down")
        assertEquals(WinSize.Epic, SoundCues.winSizeFor(300))
        assertEquals(WinSize.Epic, SoundCues.winSizeFor(320))
    }

    @Test
    fun everyLegalWinCellResolvesAndNeverFillsTheImpossibleCell() {
        val scores = listOf(1, 5, 15, 27, 40, 90, 200, 320)
        for (handScore in scores) {
            for (winner in 0 until 4) {
                for (source in listOf(-1, 0, 1, 2, 3)) {
                    // A seat cannot win off its own discard.
                    if (source == winner) continue
                    val cue = SoundCues.win(handScore, winner, source, mySeat = 2)

                    assertEquals(SoundCues.winSizeFor(handScore), cue.size, "score $handScore")
                    assertEquals(winner == 2, cue.isMe, "winner $winner")
                    val expectedSource = when {
                        source < 0 -> WinSource.SelfDraw
                        source == 2 -> WinSource.FromMe
                        else -> WinSource.FromOther
                    }
                    assertEquals(expectedSource, cue.source, "source $source")
                    // The impossible cell — I win off my own discard — is absent.
                    assertFalse(cue.isMe && cue.source == WinSource.FromMe)
                }
            }
        }
    }

    /** Every distinct outcome owns a sample: the point of the pack, not a mirror. */
    @Test
    fun eachClaimKindAndWinBandHasItsOwnSample() {
        val claimSamples = ClaimSoundKind.entries.map {
            SoundCatalog.specsFor(SoundCue.Claim(it, isMe = true, fromMe = false)).first().sampleId
        }
        assertEquals(
            claimSamples.size, claimSamples.toSet().size,
            "chow/pung/kong share a sample: $claimSamples",
        )

        val winSamples = WinSize.entries.map {
            SoundCatalog.specsFor(SoundCue.Win(it, isMe = true, WinSource.SelfDraw)).first().sampleId
        }
        assertEquals(
            winSamples.size, winSamples.toSet().size,
            "two win bands share a sample: $winSamples",
        )

    }

    /** The Hu sting is the claim-panel prompt's, distinct from any win band. */
    @Test
    fun theHuPromptHasItsOwnSting() {
        assertEquals(
            listOf("hu_claim"),
            SoundCatalog.specsFor(SoundCue.HuPrompt).map { it.sampleId },
        )
    }

    /** A draw is nobody's win, so it must not reuse a win sting. */
    @Test
    fun theExhaustiveDrawHasItsOwnSample() {
        val draw = SoundCatalog.specsFor(SoundCue.ExhaustiveDraw).map { it.sampleId }
        assertEquals(listOf("draw_exhaustive"), draw)
        val winSamples = WinSize.entries.map {
            SoundCatalog.specsFor(SoundCue.Win(it, isMe = true, WinSource.SelfDraw)).first().sampleId
        }
        assertTrue(draw.none { it in winSamples }, "the draw reuses a win sample: $draw")
    }

    /** A claim panel without Hu reuses the neutral attention blip. */
    @Test
    fun theClaimPromptIsAnAttentionBlip() {
        assertEquals(
            listOf("ui_blip"),
            SoundCatalog.specsFor(SoundCue.ClaimPrompt).map { it.sampleId },
        )
    }
}
