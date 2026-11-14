package inego.zungjunggame.tutorial

import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.Schema
import inego.zungjunggame.state.StateKey
import inego.zungjunggame.state.StateSlot

private const val MAX_IDS = 512

/**
 * Campaign progress: the lesson ids the player has completed and started.
 *
 * Two sets rather than the legacy comma-joined strings, so a lesson id can never
 * split into two entries.
 */
data class TutorialProgressState(
    val completed: Set<String> = emptySet(),
    val started: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = TutorialProgressState()
    }
}

/**
 * Versioned codec for [TutorialProgressState].
 *
 * v1: completed count + ids, then started count + ids (each sorted on the way
 * out, so the bytes are stable for a given state).
 */
object TutorialProgressSchema : Schema<TutorialProgressState> {
    override val version: Int = 1

    override fun encode(value: TutorialProgressState): ByteArray {
        val writer = RecordWriter().int(value.completed.size)
        value.completed.sorted().forEach { writer.field(it) }
        writer.int(value.started.size)
        value.started.sorted().forEach { writer.field(it) }
        return writer.build()
    }

    override fun decode(bytes: ByteArray): TutorialProgressState? = try {
        val reader = RecordReader(bytes)
        val completed = reader.readIds()
        val started = reader.readIds()
        if (!reader.atEnd()) return null
        TutorialProgressState(completed = completed, started = started)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun RecordReader.readIds(): Set<String> {
        val size = count(MAX_IDS)
        val ids = LinkedHashSet<String>(size)
        repeat(size) { ids += field() }
        return ids
    }
}

/** Persisted campaign progress: namespace `tutorial`, name `progress`. */
val TutorialProgressKey: StateKey<TutorialProgressState> = StateKey(
    namespace = "tutorial",
    name = "progress",
    schema = TutorialProgressSchema,
    default = { TutorialProgressState.EMPTY },
)

/**
 * Campaign progress model.
 *
 * Owns the in-memory sets and writes them through [slot] on every change; an
 * idempotent mark does not write. Survives free-play and app restarts, and is
 * independent of the mid-lesson save
 * (the `session.tutorial` key) and of the free-play
 * the `session.freeplay` key.
 *
 * Linear unlock is derived from [LessonDef.requires] + the completed set (used
 * by resume; the nav tree allows a jump to any lesson).
 */
class TutorialProgress(
    private val slot: StateSlot<TutorialProgressState>,
) : TutorialProgressView {
    private var state: TutorialProgressState = slot.requireValue()

    fun completedIds(): Set<String> = state.completed

    fun startedIds(): Set<String> = state.started

    override fun isCompleted(lessonId: String): Boolean = lessonId in state.completed

    fun isStarted(lessonId: String): Boolean = lessonId in state.started

    override fun playStatus(lessonId: String): LessonPlayStatus = when {
        isCompleted(lessonId) -> LessonPlayStatus.Completed
        isStarted(lessonId) -> LessonPlayStatus.Partial
        else -> LessonPlayStatus.Never
    }

    override fun isUnlocked(lesson: LessonDef): Boolean {
        return lesson.requires.isEmpty() || lesson.requires.all { it in state.completed }
    }

    /** Mark [lessonId] entered (idempotent). Does not complete. */
    fun markStarted(lessonId: String) {
        if (lessonId.isBlank() || lessonId in state.started) return
        update(state.copy(started = state.started + lessonId))
    }

    /** Mark [lessonId] complete (idempotent). */
    fun markCompleted(lessonId: String) {
        if (lessonId.isBlank()) return
        if (lessonId in state.completed && lessonId in state.started) return
        update(
            state.copy(
                completed = state.completed + lessonId,
                // Completed lessons also count as started, for status history.
                started = state.started + lessonId,
            ),
        )
    }

    /** Test / debug: wipe progress. */
    fun clear() {
        if (state == TutorialProgressState.EMPTY) return
        update(TutorialProgressState.EMPTY)
    }

    /** Immutable copy of the campaign sets for a frame snapshot. */
    fun snapshot(): TutorialProgressState = state

    private fun update(next: TutorialProgressState) {
        state = next
        slot.set(next)
    }
}

/** Read-only [TutorialProgressView] over a frozen [TutorialProgressState]. */
fun TutorialProgressState.asView(): TutorialProgressView = TutorialProgressStateView(this)

private data class TutorialProgressStateView(
    private val state: TutorialProgressState,
) : TutorialProgressView {
    override fun isCompleted(lessonId: String): Boolean = lessonId in state.completed

    override fun isUnlocked(lesson: LessonDef): Boolean {
        return lesson.requires.isEmpty() || lesson.requires.all { it in state.completed }
    }

    override fun playStatus(lessonId: String): LessonPlayStatus = when (lessonId) {
        in state.completed -> LessonPlayStatus.Completed
        in state.started -> LessonPlayStatus.Partial
        else -> LessonPlayStatus.Never
    }
}
