package inego.zungjunggame.features.tutorial

import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.TableApply
import inego.zungjunggame.render.table.TableInteract
import inego.zungjunggame.tutorial.TutorialProgressState

/**
 * Live-table chrome the tutorial host publishes for this frame. The board
 * component applies it; the host never writes [inego.zungjunggame.render.table.PlayTableView].
 */
data class TutorialTableChrome(
    val interact: TableInteract = TableInteract.Play,
    val highlightLiveWall: Boolean = false,
    val overlayLeaderActive: Boolean = false,
    val revealedSeats: Set<Int> = emptySet(),
    val boardHighlight: BoardHighlight = BoardHighlight.None,
    val apply: TableApply = TableApply.None,
) {
    companion object {
        val Absent: TutorialTableChrome = TutorialTableChrome()
    }
}

/**
 * One frame's hosted-tutorial facts: campaign progress, the current lesson, and
 * the lesson chrome the shell's spotlight hint derives from.
 *
 * Assembled by [inego.zungjunggame.tutorial.TutorialHost.snapshot]; the app
 * puts it on the frame as part of its `AppModel`.
 */
data class TutorialSnapshot(
    val progress: TutorialProgressState,
    val currentLessonId: String?,
    val showsBoard: Boolean,
    val lastSpotlight: String?,
    val quizVisible: Boolean,
    val coachVisible: Boolean,
    val inTutorialSession: Boolean,
    /**
     * The coach / complete scene for this frame, derived from the lesson model.
     * Components read it instead of the overlay keeping the values.
     */
    val coach: TutorialCoachSnapshot = TutorialCoachSnapshot.Absent,
    /** The orientation-quiz scene for this frame, derived from the lesson model. */
    val quiz: TutorialQuizSnapshot = TutorialQuizSnapshot.Absent,
    /** Live-table chrome and consume-once apply for this frame. */
    val table: TutorialTableChrome = TutorialTableChrome.Absent,
    /**
     * True when the current lesson's live table is its subject — at least one
     * board-overlay coach. The table then keeps one pane for the whole lesson,
     * including the play beats where the coach is down, so it never resizes
     * when the overlay comes or goes.
     */
    val boardLesson: Boolean = false,
) {
    companion object {
        val Absent: TutorialSnapshot = TutorialSnapshot(
            progress = TutorialProgressState.EMPTY,
            currentLessonId = null,
            showsBoard = false,
            lastSpotlight = null,
            quizVisible = false,
            coachVisible = false,
            inTutorialSession = false,
        )
    }
}
