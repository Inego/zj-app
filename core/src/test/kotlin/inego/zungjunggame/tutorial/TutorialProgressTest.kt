package inego.zungjunggame.tutorial

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Progress-model tool tests with synthetic lesson ids — not campaign content.
 */
class TutorialProgressTest {

    private fun slot(
        backend: InMemoryStateBackend = InMemoryStateBackend(),
    ): StateSlot<TutorialProgressState> = StateSlot(DefaultStateStore(backend), TutorialProgressKey)

    private fun progress(backend: InMemoryStateBackend = InMemoryStateBackend()): TutorialProgress =
        TutorialProgress(slot(backend))

    @Test
    fun emptyProgressUnlocksOnlyOpeners() {
        val p = progress()
        val opener = TutorialTestFixtures.lesson("a")
        val locked = TutorialTestFixtures.lesson("b", requires = listOf("a"))
        assertTrue(p.isUnlocked(opener))
        assertFalse(p.isUnlocked(locked))
    }

    @Test
    fun markCompletedUnlocksNext() {
        val p = progress()
        val locked = TutorialTestFixtures.lesson("b", requires = listOf("a"))
        p.markCompleted("a")
        assertTrue(p.isCompleted("a"))
        assertTrue(p.isUnlocked(locked))
    }

    @Test
    fun markCompletedIsIdempotent() {
        val p = progress()
        p.markCompleted("lesson-x")
        p.markCompleted("lesson-x")
        assertEquals(setOf("lesson-x"), p.completedIds())
    }

    @Test
    fun playStatusNeverPartialCompleted() {
        val p = progress()
        val id = "lesson-y"
        assertEquals(LessonPlayStatus.Never, p.playStatus(id))
        p.markStarted(id)
        assertEquals(LessonPlayStatus.Partial, p.playStatus(id))
        assertTrue(p.isStarted(id))
        assertFalse(p.isCompleted(id))
        p.markCompleted(id)
        assertEquals(LessonPlayStatus.Completed, p.playStatus(id))
    }

    @Test
    fun markStartedIsIdempotentAndSurvivesWithoutComplete() {
        val p = progress()
        p.markStarted("lesson-z")
        p.markStarted("lesson-z")
        assertEquals(setOf("lesson-z"), p.startedIds())
        assertTrue(p.completedIds().isEmpty())
    }

    @Test
    fun markCompletedAlsoRecordsStarted() {
        val p = progress()
        p.markCompleted("lesson-w")
        assertTrue(p.isStarted("lesson-w"))
        assertEquals(LessonPlayStatus.Completed, p.playStatus("lesson-w"))
    }

    /** A restart re-reads the same key; progress is campaign state, not session state. */
    @Test
    fun progressSurvivesANewModelOverTheSameBackend() {
        val backend = InMemoryStateBackend()
        val first = progress(backend)
        first.markStarted("lesson-a")
        first.markCompleted("lesson-b")

        val second = TutorialProgress(slot(backend))
        assertEquals(setOf("lesson-a", "lesson-b"), second.startedIds())
        assertEquals(setOf("lesson-b"), second.completedIds())
    }

    @Test
    fun clearWipesProgress() {
        val p = progress()
        p.markCompleted("lesson-a")
        p.clear()
        assertTrue(p.completedIds().isEmpty())
        assertTrue(p.startedIds().isEmpty())
    }

    @Test
    fun anIdempotentMarkDoesNotRewriteTheKey() {
        val backend = InMemoryStateBackend()
        val p = progress(backend)
        p.markCompleted("lesson-a")
        val written = backend.get(TutorialProgressKey.fullName)?.toList()

        p.markCompleted("lesson-a")

        assertEquals(written, backend.get(TutorialProgressKey.fullName)?.toList())
    }

    @Test
    fun schemaRoundTripsBothSetsAndRejectsCorruption() {
        val state = TutorialProgressState(completed = setOf("b", "a"), started = setOf("c"))
        val encoded = TutorialProgressSchema.encode(state)
        assertEquals(state, TutorialProgressSchema.decode(encoded))

        assertNull(TutorialProgressSchema.decode(ByteArray(0)))
        // A count field with no ids behind it is truncated.
        assertNull(TutorialProgressSchema.decode(RecordWriter().int(1).build()))
        assertNull(TutorialProgressSchema.decode(encoded + "1:x".toByteArray()))
    }
}
