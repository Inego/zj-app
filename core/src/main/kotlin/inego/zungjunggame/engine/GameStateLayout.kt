package inego.zungjunggame.engine

/**
 * Mid-hand board description for [GameState.fromLayout] / [GameState.hydrateFrom].
 *
 * Captures the same fields free-play [inego.zungjunggame.persist.SessionSnapshotSchema] restores.
 * Prefer this (or human-editable scenario data that maps to it) for tutorials — not binary
 * session blobs as the authoring format.
 *
 * Arrays are copied at construction so callers may reuse buffers.
 */
class PlayerLayout(
    concealed: IntArray,
    openChows: IntArray = IntArray(NUM_CHOW_TYPES),
    pungsKongs: IntArray = IntArray(NUM_TILE_TYPES),
    pungKongCalledFrom: IntArray = IntArray(NUM_TILE_TYPES) { -1 },
    val meldCount: Int = 0,
    meldKind: IntArray = IntArray(PlayerState.MAX_OPEN_MELDS),
    meldValue: IntArray = IntArray(PlayerState.MAX_OPEN_MELDS),
    meldClaimTile: IntArray = IntArray(PlayerState.MAX_OPEN_MELDS),
    meldFrom: IntArray = IntArray(PlayerState.MAX_OPEN_MELDS),
) {
    val concealed: IntArray = concealed.copyOf()
    val openChows: IntArray = openChows.copyOf()
    val pungsKongs: IntArray = pungsKongs.copyOf()
    val pungKongCalledFrom: IntArray = pungKongCalledFrom.copyOf()
    val meldKind: IntArray = meldKind.copyOf()
    val meldValue: IntArray = meldValue.copyOf()
    val meldClaimTile: IntArray = meldClaimTile.copyOf()
    val meldFrom: IntArray = meldFrom.copyOf()

    init {
        require(this.concealed.size == NUM_TILE_TYPES) {
            "concealed size ${this.concealed.size}, expected $NUM_TILE_TYPES"
        }
        require(this.openChows.size == NUM_CHOW_TYPES) {
            "openChows size ${this.openChows.size}, expected $NUM_CHOW_TYPES"
        }
        require(this.pungsKongs.size == NUM_TILE_TYPES) {
            "pungsKongs size ${this.pungsKongs.size}, expected $NUM_TILE_TYPES"
        }
        require(this.pungKongCalledFrom.size == NUM_TILE_TYPES) {
            "pungKongCalledFrom size ${this.pungKongCalledFrom.size}, expected $NUM_TILE_TYPES"
        }
        require(this.meldKind.size == PlayerState.MAX_OPEN_MELDS) {
            "meldKind size ${this.meldKind.size}"
        }
        require(this.meldValue.size == PlayerState.MAX_OPEN_MELDS) {
            "meldValue size ${this.meldValue.size}"
        }
        require(this.meldClaimTile.size == PlayerState.MAX_OPEN_MELDS) {
            "meldClaimTile size ${this.meldClaimTile.size}"
        }
        require(this.meldFrom.size == PlayerState.MAX_OPEN_MELDS) {
            "meldFrom size ${this.meldFrom.size}"
        }
        require(meldCount in 0..PlayerState.MAX_OPEN_MELDS) {
            "meldCount $meldCount"
        }
    }

    companion object {
        fun from(ps: PlayerState): PlayerLayout = PlayerLayout(
            concealed = ps.concealed,
            openChows = ps.openChows,
            pungsKongs = ps.pungsKongs,
            pungKongCalledFrom = ps.pungKongCalledFrom,
            meldCount = ps.meldCount,
            meldKind = ps.meldKind,
            meldValue = ps.meldValue,
            meldClaimTile = ps.meldClaimTile,
            meldFrom = ps.meldFrom,
        )
    }
}

/**
 * Full mid-hand [GameState] snapshot (wall remainder, hands, melds, rivers, decision phase).
 *
 * @see GameState.fromLayout
 * @see GameState.hydrateFrom
 */
class GameStateLayout(
    wall: IntArray,
    val liveWallSize: Int,
    players: Array<PlayerLayout>,
    unknownTileCountsByPlayer: Array<IntArray>,
    handRevisions: IntArray = IntArray(NUM_PLAYERS),
    discards: Array<out List<Int>> = Array(NUM_PLAYERS) { emptyList() },
    val lastDiscarder: Int = -1,
    val lastDiscarded: Int = -1,
    val lastDrawnTile: Int = -1,
    val lastDrawnPlayer: Int = -1,
    val eastSeat: Int = 0,
    val currentPlayer: Int = 0,
    val decisionType: DecisionType = DecisionType.ActOnHand,
    val handScore: Int = 0,
    val allowChickenHands: Boolean = false,
    pendingReactions: Array<Choice> = Array(NUM_PLAYERS) { Choice.pass() },
    hasPendingReaction: BooleanArray = BooleanArray(NUM_PLAYERS),
    val pendingReactionCount: Int = 0,
    declinedConcealedKong: BooleanArray = BooleanArray(NUM_TILE_TYPES),
    val lastDrawWasKongSupplement: Boolean = false,
    val kongRobWindow: Boolean = false,
    /** Seat that drew the seabed (see [GameState.seabedPlayer]); -1 while the wall runs. */
    val seabedPlayer: Int = -1,
    /** See [GameState.handEnded]. A win always ends the hand, hence the default. */
    val handEnded: Boolean = handScore > 0,
) {
    val wall: IntArray = wall.copyOf()
    val players: Array<PlayerLayout> = Array(NUM_PLAYERS) { players[it] }
    val unknownTileCountsByPlayer: Array<IntArray> =
        Array(NUM_PLAYERS) { unknownTileCountsByPlayer[it].copyOf() }
    val handRevisions: IntArray = handRevisions.copyOf()
    val discards: Array<List<Int>> = Array(NUM_PLAYERS) { discards[it].toList() }
    val pendingReactions: Array<Choice> = Array(NUM_PLAYERS) { pendingReactions[it] }
    val hasPendingReaction: BooleanArray = hasPendingReaction.copyOf()
    val declinedConcealedKong: BooleanArray = declinedConcealedKong.copyOf()

    init {
        require(this.wall.size == WALL_SIZE) {
            "wall size ${this.wall.size}, expected $WALL_SIZE"
        }
        require(players.size == NUM_PLAYERS) {
            "players size ${players.size}, expected $NUM_PLAYERS"
        }
        require(unknownTileCountsByPlayer.size == NUM_PLAYERS) {
            "unknownTileCountsByPlayer size ${unknownTileCountsByPlayer.size}"
        }
        for (p in 0 until NUM_PLAYERS) {
            require(unknownTileCountsByPlayer[p].size == NUM_TILE_TYPES) {
                "unknownTileCountsByPlayer[$p] size ${unknownTileCountsByPlayer[p].size}"
            }
        }
        require(this.handRevisions.size == NUM_PLAYERS) {
            "handRevisions size ${this.handRevisions.size}"
        }
        require(discards.size == NUM_PLAYERS) { "discards size ${discards.size}" }
        require(pendingReactions.size == NUM_PLAYERS) {
            "pendingReactions size ${pendingReactions.size}"
        }
        require(this.hasPendingReaction.size == NUM_PLAYERS) {
            "hasPendingReaction size ${this.hasPendingReaction.size}"
        }
        require(this.declinedConcealedKong.size == NUM_TILE_TYPES) {
            "declinedConcealedKong size ${this.declinedConcealedKong.size}"
        }
    }

    companion object {
        /** Snapshot a live [GameState] (arrays are copied). */
        fun from(state: GameState): GameStateLayout = GameStateLayout(
            wall = state.wall,
            liveWallSize = state.liveWallSize,
            players = Array(NUM_PLAYERS) { PlayerLayout.from(state.players[it]) },
            unknownTileCountsByPlayer = state.unknownTileCountsByPlayer,
            handRevisions = state.handRevisions,
            discards = state.discards,
            lastDiscarder = state.lastDiscarder,
            lastDiscarded = state.lastDiscarded,
            lastDrawnTile = state.lastDrawnTile,
            lastDrawnPlayer = state.lastDrawnPlayer,
            eastSeat = state.eastSeat,
            currentPlayer = state.currentPlayer,
            decisionType = state.decisionType,
            handScore = state.handScore,
            allowChickenHands = state.allowChickenHands,
            pendingReactions = state.pendingReactions,
            hasPendingReaction = state.hasPendingReaction,
            pendingReactionCount = state.pendingReactionCount,
            declinedConcealedKong = state.declinedConcealedKong,
            lastDrawWasKongSupplement = state.lastDrawWasKongSupplement,
            kongRobWindow = state.kongRobWindow,
            seabedPlayer = state.seabedPlayer,
            handEnded = state.handEnded,
        )
    }
}
