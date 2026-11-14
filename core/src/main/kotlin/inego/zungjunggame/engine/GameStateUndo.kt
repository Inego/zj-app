package inego.zungjunggame.engine

enum class GameStateArrayKind {
    Concealed,
    OpenChows,
    PungsKongs,
    UnknownTileCounts,
    /** Per-tile call source seat for open pung/kong (-1 = none / concealed kong). */
    PungKongCalledFrom,
    /** [PlayerState.meldCount] (index unused). */
    MeldCount,
    /** Slot in [PlayerState.meldKind] / MeldValue / MeldClaimTile / MeldFrom (index = slot). */
    MeldKind,
    MeldValue,
    MeldClaimTile,
    MeldFrom,
}

class GameStateCellChange {
    var arrayKind: GameStateArrayKind = GameStateArrayKind.Concealed
    var player: Int = 0
    var index: Int = 0
    var oldValue: Int = 0
}

class HuScoreCacheEntry {
    var valid: Boolean = false
    var handRevision: Int = 0
    var tile: Int = -1
    var liveWallSize: Int = -1
    var lastDiscarder: Int = -1
    var lastDrawnPlayer: Int = -1
    var seabedPlayer: Int = -1
    var decisionType: DecisionType = DecisionType.ActOnHand
    var lastDrawWasKongSupplement: Boolean = false
    var kongRobWindow: Boolean = false
    var score: Int = -1
}

/** Snapshot sufficient to reverse one [GameState.applyChoiceWithUndo]. */
class GameStateUndo {
    var liveWallSize: Int = 0
    var lastDiscarder: Int = -1
    var lastDiscarded: Int = -1
    var lastDrawnTile: Int = -1
    var lastDrawnPlayer: Int = -1
    var seabedPlayer: Int = -1
    var handEnded: Boolean = false
    var currentPlayer: Int = 0
    var decisionType: DecisionType = DecisionType.ActOnHand
    var handScore: Int = 0
    var winnerSeat: Int = -1
    var winSource: Int = -1
    val pendingReactions = Array(NUM_PLAYERS) { Choice.pass() }
    val hasPendingReaction = BooleanArray(NUM_PLAYERS)
    var pendingReactionCount: Int = 0
    val handRevisions = IntArray(NUM_PLAYERS)
    /** Snapshot of [GameState.declinedConcealedKong] before the choice. */
    val declinedConcealedKong = BooleanArray(NUM_TILE_TYPES)
    var lastDrawWasKongSupplement: Boolean = false
    var kongRobWindow: Boolean = false
    val cellChanges = Array(MAX_GAME_STATE_CELL_CHANGES) { GameStateCellChange() }
    var cellChangeCount: Int = 0

    /**
     * If this transition finalized a discard into a player's river (everyone passed),
     * [riverAppendedPlayer] is that seat and [riverAppendedTile] is the tile.
     * Otherwise both stay -1.
     */
    var riverAppendedPlayer: Int = -1
    var riverAppendedTile: Int = -1
}
