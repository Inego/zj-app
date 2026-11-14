package inego.zungjunggame.runtime

import inego.zungjunggame.features.board.BoardRenderInput
import inego.zungjunggame.features.score.ScoreBreakdownContent
import inego.zungjunggame.features.tutorial.TutorialSnapshot
import inego.zungjunggame.render.table.TableRenderInputs
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.stack.SessionKind

/**
 * The composition root's per-frame model: one immutable snapshot of the session,
 * the hosted tutorial, the board's render inputs, and the hand-end card content,
 * assembled after input and host ticks and put on every [Frame] of that layout.
 *
 * It lives in `runtime/` because features already depend on the runtime, while
 * `ui/` must stay free of `session` / `features` / `render` ([Frame.model] is
 * typed [Any] for the same reason). A presenter reads it through [appModel]; a
 * component that derives nothing from the model ignores it.
 */
data class AppModel(
    val session: SessionSnapshot,
    val tutorial: TutorialSnapshot,
    val tableInputs: TableRenderInputs,
    val boardRender: BoardRenderInput,
    val scoreBreakdown: ScoreBreakdownContent? = null,
) {
    companion object {
        val Absent: AppModel = AppModel(
            session = SessionSnapshot(SessionKind.Menu, boardHosted = false, table = null, hand = null),
            tutorial = TutorialSnapshot.Absent,
            tableInputs = TableRenderInputs(),
            boardRender = BoardRenderInput(),
        )
    }
}

/**
 * The frame's [AppModel]. Every runtime-built frame carries one; a headless
 * fixture that a component never reads the model from may leave `Frame.model`
 * at its [Unit] default, but a presenter that calls this must be handed an
 * `AppModel` — a missing one is a wiring bug, not an empty model.
 */
fun Frame.appModel(): AppModel =
    model as? AppModel
        ?: error("Frame.model is ${model::class.simpleName}; the host must assemble an AppModel per frame")
