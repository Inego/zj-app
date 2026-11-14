package inego.zungjunggame.features.tutorial

import inego.zungjunggame.tutorial.TutorialCatalog
import inego.zungjunggame.tutorial.TutorialNavEntry
import inego.zungjunggame.tutorial.TutorialProgressState
import inego.zungjunggame.tutorial.asView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorialShellViewTest {

    private fun snapshot(
        progress: TutorialProgressState = TutorialProgressState.EMPTY,
        currentLessonId: String? = null,
        inTutorialSession: Boolean = true,
    ) = TutorialSnapshot(
        progress = progress,
        currentLessonId = currentLessonId,
        showsBoard = true,
        lastSpotlight = "hand",
        quizVisible = false,
        coachVisible = false,
        inTutorialSession = inTutorialSession,
    )

    private fun view(
        snapshot: TutorialSnapshot = snapshot(),
        selectedLessonId: String = "lesson",
        entries: List<TutorialNavEntry> = emptyList(),
    ) = TutorialShellView(
        hosted = snapshot.inTutorialSession,
        entries = entries,
        currentLessonId = snapshot.currentLessonId,
        selectedLessonId = selectedLessonId,
        showsBoard = snapshot.showsBoard,
        lastSpotlight = snapshot.lastSpotlight,
        quizVisible = snapshot.quizVisible,
        coachVisible = snapshot.coachVisible,
    )

    @Test
    fun projectionIsAPureFunctionOfTheSnapshot() {
        val source = snapshot(currentLessonId = "x")

        val first = tutorialShellView(source)
        val second = tutorialShellView(source)

        assertEquals(first, second)
        assertEquals(first.entries, TutorialCatalog.navEntries(TutorialProgressState.EMPTY.asView()))
    }

    @Test
    fun projectionSelectsTheCurrentLessonWhenItIsInTheToc() {
        val entries = TutorialCatalog.navEntries(TutorialProgressState.EMPTY.asView())
        val lessonId = entries.filterIsInstance<TutorialNavEntry.Lesson>().first().lessonId
        val projected = tutorialShellView(snapshot(currentLessonId = lessonId))

        assertTrue(projected.hosted)
        assertEquals(entries, projected.entries)
        assertEquals(lessonId, projected.selectedLessonId)
        assertEquals("hand", projected.lastSpotlight)
        assertTrue(projected.showsBoard)
    }

    @Test
    fun projectionFallsBackToTheFirstLessonWhenCurrentIsUnknown() {
        val projected = tutorialShellView(snapshot(currentLessonId = "not-a-lesson"))
        val first = projected.entries.filterIsInstance<TutorialNavEntry.Lesson>().first().lessonId

        assertEquals(first, projected.selectedLessonId)
    }

    @Test
    fun anUnchangedViewDoesNotRevealAndDoesNotClobberAPlayerScroll() {
        val current = view(snapshot(currentLessonId = "a"), selectedLessonId = "a")
        val previous = ShellReconcileKey(current)

        val reconcile = shellReconcile(previous, current, storedAbsent = false)

        assertFalse(reconcile.reveal)
        assertFalse(reconcile.resetScroll)
    }

    @Test
    fun aRestoredStoreOnTheFirstFrameIsAppliedAsIs() {
        val current = view(snapshot(currentLessonId = "a"), selectedLessonId = "a")

        val reconcile = shellReconcile(previous = null, current, storedAbsent = false)

        assertFalse(reconcile.reveal)
        assertFalse(reconcile.resetScroll)
    }

    @Test
    fun aFreshEmptyStoreRevealsFromZero() {
        val current = view(snapshot(currentLessonId = "a"), selectedLessonId = "a")

        val reconcile = shellReconcile(previous = null, current, storedAbsent = true)

        assertTrue(reconcile.reveal)
        assertTrue(reconcile.resetScroll)
    }

    @Test
    fun aLessonChangeRevealsWithoutResettingThePlayerOffset() {
        val previousView = view(snapshot(currentLessonId = "a"), selectedLessonId = "a")
        val next = view(snapshot(currentLessonId = "b"), selectedLessonId = "b")

        val reconcile = shellReconcile(ShellReconcileKey(previousView), next, storedAbsent = false)

        assertTrue(reconcile.reveal)
        assertFalse(reconcile.resetScroll)
        assertEquals(
            reconcile,
            shellReconcile(ShellReconcileKey(previousView), next, storedAbsent = false),
        )
    }

    @Test
    fun anEntriesChangeRevealsWithoutResettingThePlayerOffset() {
        val lesson = TutorialNavEntry.Lesson("a", "tutorial.a", inego.zungjunggame.tutorial.LessonPlayStatus.Never)
        val started = TutorialNavEntry.Lesson("a", "tutorial.a", inego.zungjunggame.tutorial.LessonPlayStatus.Partial)
        val previousView = view(entries = listOf(lesson), selectedLessonId = "a")
        val next = view(entries = listOf(started), selectedLessonId = "a")

        val reconcile = shellReconcile(ShellReconcileKey(previousView), next, storedAbsent = false)

        assertTrue(reconcile.reveal)
        assertFalse(reconcile.resetScroll)
    }
}
