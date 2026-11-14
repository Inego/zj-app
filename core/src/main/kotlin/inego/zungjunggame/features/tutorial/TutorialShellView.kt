package inego.zungjunggame.features.tutorial

import inego.zungjunggame.tutorial.TutorialCatalog
import inego.zungjunggame.tutorial.TutorialNavEntry
import inego.zungjunggame.tutorial.asView

/**
 * The tutorial shell's model-derived view for one frame: the TOC list, the
 * selected lesson, and the lesson facts the spotlight hint needs.
 */
data class TutorialShellView(
    val hosted: Boolean,
    val entries: List<TutorialNavEntry>,
    val currentLessonId: String?,
    val selectedLessonId: String,
    val showsBoard: Boolean,
    val lastSpotlight: String?,
    val quizVisible: Boolean,
    val coachVisible: Boolean,
)

internal fun tutorialShellView(snapshot: TutorialSnapshot): TutorialShellView {
    val entries = TutorialCatalog.navEntries(snapshot.progress.asView())
    return TutorialShellView(
        hosted = snapshot.inTutorialSession,
        entries = entries,
        currentLessonId = snapshot.currentLessonId,
        selectedLessonId = selectedLessonId(entries, snapshot.currentLessonId),
        showsBoard = snapshot.showsBoard,
        lastSpotlight = snapshot.lastSpotlight,
        quizVisible = snapshot.quizVisible,
        coachVisible = snapshot.coachVisible,
    )
}

internal fun selectedLessonId(
    entries: List<TutorialNavEntry>,
    currentLessonId: String?,
): String {
    val lessons = entries.mapNotNull { (it as? TutorialNavEntry.Lesson)?.lessonId }
    return when {
        currentLessonId != null && currentLessonId in lessons -> currentLessonId
        else -> lessons.firstOrNull().orEmpty()
    }
}

/**
 * Identity of the last view the shell presenter reconciled, so a later frame
 * can tell a model change (reveal) from an unchanged player scroll.
 */
data class ShellReconcileKey(
    val lessonId: String?,
    val entries: List<TutorialNavEntry>,
) {
    constructor(view: TutorialShellView) : this(view.currentLessonId, view.entries)
}

/**
 * Whether [motion] should reveal the selected TOC row, and whether that reveal
 * resets the offset. Pure: the scroll number itself is geometry and lives on
 * the widget.
 */
data class ShellReconcile(
    val reveal: Boolean,
    val resetScroll: Boolean,
)

/**
 * First hosted frame with an empty store reveals from zero (the default open
 * drawer already stands). A restored store is applied as-is. A later frame
 * reveals only when the lesson or the TOC entries changed, and never resets
 * the player's offset.
 */
fun shellReconcile(
    previous: ShellReconcileKey?,
    view: TutorialShellView,
    storedAbsent: Boolean,
): ShellReconcile {
    if (previous == null) {
        return ShellReconcile(reveal = storedAbsent, resetScroll = storedAbsent)
    }
    val changed = previous.lessonId != view.currentLessonId || previous.entries != view.entries
    return ShellReconcile(reveal = changed, resetScroll = false)
}
