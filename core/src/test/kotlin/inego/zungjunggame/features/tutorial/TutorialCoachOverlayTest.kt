package inego.zungjunggame.features.tutorial

import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.slide.SlidePresentation
import inego.zungjunggame.tutorial.slide.SlidePresenter
import inego.zungjunggame.tutorial.slide.SlideScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorialCoachOverlayTest {

    private fun snapshot(
        lessonId: String = "lesson-1",
        bodyKey: String = "body-1",
        titleKey: String = "title-1",
        focus: TutorialFocus? = null,
        leaving: Boolean = false,
        leaveToward: SlideScene? = null,
        presentation: SlidePresentation = SlidePresentation.Standard,
        boardOverlay: Boolean = false,
        diagram: TutorialDiagram = TutorialDiagram.None,
    ): TutorialCoachSnapshot = TutorialCoachSnapshot(
        visible = true,
        mode = TutorialCoachSnapshot.Mode.Coach,
        titleKey = titleKey,
        bodyKey = bodyKey,
        primaryKey = "tutorial.next",
        diagram = diagram,
        focus = focus,
        lessonId = lessonId,
        presentation = presentation,
        leaving = leaving,
        leaveToward = leaveToward,
        boardOverlay = boardOverlay,
    )

    @Test
    fun firstApplyOfFirstLessonSnapsSettled() {
        val overlay = TutorialCoachOverlay()
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-1"), 0.016f)

        assertTrue(overlay.visible)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)
    }

    @Test
    fun inSceneTargetChangeEases() {
        val overlay = TutorialCoachOverlay()
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-1"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)

        // Same scene, target changes: must ease
        overlay.applyScene(
            snapshot(lessonId = "lesson-1", bodyKey = "slide-1", focus = TutorialFocus("target-a")),
            0.016f,
        )
        assertEquals(SlidePresenter.Phase.Entering, overlay.slidePhase)
    }

    @Test
    fun enteringLaterLessonOnSameInstanceSnapsSettled() {
        val overlay = TutorialCoachOverlay()
        // Lesson 1 is applied on this app-lifetime instance
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-1"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)

        // Enter Lesson 2 on the same instance: must snap settled on the first frame
        overlay.applyScene(snapshot(lessonId = "lesson-2", bodyKey = "lesson-2-intro"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)
    }

    @Test
    fun crossLessonBackOnSameInstanceSnapsSettled() {
        val overlay = TutorialCoachOverlay()
        // Play Lesson 1 then Lesson 2
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-1"), 0.016f)
        overlay.applyScene(snapshot(lessonId = "lesson-2", bodyKey = "slide-1"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)

        // Cross-lesson Back to Lesson 1: must snap settled on the first frame
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-last"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)
    }

    @Test
    fun reappearingAfterHiddenSnapsSettled() {
        val overlay = TutorialCoachOverlay()
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-1"), 0.016f)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)

        // Coach hidden (e.g. play beat / drill / quiz)
        overlay.applyScene(TutorialCoachSnapshot.Absent, 0.016f)
        assertFalse(overlay.visible)

        // Coach re-appears: must snap settled on the first frame
        overlay.applyScene(snapshot(lessonId = "lesson-1", bodyKey = "slide-after-drill"), 0.016f)
        assertTrue(overlay.visible)
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)
    }

    /**
     * The invariant behind the on-demand-rendering contract.
     *
     * `Main.create()` calls `setContinuousRendering(false)`, so the *only* thing that
     * presents a frame is a component asking through `Frame.keepPainting()`. For the
     * coach that ask is exactly this gate, so a clock [TutorialCoachOverlay.update]
     * advances but this gate does not report is a clock that does not animate — it
     * ticks only when unrelated input happens to deliver a frame. That is the
     * reported defect: an untouched tutorial froze mid-pulse, and moving the mouse
     * made it advance in uneven jumps.
     *
     * `SeatWindsView` was the hole (it runs a looping deal rotation and a 1.1 s East
     * pulse but, unlike its wall sibling, published no report). The two
     * `not-a-sequence` peel and the board-overlay felt morph are the same class.
     *
     * Driven through the real gate and the real clock, then swept over every
     * diagram: a newly added animated diagram fails here unless it reports itself.
     */
    @Test
    fun aLoopingDiagramKeepsAskingForFrames() {
        // The reported slide: East player, beat 1. The slide presenter is settled
        // (DiagramIntro has no ambient effects), so the diagram is the only clock.
        val overlay = TutorialCoachOverlay()
        overlay.applyScene(
            snapshot(bodyKey = "tutorial.moves.east_player.four", diagram = TutorialDiagram.SeatWinds),
            0.016f,
        )
        // Settled slide, and still asking: a looping clock has no "done".
        assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase)
        assertTrue(overlay.needsFrames(), "a looping seat-winds diagram must keep presenting")

        // Beat 2 adds the 90 degree deal rotation on top of the pulse.
        overlay.applyScene(
            snapshot(bodyKey = "tutorial.moves.east_player.pass", diagram = TutorialDiagram.SeatWinds),
            0.016f,
        )
        assertTrue(overlay.needsFrames())

        // Hidden: nothing on screen, nothing to present.
        overlay.applyScene(TutorialCoachSnapshot.Absent, 0.016f)
        assertFalse(overlay.visible)
        assertFalse(overlay.needsFrames())
    }

    /**
     * Every clock the overlay advances must be reachable from its gate.
     *
     * A sweep rather than one case per diagram, so adding an animated diagram
     * cannot silently join the frozen class. Each entry is the beat that starts that
     * clock, applied to a fresh overlay and then driven well past its animation
     * window: while the clock runs the gate must report, and a genuinely settled
     * still diagram must stop.
     */
    @Test
    fun everyDiagramReportsItsClockWhileItRuns() {
        val looping = listOf(
            // (diagram, bodyKey) — each is a beat with a clock that never settles.
            Triple(TutorialDiagram.SeatWinds, "tutorial.moves.east_player.four", "East pulse"),
            Triple(TutorialDiagram.SeatWinds, "tutorial.moves.east_player.pass", "deal rotation"),
        )
        for ((diagram, bodyKey, label) in looping) {
            val overlay = TutorialCoachOverlay()
            overlay.applyScene(
                snapshot(bodyKey = bodyKey, diagram = diagram, presentation = SlidePresentation.DiagramIntro),
                0.016f,
            )
            // Drive well past any enter window: the slide has settled, so only the
            // diagram clock can be holding the gate open.
            overlay.applyScene(
                snapshot(bodyKey = bodyKey, diagram = diagram, presentation = SlidePresentation.DiagramIntro),
                1f,
            )
            assertEquals(SlidePresenter.Phase.Idle, overlay.slidePhase, "$label: slide must be settled")
            assertTrue(overlay.needsFrames(), "$label: looping clock must keep presenting")
        }

        // A still diagram with no clock settles and stops asking — otherwise the
        // fix would present every blank for the whole lesson.
        val still = TutorialCoachOverlay()
        still.applyScene(
            snapshot(bodyKey = "body-plain", diagram = TutorialDiagram.TileTypes),
            0.016f,
        )
        still.applyScene(
            snapshot(bodyKey = "body-plain", diagram = TutorialDiagram.TileTypes),
            1f,
        )
        assertEquals(SlidePresenter.Phase.Idle, still.slidePhase)
        assertFalse(still.needsFrames(), "a settled still diagram must stop presenting")
    }
}
