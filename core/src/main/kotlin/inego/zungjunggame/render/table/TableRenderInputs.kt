package inego.zungjunggame.render.table

import inego.zungjunggame.engine.DiscardWaitTable
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.session.table.DiscardOrigin

/**
 * Consume-once hand apply published by a host and consumed by [PlayTableView].
 *
 * [id] is unique per event across publishers (`fp:`, `tut:`, `on:`). Empty [id]
 * or [TableApplyKind.None] is a no-op; the widget applies when [id] changes.
 */
data class TableApply(
    val id: String = "",
    val kind: TableApplyKind = TableApplyKind.None,
    val wallHandoff: WallBuildHandoffSnapshot? = null,
    val humanSeat: Int = 0,
) {
    companion object {
        val None: TableApply = TableApply()
    }
}

enum class TableApplyKind {
    None,
    /**
     * A watched deal: free play's own deal, or the opening hand of an online
     * match this connection attached to before it was dealt. Tiles spawn from
     * the wall marker with stagger.
     */
    Reset,
    /**
     * Rematerialized layout: snap tiles to targets. Restoring a save and joining
     * an online table that was already running both are this — the client
     * received a position and shows it as it is, with no deal animation.
     */
    Restored,
    /** Tutorial wall-build → live table: seed diagram poses and run residual FX. */
    WallHandoff,
}

/**
 * One frame's model-derived table render inputs, passed to [PlayTableView.render]
 * and [PlayTableView.refreshHits] instead of living as mutable table fields.
 *
 * Chrome ([interact], highlights, revealed seats) is continuous. [apply] is a
 * consume-once command: hosts publish a new [TableApply.id], the widget applies
 * once.
 */
data class TableRenderInputs(
    val humanSeat: Int = 0,
    val seatScores: IntArray = IntArray(NUM_PLAYERS),
    val hintsEnabled: Boolean = true,
    val discardOrigin: DiscardOrigin? = null,
    val interact: TableInteract = TableInteract.Play,
    val highlightLiveWall: Boolean = false,
    val overlayLeaderActive: Boolean = false,
    val revealedSeats: Set<Int> = emptySet(),
    val boardHighlight: BoardHighlight = BoardHighlight.None,
    val apply: TableApply = TableApply.None,
    val allowChickenHands: Boolean = false,
    /** Ready-discard waits for this human discard turn; empty otherwise. */
    val discardWaits: DiscardWaitTable = DiscardWaitTable.Empty,
)
