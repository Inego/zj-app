package inego.zungjunggame.features.board

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DiscardWaitTable
import inego.zungjunggame.engine.DiscardWaits
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.TableApply
import inego.zungjunggame.render.table.TableInteract
import inego.zungjunggame.render.table.TableRenderInputs
import inego.zungjunggame.runtime.AppModel
import inego.zungjunggame.session.online.toSeatObservation
import inego.zungjunggame.session.table.DiscardOrigin
import inego.zungjunggame.session.table.toPlayerState
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.ui.stack.SessionKind

/** The board's slice of the frame model: the session projection and its render inputs. */
data class BoardView(
    val session: SessionSnapshot,
    val renderInput: BoardRenderInput,
    val inputs: TableRenderInputs,
)

/** Projects the frame's [AppModel] into the board's view. Pure. */
fun boardView(model: AppModel): BoardView = BoardView(
    session = model.session,
    renderInput = model.boardRender,
    inputs = model.tableInputs,
)

/** Free-play facts the board's table inputs need, projected from the live session. */
data class FreePlayRenderFacts(
    val seatScores: IntArray,
)

/** Online facts the board's table inputs need, projected from the live host. */
data class OnlineRenderFacts(
    val seatScores: IntArray,
    val connected: Boolean = true,
)

/**
 * Host-published table chrome and consume-once apply folded into [TableRenderInputs].
 * Tutorial supplies the lecture lock, highlights, and wall-handoff; free-play and
 * online supply restore/reset.
 */
data class TableChrome(
    val interact: TableInteract = TableInteract.Play,
    val highlightLiveWall: Boolean = false,
    val overlayLeaderActive: Boolean = false,
    val revealedSeats: Set<Int> = emptySet(),
    val boardHighlight: BoardHighlight = BoardHighlight.None,
    val apply: TableApply = TableApply.None,
)

/**
 * The board's model-derived table inputs for one frame: session kind plus the
 * hosts' facts. Pure, so the arithmetic is exercised without GL.
 */
fun boardTableInputs(
    session: SessionSnapshot,
    origin: DiscardOrigin?,
    freePlay: FreePlayRenderFacts,
    online: OnlineRenderFacts?,
    chrome: TableChrome = TableChrome(),
): TableRenderInputs {
    val kind = session.kind
    val humanSeat = (session.table?.humanSeat ?: session.hand?.humanSeat ?: 0).coerceAtLeast(0)
    val interact = when (kind) {
        SessionKind.Tutorial -> chrome.interact
        SessionKind.Online if online?.connected == false -> TableInteract.ReadOnly
        else -> TableInteract.Play
    }
    val scores = when {
        !session.boardHosted -> TableRenderInputs(
            humanSeat = humanSeat,
            hintsEnabled = kind == SessionKind.FreePlay,
            discardOrigin = origin,
        )
        kind == SessionKind.Tutorial -> TableRenderInputs(
            humanSeat = humanSeat,
            hintsEnabled = false,
            discardOrigin = origin,
        )
        kind == SessionKind.FreePlay && session.hand?.state != null -> TableRenderInputs(
            humanSeat = humanSeat,
            seatScores = freePlay.seatScores,
            hintsEnabled = true,
            discardOrigin = origin,
        )
        kind == SessionKind.Online && online != null -> TableRenderInputs(
            humanSeat = humanSeat,
            seatScores = online.seatScores,
            hintsEnabled = false,
            discardOrigin = origin,
        )
        else -> TableRenderInputs(
            humanSeat = humanSeat,
            hintsEnabled = kind == SessionKind.FreePlay,
            discardOrigin = origin,
        )
    }
    val allowChicken = session.table?.allowChickenHands ?: session.hand?.state?.allowChickenHands ?: false
    return scores.copy(
        interact = interact,
        highlightLiveWall = chrome.highlightLiveWall,
        overlayLeaderActive = chrome.overlayLeaderActive,
        revealedSeats = chrome.revealedSeats,
        boardHighlight = chrome.boardHighlight,
        apply = chrome.apply,
        allowChickenHands = allowChicken,
        discardWaits = discardWaitsFor(session, humanSeat, allowChicken),
    )
}

/**
 * The ready-discard table for this frame: only on a human discard turn, only
 * while the board is hosted. Empty otherwise.
 *
 * The hand is the human seat's visible [PlayerState]; the unseen counts are the
 * session's own (the free-play / tutorial `unknownTileCountsByPlayer`, else the
 * online [inego.zungjunggame.engines.SeatObservation.remainingTiles]). The legal
 * discards are the `Discard` choices the table view offers.
 */
private fun discardWaitsFor(
    session: SessionSnapshot,
    humanSeat: Int,
    allowChickenHands: Boolean,
): DiscardWaitTable {
    if (!session.boardHosted) return DiscardWaitTable.Empty
    val tableView = boardTableView(session, emptySet()) ?: return DiscardWaitTable.Empty
    if (!tableView.isHumanDiscardTurn) return DiscardWaitTable.Empty
    val hand = tableView.seats.getOrNull(humanSeat)?.toPlayerState() ?: return DiscardWaitTable.Empty

    val choices = tableView.myChoices
    val discards = ArrayList<Int>(choices.size)
    for ((kind, tile) in choices) {
        if (kind != ChoiceKind.Discard) continue
        if (tile in 0 until NUM_TILE_TYPES) discards.add(tile)
    }
    if (discards.isEmpty()) return DiscardWaitTable.Empty

    val localUnseen = when (session.kind) {
        SessionKind.FreePlay, SessionKind.Tutorial ->
            session.hand?.state?.unknownTileCountsByPlayer?.getOrNull(humanSeat)
        else -> null
    }
    val unseen = localUnseen ?: tableView.toSeatObservation()?.remainingTiles
        ?: return DiscardWaitTable.Empty

    return DiscardWaits.assess(
        player = hand,
        discardTypes = discards.toIntArray(),
        unseen = unseen,
        playerIndex = humanSeat,
        eastSeat = tableView.eastSeat,
        allowChickenHands = allowChickenHands,
    )
}
