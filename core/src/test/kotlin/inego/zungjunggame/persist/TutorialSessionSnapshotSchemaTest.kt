package inego.zungjunggame.persist

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.tutorial.TutorialTestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codec / restore tools — synthetic discard scenario only (not curriculum). */
class TutorialSessionSnapshotSchemaTest {

    @Test
    fun restoreResumesLaterCoachNotLessonStart() {
        val scenario = TutorialTestFixtures.multiCoach()
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertEquals(0, runner.director.stepIndex)
        assertEquals("coach.a", runner.director.lastCoachKey)
        assertTrue(runner.acknowledgeCoach())
        assertEquals("coach.b", runner.director.lastCoachKey)
        val laterStep = runner.director.stepIndex
        assertTrue(laterStep > 0)
        val snapshot = runner.captureSnapshot()
        assertEquals(laterStep, snapshot.stepIndex)
        assertEquals("coach.b", snapshot.lastCoachKey)

        val restored = assertNotNull(
            TutorialRunner.restore(scenario, snapshot, blockOnCoach = true),
        )
        assertEquals(laterStep, restored.director.stepIndex)
        assertEquals("coach.b", restored.director.lastCoachKey)
        assertTrue(restored.awaitingCoach)

        val fresh = TutorialRunner.start(scenario, blockOnCoach = true)
        assertEquals(0, fresh.director.stepIndex)
        assertEquals("coach.a", fresh.director.lastCoachKey)
    }

    @Test
    fun restoreResumesWaitAfterCoach() {
        val scenario = TutorialTestFixtures.discardWaitScenario()
        val runner = TutorialRunner.start(scenario, blockOnCoach = true)
        assertTrue(runner.acknowledgeCoach())
        val wait = assertIs<TutorialStep.WaitChoice>(runner.director.currentStep)
        val target = assertNotNull(wait.match.payload)
        val snapshot = runner.captureSnapshot()
        val restored = assertNotNull(
            TutorialRunner.restore(scenario, snapshot, blockOnCoach = true),
        )
        assertEquals(TutorialDirector.Status.Running, restored.status)
        assertIsWaitDiscard(restored)
        assertTrue(restored.submitDiscard(target))
        // After-coach still blocks until acknowledged when blockOnCoach.
        assertEquals(TutorialDirector.Status.Running, restored.status)
        assertTrue(restored.awaitingCoach)
        assertTrue(restored.acknowledgeCoach())
        assertEquals(TutorialDirector.Status.Completed, restored.status)
        assertFalse(restored.active)
    }

    @Test
    fun corruptPayloadReturnsNull() {
        assertNull(TutorialSessionSnapshotSchema.decode(ByteArray(0)))
        assertNull(TutorialSessionSnapshotSchema.decode(byteArrayOf(1, 2, 3, 4)))
        val good = TutorialSessionSnapshotSchema.encode(
            TutorialSessionSnapshot(
                lessonId = "x",
                stepIndex = 0,
                status = TutorialDirector.Status.Running,
                awaitingCoach = true,
                awaitingQuiz = false,
                quizFeedbackFail = false,
                lastCoachKey = "k",
                lastFocus = null,
                lastDiagram = TutorialDiagram.None,
                failureMessage = null,
                pendingLinkPageId = null,
                game = TutorialTestFixtures.discardWaitScenario().layout.materialize(),
            ),
        )
        good[0] = 0
        assertNull(TutorialSessionSnapshotSchema.decode(good))
    }

    @Test
    fun versionsOutsideTheReadableRangeFallBackToTheKeyDefault() {
        val snapshot = migrationFixture()
        val backend = InMemoryStateBackend()
        val store = DefaultStateStore(backend)
        val current = TutorialSessionSnapshotSchema.encode(snapshot)

        // A payload from an older version is not migrated: the key falls back to its default.
        backend.put(TutorialSessionSnapshotKey.fullName, frame(7, current))
        assertNull(store.read(TutorialSessionSnapshotKey))

        // A save from a newer build is unusable, not a crash.
        backend.put(TutorialSessionSnapshotKey.fullName, frame(TutorialSessionSnapshotSchema.version + 1, current))
        assertNull(store.read(TutorialSessionSnapshotKey))
    }

    /**
     * An arbitrary valid mid-lesson snapshot: no focus, an empty wall-deal map, and
     * no quiz hand/interaction state.
     */
    private fun migrationFixture(): TutorialSessionSnapshot = TutorialSessionSnapshot(
        lessonId = "lesson-migration",
        stepIndex = 3,
        status = TutorialDirector.Status.Running,
        awaitingCoach = false,
        awaitingQuiz = true,
        quizFeedbackFail = false,
        lastCoachKey = "coach.key",
        lastFocus = null,
        lastDiagram = TutorialDiagram.None,
        failureMessage = null,
        pendingLinkPageId = null,
        game = TutorialTestFixtures.discardWaitScenario().layout.materialize(),
        quizHandTiles = null,
        quizAwaitingAdvance = false,
        quizInteraction = null,
        wallBuildDeals = emptyMap(),
    )

    /** The store's framing: a 4-byte big-endian schema version, then the payload. */
    private fun frame(version: Int, payload: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(4 + payload.size).putInt(version).put(payload).array()

    private fun assertIsWaitDiscard(runner: TutorialRunner) {
        val step = assertIs<TutorialStep.WaitChoice>(runner.director.currentStep)
        assertEquals(ChoiceKind.Discard, step.match.kind)
    }
}
