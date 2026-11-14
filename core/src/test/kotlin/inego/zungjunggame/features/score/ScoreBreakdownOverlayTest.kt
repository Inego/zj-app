package inego.zungjunggame.features.score

import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.render.handend.HandEndAction
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ScoreBreakdownOverlayTest {

    /**
     * The fold is the owner's remembered display state (`ui.prefs`): the card renders
     * what it is handed and never flips it itself.
     */

    @Test
    fun firstSetCollapsedSnapsThenLaterAppliesEase() {
        val overlay = shownOverlay()
        assertEquals(0f, overlay.foldProgress)
        // First target after construction snaps (restore starts settled).
        overlay.setCollapsed(true)
        assertTrue(overlay.collapsed)
        assertEquals(1f, overlay.foldProgress, 1e-5f)
        // A later target does not snap: it eases from where the fold is.
        overlay.setCollapsed(false)
        assertFalse(overlay.collapsed)
        assertEquals(1f, overlay.foldProgress, 1e-5f)
        overlay.advanceFold(1f)
        assertEquals(0f, overlay.foldProgress, 1e-5f)
    }

    @Test
    fun showRefreshPreservesCollapse() {
        val overlay = shownOverlay()
        overlay.setCollapsed(true)
        assertTrue(overlay.collapsed)
        // Free-play / online sync call show() every frame (countdown, ready).
        overlay.show(
            details = HandScoreDetails(totalScore = 50),
            headline = "You win",
            settlement = emptyList(),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "New hand",
            primaryTimeLeftMs = 4000L,
        )
        assertTrue(overlay.collapsed)
    }

    @Test
    fun advanceFoldReachesCollapsedThenExpanded() {
        val overlay = shownOverlay()
        overlay.setCollapsed(true)
        overlay.advanceFold(1f)
        assertEquals(1f, overlay.foldProgress, 1e-5f)
        overlay.setCollapsed(false)
        overlay.advanceFold(1f)
        assertEquals(0f, overlay.foldProgress, 1e-5f)
        assertFalse(overlay.collapsed)
    }

    @Test
    fun midFoldToggleReversesProgress() {
        val overlay = shownOverlay()
        // Consume the first-apply snap, then ease toward collapsed.
        overlay.setCollapsed(false)
        overlay.setCollapsed(true)
        overlay.advanceFold(0.11f)
        val mid = overlay.foldProgress
        assertTrue(mid > 0f && mid < 1f)
        overlay.setCollapsed(false)
        assertFalse(overlay.collapsed)
        overlay.advanceFold(0.11f)
        assertTrue(overlay.foldProgress < mid)
    }

    @Test
    fun showRefreshPreservesInFlightFold() {
        val overlay = shownOverlay()
        // Consume the first-apply snap, then start an in-flight fold.
        overlay.setCollapsed(false)
        overlay.setCollapsed(true)
        overlay.advanceFold(0.11f)
        val mid = overlay.foldProgress
        assertTrue(mid > 0f && mid < 1f)
        overlay.show(
            details = HandScoreDetails(totalScore = 50),
            headline = "You win",
            settlement = emptyList(),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "New hand",
            primaryTimeLeftMs = 4000L,
        )
        assertEquals(mid, overlay.foldProgress, 1e-5f)
        assertTrue(overlay.collapsed)
    }

    @Test
    fun expandedCardStartsDragToo() {
        val overlay = shownOverlay()
        overlay.seedLayoutForTest()
        assertTrue(overlay.onPointerDown(400f, 240f))
        overlay.onPointerDrag(430f, 270f)
        assertTrue(overlay.dragMoved)
        assertEquals(30f, overlay.parkX, 1e-3f)
        assertEquals(30f, overlay.parkY, 1e-3f)
        val up = overlay.onPointerUp(430f, 270f)
        assertTrue(up.wasDrag)
        assertFalse(up.foldToggle)
        assertTrue(overlay.collapsed.not())
    }

    @Test
    fun expandedChevronAsksForTheFoldOnReleaseNotPress() {
        val overlay = shownOverlay()
        // Chevron sits inside the block's top-right (seeded blockW=240, 32px).
        val chevronLeft = 300f + 240f - 40f
        val chevronBottom = 190f + 100f - 40f
        overlay.seedLayoutForTest(
            chromeLeft = chevronLeft,
            chromeBottom = chevronBottom,
        )
        // Press on the expanded chevron claims the gesture; no fold yet.
        assertTrue(overlay.onPointerDown(chevronLeft + 10f, chevronBottom + 10f))
        assertFalse(overlay.collapsed)
        assertEquals(0f, overlay.foldProgress, 1e-5f)
        // Held frames are inert (no park, no fold).
        overlay.onPointerDrag(chevronLeft + 15f, chevronBottom + 14f)
        assertFalse(overlay.dragMoved)
        // Release on the chevron asks for the fold. The card does not apply it: the
        // owner writes the value and hands it back in.
        val up = overlay.onPointerUp(chevronLeft + 10f, chevronBottom + 10f)
        assertTrue(up.foldToggle)
        assertFalse(overlay.collapsed)
        overlay.setCollapsed(true)
        overlay.advanceFold(1f)
        assertTrue(overlay.collapsed)
    }

    @Test
    fun expandedChevronReleaseOffButtonCancels() {
        val overlay = shownOverlay()
        val chevronLeft = 300f + 240f - 40f
        val chevronBottom = 190f + 100f - 40f
        overlay.seedLayoutForTest(
            chromeLeft = chevronLeft,
            chromeBottom = chevronBottom,
        )
        assertTrue(overlay.onPointerDown(chevronLeft + 10f, chevronBottom + 10f))
        val up = overlay.onPointerUp(400f, 240f)
        assertFalse(up.foldToggle)
        assertFalse(overlay.collapsed)
    }

    @Test
    fun dragPastSlopParksCollapsedBlock() {
        val overlay = shownCollapsed()
        assertTrue(overlay.onPointerDown(400f, 260f))
        overlay.onPointerDrag(430f, 290f)
        assertTrue(overlay.dragMoved)
        assertEquals(30f, overlay.parkX, 1e-3f)
        assertEquals(30f, overlay.parkY, 1e-3f)
        val up = overlay.onPointerUp(430f, 290f)
        assertTrue(up.wasDrag)
        assertFalse(up.foldToggle)
        assertEquals(null, up.action)
        assertTrue(overlay.collapsed)
    }

    @Test
    fun tapOnCollapsedCardAsksToExpandWithoutParking() {
        val overlay = shownCollapsed()
        assertTrue(overlay.onPointerDown(400f, 260f))
        overlay.onPointerDrag(403f, 262f)
        assertFalse(overlay.dragMoved)
        val up = overlay.onPointerUp(403f, 262f)
        assertTrue(up.foldToggle)
        assertFalse(up.wasDrag)
        // The request is the owner's to apply; until then the card is still folded.
        assertTrue(overlay.collapsed)
        overlay.setCollapsed(false)
        assertFalse(overlay.collapsed)
        assertEquals(0f, overlay.parkX)
        assertEquals(0f, overlay.parkY)
    }

    @Test
    fun tapOnNewHandFiresAction() {
        val overlay = shownCollapsed()
        // Button is seeded at block left/bottom (280, 190) size 110×40.
        assertTrue(overlay.onPointerDown(300f, 210f))
        val up = overlay.onPointerUp(300f, 210f)
        assertEquals(HandEndAction.NextHand, up.action)
        assertFalse(up.wasDrag)
        assertFalse(up.foldToggle)
        assertTrue(overlay.collapsed)
    }

    @Test
    fun dragFromNewHandDoesNotFireAction() {
        val overlay = shownCollapsed()
        assertTrue(overlay.onPointerDown(300f, 210f))
        overlay.onPointerDrag(360f, 260f)
        val up = overlay.onPointerUp(360f, 260f)
        assertTrue(up.wasDrag)
        assertEquals(null, up.action)
        assertTrue(overlay.collapsed)
    }

    @Test
    fun parkClampsToScreen() {
        val overlay = shownCollapsed()
        overlay.onPointerDown(400f, 260f)
        overlay.onPointerDrag(400f + 10_000f, 260f)
        assertTrue(overlay.parkX <= 280.001f)
        assertTrue(overlay.parkX > 0f)
    }

    @Test
    fun pressOnCollapsedCardActivatesGrabHighlight() {
        val overlay = shownCollapsed()
        assertFalse(overlay.grabHighlightActive(Float.NaN, Float.NaN))
        assertTrue(overlay.grabHighlightActive(400f, 260f))
        assertTrue(overlay.onPointerDown(400f, 260f))
        assertTrue(overlay.dragging)
        assertTrue(overlay.grabHighlightActive(Float.NaN, Float.NaN))
        overlay.onPointerUp(400f, 260f)
        assertFalse(overlay.dragging)
        assertFalse(overlay.grabHighlightActive(Float.NaN, Float.NaN))
    }

    @Test
    fun pressOnNewHandDoesNotGrabHighlightCard() {
        val overlay = shownCollapsed()
        assertTrue(overlay.onPointerDown(300f, 210f))
        assertTrue(overlay.dragging)
        assertFalse(overlay.grabHighlightActive(Float.NaN, Float.NaN))
        overlay.onPointerDrag(360f, 260f)
        assertTrue(overlay.dragMoved)
        assertTrue(overlay.grabHighlightActive(Float.NaN, Float.NaN))
        overlay.onPointerUp(360f, 260f)
        assertFalse(overlay.grabHighlightActive(Float.NaN, Float.NaN))
    }

    @Test
    fun expandedCardHeldLightsGrabHighlight() {
        val overlay = shownOverlay()
        overlay.seedLayoutForTest()
        assertTrue(overlay.onPointerDown(400f, 260f))
        assertTrue(overlay.dragging)
        assertTrue(overlay.grabHighlightActive(Float.NaN, Float.NaN))
        overlay.onPointerUp(400f, 260f)
        assertFalse(overlay.dragging)
        assertFalse(overlay.grabHighlightActive(Float.NaN, Float.NaN))
    }

    @Test
    fun bodyScrollDoesNotParkWhenOverflowing() {
        val overlay = shownOverlay()
        overlay.seedLayoutForTest()
        overlay.seedBodyOverflowForTest(contentH = 400f, viewH = 48f)
        assertTrue(overlay.bodyOverflowingForTest)
        // Panel is the top 48px of the 100px block (seed: panelBottom = 242).
        assertTrue(overlay.onPointerDown(400f, 260f))
        overlay.onPointerDrag(400f, 300f)
        assertTrue(overlay.pressBodyScrollForTest)
        assertFalse(overlay.dragMoved)
        assertEquals(0f, overlay.parkX)
        assertEquals(0f, overlay.parkY)
        assertTrue(overlay.bodyScrollYForTest > 0f)
        val up = overlay.onPointerUp(400f, 300f)
        assertTrue(up.wasDrag)
        assertEquals(null, up.action)
        // A body drag reports the offset it settled at so the model can keep it.
        assertEquals(overlay.bodyScrollYForTest, up.bodyScrollY)
        assertTrue((up.bodyScrollY ?: 0f) > 0f)
    }

    @Test
    fun titleBandStillParksWhenBodyOverflows() {
        val overlay = shownOverlay()
        overlay.seedLayoutForTest()
        overlay.seedBodyOverflowForTest(contentH = 400f, viewH = 20f)
        // Button strip under the card (block 190–242) is not the body viewport.
        assertTrue(overlay.onPointerDown(400f, 210f))
        overlay.onPointerDrag(430f, 240f)
        assertFalse(overlay.pressBodyScrollForTest)
        assertTrue(overlay.dragMoved)
        assertEquals(30f, overlay.parkX, 1e-3f)
        assertEquals(30f, overlay.parkY, 1e-3f)
    }

    /**
     * The park is the player's arrangement, not the showing's: a refresh keeps it,
     * and the component hands the stored offset back in, so the next hand's card
     * appears where the player left it.
     */
    @Test
    fun parkOutlivesARefreshAndIsRestoredFromTheModel() {
        val overlay = shownCollapsed()
        overlay.onPointerDown(400f, 260f)
        overlay.onPointerDrag(450f, 280f)
        overlay.onPointerUp(450f, 280f)
        val parkedX = overlay.parkX
        val parkedY = overlay.parkY
        assertTrue(parkedX != 0f || parkedY != 0f)
        overlay.show(
            details = HandScoreDetails(totalScore = 50),
            headline = "You win",
            settlement = emptyList(),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "New hand",
        )
        assertEquals(parkedX, overlay.parkX, 1e-5f)
        assertEquals(parkedY, overlay.parkY, 1e-5f)
        overlay.restorePark(0f, 0f)
        assertEquals(0f, overlay.parkX)
        assertEquals(0f, overlay.parkY)
    }

    @Test
    fun blankGameResultIsTreatedAsNoResult() {
        val overlay = ScoreBreakdownOverlay()
        overlay.show(
            details = HandScoreDetails(totalScore = 10),
            headline = "You win",
            settlement = emptyList(),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "New hand",
            gameResult = "   ",
        )
        assertNull(overlay.gameResultLine)
    }

    private fun shownCollapsed(): ScoreBreakdownOverlay {
        val overlay = shownOverlay()
        overlay.setCollapsed(true)
        overlay.advanceFold(1f)
        overlay.seedLayoutForTest()
        return overlay
    }

    private fun shownOverlay(): ScoreBreakdownOverlay {
        val overlay = ScoreBreakdownOverlay()
        val details = HandScoreDetails(totalScore = 45)
        details.components += HandScoreComponent("pattern.concealed_hand", 5)
        details.components += HandScoreComponent("pattern.all_simples", 5)
        overlay.show(
            details = details,
            headline = "You win on discard from Left",
            settlement = listOf(
                SettlementSeatLine("Left", 0, -15, -15, isHuman = false),
                SettlementSeatLine("You", 0, 45, 45, isHuman = true),
            ),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "New hand",
        )
        return overlay
    }
}
