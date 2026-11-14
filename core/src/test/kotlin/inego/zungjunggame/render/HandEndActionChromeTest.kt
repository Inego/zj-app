package inego.zungjunggame.render

import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.handend.HandEndActionChrome
import inego.zungjunggame.render.handend.HandEndActionHit
import inego.zungjunggame.render.handend.HandEndChipBlock
import inego.zungjunggame.render.handend.HandEndChipRow
import inego.zungjunggame.render.handend.HandEndSizedAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandEndActionChromeTest {

    @Test
    fun countdownAppendsWholeSeconds() {
        assertEquals("New hand  4", HandEndActionChrome.withCountdown("New hand", 4000L))
        assertEquals("New hand  1", HandEndActionChrome.withCountdown("New hand", 1L))
        assertEquals("New hand", HandEndActionChrome.withCountdown("New hand", 0L))
        assertEquals("New hand", HandEndActionChrome.withCountdown("New hand", null))
    }

    @Test
    fun isPrimaryMatchesCtas() {
        assertTrue(HandEndActionChrome.isPrimary(HandEndAction.NextHand))
        assertTrue(HandEndActionChrome.isPrimary(HandEndAction.NewGame))
        assertTrue(HandEndActionChrome.isPrimary(HandEndAction.PlayAgain))
        assertFalse(HandEndActionChrome.isPrimary(HandEndAction.ScoreTable))
        assertFalse(HandEndActionChrome.isPrimary(HandEndAction.ReturnHome))
    }

    @Test
    fun actionAtSkipsDisabledAndMisses() {
        val hits = listOf(
            HandEndActionHit(HandEndAction.ScoreTable, 0f, 0f, 10f, 10f, enabled = true),
            HandEndActionHit(HandEndAction.NextHand, 20f, 0f, 10f, 10f, enabled = false),
        )
        assertEquals(HandEndAction.ScoreTable, HandEndActionChrome.actionAt(hits, 5f, 5f))
        assertNull(HandEndActionChrome.actionAt(hits, 25f, 5f))
        assertNull(HandEndActionChrome.actionAt(hits, 100f, 100f))
    }

    /**
     * Regression: "Back to Online home" is a real chip (as a plain text link it
     * read as a caption), but it keeps its own line under the Scores / Play again
     * row instead of joining it as a third chip.
     */
    @Test
    fun finishedOnlineActionsSplitIntoACtaRowPlusABackHomeRow() {
        val cta = HandEndActionChrome.chipSpecs(
            primaryAction = HandEndAction.PlayAgain,
            primaryLabel = "Play again",
            primaryEnabled = true,
        )
        assertEquals(
            listOf(HandEndAction.ScoreTable, HandEndAction.PlayAgain),
            cta.map { it.action },
        )
        assertTrue(cta.all { it.enabled })

        val under = HandEndActionChrome.underRowSpecs(
            HandEndAction.ReturnHome,
            "Back to Online home",
        )
        assertEquals(listOf(HandEndAction.ReturnHome), under.map { it.action })
        assertTrue(under.single().enabled)
        assertFalse(HandEndActionChrome.isPrimary(HandEndAction.ReturnHome))
    }

    /** An unfinished hand has nothing to leave, so it has no under-row at all. */
    @Test
    fun unfinishedActionsHaveNoBackHomeRow() {
        assertTrue(HandEndActionChrome.underRowSpecs(null, "Back to Online home").isEmpty())
        assertEquals(
            listOf(HandEndAction.ScoreTable, HandEndAction.NextHand),
            HandEndActionChrome.chipSpecs(HandEndAction.NextHand, "New hand", true)
                .map { it.action },
        )
    }

    /**
     * The under-row is placed on its own line, centred on the same axis as the
     * CTA row, one [inego.zungjunggame.render.handend.HandEndChipBlock.rowGap] below it. GL-free, so the rule that
     * keeps the online leave chip off the CTA line is asserted without a backend.
     */
    @Test
    fun backHomeChipIsPlacedOnItsOwnLineUnderTheCtaRow() {
        val block = HandEndChipBlock(
            primary = rowOf(HandEndAction.ScoreTable, HandEndAction.PlayAgain, w = 100f, gap = 10f),
            under = rowOf(HandEndAction.ReturnHome, w = 200f),
            rowGap = 10f,
            width = 210f,
            height = 130f,
        )
        val placed = HandEndActionChrome.placeChipBlock(block, centerX = 500f, bottom = 300f)
        assertEquals(
            listOf(HandEndAction.ScoreTable, HandEndAction.PlayAgain, HandEndAction.ReturnHome),
            placed.map { it.action },
        )
        val (scores, playAgain, backHome) = placed

        // Top line: the CTA row, laid out left to right at the block's top edge.
        assertEquals(370f, scores.bottom)
        assertEquals(370f, playAgain.bottom)
        assertEquals(395f, scores.left)
        assertEquals(505f, playAgain.left)

        // Bottom line: Back to Online home, a full row height further down.
        assertEquals(300f, backHome.bottom)
        assertEquals(360f, backHome.bottom + backHome.h)
        assertTrue(backHome.bottom + backHome.h <= scores.bottom)

        // Each line is centred on the block's axis; the block is one chip tall.
        assertEquals(500f, (scores.left + playAgain.left + playAgain.w) / 2f)
        assertEquals(500f, backHome.left + backHome.w / 2f)
        assertTrue(placed.all { it.h == 60f })
    }

    /** Unscaled chip row of [actions], all [h] tall, used to drive placement tests. */
    private fun rowOf(
        vararg actions: HandEndAction,
        w: Float,
        gap: Float = 0f,
        h: Float = 60f,
    ) = HandEndChipRow(
        chips = actions.map { HandEndSizedAction(it, it.name, w, h, enabled = true) },
        maxH = h,
        gap = gap,
        rowW = w * actions.size + gap * (actions.size - 1),
    )

    /**
     * A finished-online block is two lines tall and still has to fit a narrow
     * (portrait) screen at font scale. The whole block shrinks uniformly instead
     * of being clipped; a row that already fits (or an unbounded width) is left
     * at scale 1 so callers without a width never collapse it.
     */
    @Test
    fun rowFitScaleShrinksOnlyAnOverflowingRow() {
        assertEquals(1f, HandEndActionChrome.rowFitScale(300f, 400f))
        assertEquals(1f, HandEndActionChrome.rowFitScale(400f, 400f))
        assertEquals(1f, HandEndActionChrome.rowFitScale(400f, 0f))
        assertEquals(1f, HandEndActionChrome.rowFitScale(400f, Float.POSITIVE_INFINITY))
        assertEquals(0.8f, HandEndActionChrome.rowFitScale(1000f, 800f))
        assertTrue(HandEndActionChrome.rowFitScale(1431f, 1080f) < 1f)
    }
}
