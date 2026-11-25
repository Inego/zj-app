package inego.zungjunggame.engine

import kotlin.math.max
import kotlin.random.Random

/**
 * Full game state (wall + all four players + discard info).
 *
 * Ported from zj-self-play `engine/game_state.*` and `game_state_transitions.cpp`.
 */
class GameState {
    // --- wall ----------------------------------------------------------
    val wall = IntArray(WALL_SIZE)
    var liveWallSize: Int = 0

    // --- per-player ----------------------------------------------------
    val players = Array(NUM_PLAYERS) { PlayerState() }
    val unknownTileCountsByPlayer = Array(NUM_PLAYERS) { IntArray(NUM_TILE_TYPES) }
    val handRevisions = IntArray(NUM_PLAYERS)
    private val selfDrawHuCache = Array(NUM_PLAYERS) { HuScoreCacheEntry() }
    private val discardReactionHuCache = Array(NUM_PLAYERS) { HuScoreCacheEntry() }

    // --- discard / decision info ---------------------------------------
    var lastDiscarder: Int = -1
    var lastDiscarded: Int = -1
    /**
     * Most recent wall draw: tile type and the seat that received it.
     * Used by UI to park the new tile on the right of the hand until a discard.
     * Cleared on discard; not set for claim-only ActOnHand (pung/chow).
     */
    var lastDrawnTile: Int = -1
    var lastDrawnPlayer: Int = -1
    /**
     * Seat that drew the **seabed** — the last live wall tile, i.e. the draw that
     * took [liveWallSize] to 0. `-1` while the wall still holds tiles.
     *
     * Their discard is the **riverbed**: the only discard that can score
     * [PatternIds.FINAL_DISCARD] (9.1.2). Recorded here because [lastDrawnPlayer] is
     * cleared by that very discard, and because a later discard — after the riverbed
     * was claimed for pung/chow — must **not** score 9.1.2 a second time.
     */
    var seabedPlayer: Int = -1
        internal set
    /**
     * True once the hand is over: a win, or the wall ran out and nobody claimed the
     * riverbed.
     *
     * An empty wall is **not** on its own an end of hand — [isTerminal] combines this
     * with [handScore]. After the seabed draw the seabed player still owes a discard
     * everyone may win on (9.1.2), and after the wall is empty the hand can still
     * continue through claims until a discard finally goes unclaimed.
     */
    var handEnded: Boolean = false
        internal set
    /**
     * Finalized discard rivers (tiles nobody claimed). Order is chronological
     * per player. The pending [lastDiscarded] is not included until reactions resolve.
     */
    val discards = Array(NUM_PLAYERS) { ArrayList<Int>(32) }
    /**
     * Absolute seat (0..3) that is East / dealer this hand. Seat winds rotate
     * each hand: South of the previous hand becomes East, etc.
     */
    var eastSeat: Int = 0
    var currentPlayer: Int = 0
    var decisionType: DecisionType = DecisionType.ActOnHand
    var handScore: Int = 0
    var winnerSeat: Int = -1
        internal set
    var winSource: Int = -1
        internal set
    /**
     * When false, pure chicken hands (no pattern points) cannot declare Hu.
     * Set from [GameConfig] at deal time; fixed for the life of the hand.
     */
    var allowChickenHands: Boolean = false
    val pendingReactions = Array(NUM_PLAYERS) { Choice.pass() }
    val hasPendingReaction = BooleanArray(NUM_PLAYERS)
    var pendingReactionCount: Int = 0
    /**
     * Per tile type: player declined [ChoiceKind.Keep] for a concealed kong of that
     * type during the current ActOnHand stint. Cleared on discard, deal, and when a
     * new ActOnHand stint begins after a claim or wall draw (see [clearDeclinedConcealedKongs]).
     * Enables sequential declare/keep for each four-of-a-kind before discards.
     */
    val declinedConcealedKong = BooleanArray(NUM_TILE_TYPES)
    /**
     * True when [lastDrawnTile] was the supplement after a kong (concealed,
     * promoted, or claimed). Win on Kong (9.2). Cleared on discard and deal.
     */
    var lastDrawWasKongSupplement: Boolean = false
    /**
     * True while collecting Hu/Pass on a [ChoiceKind.PromoteKong] (small exposed
     * kong). The promoter still has an open pung; the 4th tile is [lastDiscarded]
     * and is not in a river. Rob is win-on-discard (9.3).
     */
    var kongRobWindow: Boolean = false

    /** Seat wind tile type for [player] this hand (East/South/West/North tile index). */
    fun seatWindTile(player: Int): Int =
        SeatWinds.tileForPlayer(player, eastSeat)

    fun copy(): GameState {
        val other = GameState()
        other.copyFrom(this)
        return other
    }

    /**
     * Overwrite this instance from [source] (no new arrays / player objects).
     * Used by Monte Carlo playouts that reuse a working [GameState].
     */
    fun copyFrom(source: GameState) {
        System.arraycopy(source.wall, 0, wall, 0, WALL_SIZE)
        liveWallSize = source.liveWallSize
        for (p in 0 until NUM_PLAYERS) {
            players[p].copyFrom(source.players[p])
            System.arraycopy(
                source.unknownTileCountsByPlayer[p],
                0,
                unknownTileCountsByPlayer[p],
                0,
                NUM_TILE_TYPES,
            )
            handRevisions[p] = source.handRevisions[p]
            discards[p].clear()
            discards[p].addAll(source.discards[p])
            selfDrawHuCache[p].valid = false
            discardReactionHuCache[p].valid = false
        }
        lastDiscarder = source.lastDiscarder
        lastDiscarded = source.lastDiscarded
        lastDrawnTile = source.lastDrawnTile
        lastDrawnPlayer = source.lastDrawnPlayer
        seabedPlayer = source.seabedPlayer
        handEnded = source.handEnded
        eastSeat = source.eastSeat
        currentPlayer = source.currentPlayer
        decisionType = source.decisionType
        handScore = source.handScore
        winnerSeat = source.winnerSeat
        winSource = source.winSource
        allowChickenHands = source.allowChickenHands
        for (p in 0 until NUM_PLAYERS) {
            pendingReactions[p] = source.pendingReactions[p]
            hasPendingReaction[p] = source.hasPendingReaction[p]
        }
        pendingReactionCount = source.pendingReactionCount
        System.arraycopy(source.declinedConcealedKong, 0, declinedConcealedKong, 0, NUM_TILE_TYPES)
        lastDrawWasKongSupplement = source.lastDrawWasKongSupplement
        kongRobWindow = source.kongRobWindow
    }

    fun clearDeclinedConcealedKongs() {
        declinedConcealedKong.fill(false)
    }

    /**
     * Overwrite this instance from [layout] (shared hydrate used by [fromLayout] and
     * free-play [inego.zungjunggame.persist.SessionSnapshotSchema] restore).
     *
     * Rebuilds active-concealed caches; Hu score caches stay invalid on a fresh fill.
     * Does **not** re-validate the multiset — call [fromLayout] (or [requireValidLayout])
     * when loading author/tutorial layouts. Codec restore skips full multiset checks so
     * field-level round-trips stay tolerant of test fixtures.
     */
    fun hydrateFrom(layout: GameStateLayout) {
        System.arraycopy(layout.wall, 0, wall, 0, WALL_SIZE)
        liveWallSize = layout.liveWallSize
        for (p in 0 until NUM_PLAYERS) {
            val pl = layout.players[p]
            val ps = players[p]
            System.arraycopy(pl.concealed, 0, ps.concealed, 0, NUM_TILE_TYPES)
            System.arraycopy(pl.openChows, 0, ps.openChows, 0, NUM_CHOW_TYPES)
            System.arraycopy(pl.pungsKongs, 0, ps.pungsKongs, 0, NUM_TILE_TYPES)
            System.arraycopy(pl.pungKongCalledFrom, 0, ps.pungKongCalledFrom, 0, NUM_TILE_TYPES)
            ps.meldCount = pl.meldCount
            System.arraycopy(pl.meldKind, 0, ps.meldKind, 0, PlayerState.MAX_OPEN_MELDS)
            System.arraycopy(pl.meldValue, 0, ps.meldValue, 0, PlayerState.MAX_OPEN_MELDS)
            System.arraycopy(pl.meldClaimTile, 0, ps.meldClaimTile, 0, PlayerState.MAX_OPEN_MELDS)
            System.arraycopy(pl.meldFrom, 0, ps.meldFrom, 0, PlayerState.MAX_OPEN_MELDS)
            ps.activeConcealedTileCount = -1
            ps.ensureActiveConcealedTiles()

            System.arraycopy(
                layout.unknownTileCountsByPlayer[p],
                0,
                unknownTileCountsByPlayer[p],
                0,
                NUM_TILE_TYPES,
            )
            handRevisions[p] = layout.handRevisions[p]
            discards[p].clear()
            discards[p].addAll(layout.discards[p])
            selfDrawHuCache[p].valid = false
            discardReactionHuCache[p].valid = false
        }
        lastDiscarder = layout.lastDiscarder
        lastDiscarded = layout.lastDiscarded
        lastDrawnTile = layout.lastDrawnTile
        lastDrawnPlayer = layout.lastDrawnPlayer
        seabedPlayer = layout.seabedPlayer
        handEnded = layout.handEnded
        eastSeat = layout.eastSeat
        currentPlayer = layout.currentPlayer
        decisionType = layout.decisionType
        handScore = layout.handScore
        winnerSeat = if (layout.handScore > 0) layout.currentPlayer else -1
        winSource = if (layout.handScore > 0 && layout.decisionType == DecisionType.ReactToDiscard) layout.lastDiscarder else -1
        allowChickenHands = layout.allowChickenHands
        for (p in 0 until NUM_PLAYERS) {
            pendingReactions[p] = layout.pendingReactions[p]
            hasPendingReaction[p] = layout.hasPendingReaction[p]
        }
        pendingReactionCount = layout.pendingReactionCount
        System.arraycopy(
            layout.declinedConcealedKong,
            0,
            declinedConcealedKong,
            0,
            NUM_TILE_TYPES,
        )
        lastDrawWasKongSupplement = layout.lastDrawWasKongSupplement
        kongRobWindow = layout.kongRobWindow
    }

    // --- queries -------------------------------------------------------

    /**
     * Lowest tile type with four concealed copies that is still undeclared and not
     * [declinedConcealedKong], or -1 if none (or wall empty — no supplement draw).
     */
    fun nextPendingConcealedKongTile(actingSeat: Int = currentPlayer): Int {
        if (decisionType != DecisionType.ActOnHand) return -1
        if (liveWallSize <= 0) return -1
        val ps = players[actingSeat]
        for (tile in 0 until NUM_TILE_TYPES) {
            if (ps.concealed[tile] == 4 && !declinedConcealedKong[tile]) return tile
        }
        return -1
    }

    /** Enumerate every legal action for [actingSeat] on an ActOnHand turn. */
    fun enumerateHandActions(actingSeat: Int, out: ChoiceList = ChoiceList()): ChoiceList {
        out.clear()
        if (canDeclareSelfDrawHu(actingSeat)) {
            val huScore = selfDrawHuScoreOrNegativeForPlayer(actingSeat)
            if (huScore >= 0) {
                out.pushBack(Choice.hu(huScore))
            }
        }

        val pendingKong = nextPendingConcealedKongTile(actingSeat)
        if (pendingKong >= 0) {
            out.pushBack(Choice.concealedKong(pendingKong))
            out.pushBack(Choice.keep(pendingKong))
            return out
        }

        val ps = players[actingSeat]
        ps.ensureActiveConcealedTiles()
        for (index in 0 until ps.activeConcealedTileCount) {
            val tile = ps.activeConcealedTiles[index]
            val concealedCount = ps.concealed[tile]

            out.pushBack(Choice.discard(tile))

            if (concealedCount == 1 &&
                ps.pungsKongs[tile] == PlayerState.PK_OPEN_PUNG &&
                liveWallSize > 0
            ) {
                out.pushBack(Choice.promoteKong(tile))
            }
        }
        return out
    }

    /** Enumerate every legal reaction for [seat] given a frozen [reactionContext]. */
    fun enumerateReactions(
        reactionContext: ReactionContext,
        seat: Int,
        out: ChoiceList = ChoiceList(),
    ): ChoiceList {
        out.clear()
        out.pushBack(Choice.pass())

        val discardedTile = reactionContext.tile
        val ps = players[seat]

        if (discardedTile in 0 until NUM_TILE_TYPES && seat != reactionContext.sourceSeat) {
            val huScore = discardReactionHuScoreOrNegativeForPlayer(seat, discardedTile)
            if (huScore > 0) {
                out.pushBack(Choice.hu(huScore))
            }

            // Small-exposed-kong rob: Hu or Pass only (no pung/chow/kong).
            if (reactionContext.kongRobWindow) return out

            if (ps.concealed[discardedTile] >= 2) {
                out.pushBack(Choice.pung(discardedTile))
            }
            if (ps.concealed[discardedTile] >= 3 && reactionContext.liveWallSize > 0) {
                out.pushBack(Choice.kong(discardedTile))
            }

            val nextAfterDiscarder = (reactionContext.sourceSeat + 1) % NUM_PLAYERS
            if (seat == nextAfterDiscarder && discardedTile < 27) {
                val rank = discardedTile % 9

                if (rank >= 2) {
                    val first = discardedTile - 2
                    if (ps.concealed[first] > 0 && ps.concealed[first + 1] > 0) {
                        out.pushBack(Choice.chow(first))
                    }
                }
                if (rank in 1..7) {
                    val first = discardedTile - 1
                    if (ps.concealed[first] > 0 && ps.concealed[first + 2] > 0) {
                        out.pushBack(Choice.chow(first))
                    }
                }
                if (rank <= 6) {
                    if (ps.concealed[discardedTile + 1] > 0 && ps.concealed[discardedTile + 2] > 0) {
                        out.pushBack(Choice.chow(discardedTile))
                    }
                }
            }
        }

        return out
    }

    /** Enumerate every legal choice for the current player (fresh list). */
    fun enumerateChoices(): ChoiceList = enumerateChoicesInto(ChoiceList())

    /**
     * Fill [out] with every legal choice for the current player (clears first).
     * Prefer this on the MC playout hot path to avoid per-decision allocations.
     */
    fun enumerateChoicesInto(out: ChoiceList): ChoiceList {
        return if (decisionType == DecisionType.ActOnHand) {
            enumerateHandActions(currentPlayer, out)
        } else {
            val context = ReactionContext.fromState(this) ?: error("Not in ReactToDiscard")
            enumerateReactions(context, currentPlayer, out)
        }
    }

    /** True when the current state is a discard reaction with only PASS legal. */
    fun isTrivialPassOnlyReaction(choices: ChoiceList): Boolean =
        decisionType == DecisionType.ReactToDiscard &&
            choices.size == 1 &&
            choices[0].kind == ChoiceKind.Pass

    fun applyChoice(choice: Choice) {
        applyChoiceImpl(choice, undo = null)
    }

    fun applyChoiceWithUndo(choice: Choice): GameStateUndo {
        val undo = GameStateUndo()
        undo.liveWallSize = liveWallSize
        undo.lastDiscarder = lastDiscarder
        undo.lastDiscarded = lastDiscarded
        undo.lastDrawnTile = lastDrawnTile
        undo.lastDrawnPlayer = lastDrawnPlayer
        undo.seabedPlayer = seabedPlayer
        undo.handEnded = handEnded
        undo.currentPlayer = currentPlayer
        undo.decisionType = decisionType
        undo.handScore = handScore
        undo.winnerSeat = winnerSeat
        undo.winSource = winSource
        for (p in 0 until NUM_PLAYERS) {
            undo.pendingReactions[p] = pendingReactions[p]
            undo.hasPendingReaction[p] = hasPendingReaction[p]
            undo.handRevisions[p] = handRevisions[p]
        }
        undo.pendingReactionCount = pendingReactionCount
        System.arraycopy(declinedConcealedKong, 0, undo.declinedConcealedKong, 0, NUM_TILE_TYPES)
        undo.lastDrawWasKongSupplement = lastDrawWasKongSupplement
        undo.kongRobWindow = kongRobWindow
        undo.riverAppendedPlayer = -1
        undo.riverAppendedTile = -1

        applyChoiceImpl(choice, undo)
        return undo
    }

    fun undoChoice(undo: GameStateUndo) {
        if (undo.riverAppendedPlayer in 0 until NUM_PLAYERS) {
            val river = discards[undo.riverAppendedPlayer]
            check(river.isNotEmpty()) { "undo river append but river empty" }
            check(river.last() == undo.riverAppendedTile) {
                "undo river tile mismatch: ${river.last()} vs ${undo.riverAppendedTile}"
            }
            river.removeAt(river.lastIndex)
        }

        for (i in undo.cellChangeCount - 1 downTo 0) {
            val change = undo.cellChanges[i]
            when (change.arrayKind) {
                GameStateArrayKind.Concealed -> {
                    players[change.player].onConcealedCountChanged(
                        change.index,
                        players[change.player].concealed[change.index],
                        change.oldValue,
                    )
                    players[change.player].concealed[change.index] = change.oldValue
                }
                GameStateArrayKind.OpenChows -> {
                    players[change.player].openChows[change.index] = change.oldValue
                }
                GameStateArrayKind.PungsKongs -> {
                    players[change.player].pungsKongs[change.index] = change.oldValue
                }
                GameStateArrayKind.UnknownTileCounts -> {
                    unknownTileCountsByPlayer[change.player][change.index] = change.oldValue
                }
                GameStateArrayKind.PungKongCalledFrom -> {
                    players[change.player].pungKongCalledFrom[change.index] = change.oldValue
                }
                GameStateArrayKind.MeldCount -> {
                    players[change.player].meldCount = change.oldValue
                }
                GameStateArrayKind.MeldKind -> {
                    players[change.player].meldKind[change.index] = change.oldValue
                }
                GameStateArrayKind.MeldValue -> {
                    players[change.player].meldValue[change.index] = change.oldValue
                }
                GameStateArrayKind.MeldClaimTile -> {
                    players[change.player].meldClaimTile[change.index] = change.oldValue
                }
                GameStateArrayKind.MeldFrom -> {
                    players[change.player].meldFrom[change.index] = change.oldValue
                }
            }
        }

        liveWallSize = undo.liveWallSize
        lastDiscarder = undo.lastDiscarder
        lastDiscarded = undo.lastDiscarded
        lastDrawnTile = undo.lastDrawnTile
        lastDrawnPlayer = undo.lastDrawnPlayer
        seabedPlayer = undo.seabedPlayer
        handEnded = undo.handEnded
        currentPlayer = undo.currentPlayer
        decisionType = undo.decisionType
        handScore = undo.handScore
        winnerSeat = undo.winnerSeat
        winSource = undo.winSource
        for (p in 0 until NUM_PLAYERS) {
            pendingReactions[p] = undo.pendingReactions[p]
            hasPendingReaction[p] = undo.hasPendingReaction[p]
            handRevisions[p] = undo.handRevisions[p]
            selfDrawHuCache[p].valid = false
            discardReactionHuCache[p].valid = false
        }
        pendingReactionCount = undo.pendingReactionCount
        System.arraycopy(undo.declinedConcealedKong, 0, declinedConcealedKong, 0, NUM_TILE_TYPES)
        lastDrawWasKongSupplement = undo.lastDrawWasKongSupplement
        kongRobWindow = undo.kongRobWindow
    }


    /**
     * True when ActOnHand Hu is legal: dealer opening hand (no discards yet), or the
     * current player just drew from the wall (including kong replacement tiles).
     * False after chow/pung, where the only legal continuation is discard (or kong).
     */
    fun canDeclareSelfDrawHu(actingSeat: Int = currentPlayer): Boolean =
        lastDiscarder < 0 || lastDrawnPlayer == actingSeat

    /** Returns -1 if the current player cannot declare Hu on the current discard. */
    fun discardReactionHuScoreOrNegative(): Int {
        check(currentPlayer in 0 until NUM_PLAYERS)
        return discardReactionHuScoreOrNegativeForPlayer(currentPlayer, lastDiscarded)
    }

    fun isTerminal(): Boolean = handScore > 0 || handEnded

    /**
     * Zung Jung payment deltas for each seat (only meaningful at terminal).
     * See [ZjSettlement]: winner nets `3 × handScore`, with the >25 discarder
     * liability exception on win-on-discard.
     */
    fun computeScoreDeltas(): IntArray {
        if (handScore <= 0) return IntArray(NUM_PLAYERS)
        val winner = if (winnerSeat in 0 until NUM_PLAYERS) winnerSeat else currentPlayer
        val discarder =
            if (winSource in 0 until NUM_PLAYERS) {
                winSource
            } else if (decisionType == DecisionType.ReactToDiscard) {
                lastDiscarder
            } else {
                -1
            }
        return ZjSettlement.computeDeltas(
            handValue = handScore,
            winnerSeat = winner,
            discarderSeat = discarder,
        )
    }

    /** Zero-allocation Zung Jung payment delta for a single [seat] (only meaningful at terminal). */
    fun computeScoreDeltaForSeat(seat: Int): Int {
        if (handScore <= 0) return 0
        val winner = if (winnerSeat in 0 until NUM_PLAYERS) winnerSeat else currentPlayer
        val discarder =
            if (winSource in 0 until NUM_PLAYERS) {
                winSource
            } else if (decisionType == DecisionType.ReactToDiscard) {
                lastDiscarder
            } else {
                -1
            }
        return ZjSettlement.computeDeltaForSeat(
            handValue = handScore,
            winnerSeat = winner,
            discarderSeat = discarder,
            seat = seat,
        )
    }

    // --- private helpers -----------------------------------------------

    private fun clearPendingReactions() {
        for (p in 0 until NUM_PLAYERS) {
            pendingReactions[p] = Choice.pass()
            hasPendingReaction[p] = false
        }
        pendingReactionCount = 0
    }

    private fun recordCellChange(
        undo: GameStateUndo?,
        arrayKind: GameStateArrayKind,
        player: Int,
        index: Int,
        oldValue: Int,
    ) {
        if (undo == null) return

        for (i in 0 until undo.cellChangeCount) {
            val change = undo.cellChanges[i]
            if (change.arrayKind == arrayKind &&
                change.player == player &&
                change.index == index
            ) {
                return
            }
        }

        check(undo.cellChangeCount < MAX_GAME_STATE_CELL_CHANGES)
        val slot = undo.cellChanges[undo.cellChangeCount++]
        slot.arrayKind = arrayKind
        slot.player = player
        slot.index = index
        slot.oldValue = oldValue
    }

    /**
     * Append one exposed meld to [player]'s exposure log, recording for undo the
     * cells it overwrites (the new slot and the rules index cell the set or chow
     * lands in). The append itself belongs to [PlayerState.recordMeldExposure], the
     * log's only writer, which also updates `openChows`/`pungsKongs`.
     */
    private fun appendMeldExposure(
        undo: GameStateUndo?,
        player: Int,
        kind: Int,
        value: Int,
        claimTile: Int = -1,
        from: Int = -1,
        setState: Int = PlayerState.PK_NONE,
    ) {
        val ps = players[player]
        check(ps.meldCount < PlayerState.MAX_OPEN_MELDS) { "meld log full for player $player" }
        val slot = ps.meldCount
        recordCellChange(undo, GameStateArrayKind.MeldCount, player, 0, slot)
        recordCellChange(undo, GameStateArrayKind.MeldKind, player, slot, ps.meldKind[slot])
        recordCellChange(undo, GameStateArrayKind.MeldValue, player, slot, ps.meldValue[slot])
        recordCellChange(undo, GameStateArrayKind.MeldClaimTile, player, slot, ps.meldClaimTile[slot])
        recordCellChange(undo, GameStateArrayKind.MeldFrom, player, slot, ps.meldFrom[slot])
        if (kind == PlayerState.MELD_CHOW) {
            recordCellChange(undo, GameStateArrayKind.OpenChows, player, value, ps.openChows[value])
        } else {
            recordCellChange(undo, GameStateArrayKind.PungsKongs, player, value, ps.pungsKongs[value])
        }
        ps.recordMeldExposure(kind, value, claimTile, from, setState)
    }

    private fun markPlayerHandChanged(player: Int) {
        check(player in 0 until NUM_PLAYERS)
        ++handRevisions[player]
        selfDrawHuCache[player].valid = false
        discardReactionHuCache[player].valid = false
    }

    internal fun selfDrawHuScoreOrNegativeForPlayer(player: Int): Int {
        check(player in 0 until NUM_PLAYERS)

        if (!ENABLE_HU_SCORE_CACHE) {
            val score = Scoring.computeHandScoreDetails(this, player, includeClaimedDiscard = false).totalScore
            return if (score > 0) score else -1
        }

        val cache = selfDrawHuCache[player]
        if (cache.valid &&
            cache.handRevision == handRevisions[player] &&
            cache.liveWallSize == liveWallSize &&
            cache.lastDiscarder == lastDiscarder &&
            cache.lastDrawnPlayer == lastDrawnPlayer &&
            cache.seabedPlayer == seabedPlayer &&
            cache.decisionType == decisionType &&
            cache.lastDrawWasKongSupplement == lastDrawWasKongSupplement &&
            cache.kongRobWindow == kongRobWindow
        ) {
            return cache.score
        }

        val score = Scoring.computeHandScoreDetails(this, player, includeClaimedDiscard = false).totalScore
        cache.valid = true
        cache.handRevision = handRevisions[player]
        cache.tile = -1
        cache.liveWallSize = liveWallSize
        cache.lastDiscarder = lastDiscarder
        cache.lastDrawnPlayer = lastDrawnPlayer
        cache.seabedPlayer = seabedPlayer
        cache.decisionType = decisionType
        cache.lastDrawWasKongSupplement = lastDrawWasKongSupplement
        cache.kongRobWindow = kongRobWindow
        cache.score = if (score > 0) score else -1
        return cache.score
    }

    internal fun discardReactionHuScoreOrNegativeForPlayer(player: Int, discardedTile: Int): Int {
        check(player in 0 until NUM_PLAYERS)
        if (discardedTile !in 0 until NUM_TILE_TYPES) return -1

        if (!ENABLE_HU_SCORE_CACHE) {
            val score = Scoring.computeHandScoreDetails(this, player, includeClaimedDiscard = true).totalScore
            return if (score > 0) score else -1
        }

        val cache = discardReactionHuCache[player]
        if (cache.valid &&
            cache.handRevision == handRevisions[player] &&
            cache.tile == discardedTile &&
            cache.liveWallSize == liveWallSize &&
            cache.lastDiscarder == lastDiscarder &&
            cache.seabedPlayer == seabedPlayer &&
            cache.decisionType == decisionType &&
            cache.lastDrawWasKongSupplement == lastDrawWasKongSupplement &&
            cache.kongRobWindow == kongRobWindow
        ) {
            return cache.score
        }

        val score = Scoring.computeHandScoreDetails(this, player, includeClaimedDiscard = true).totalScore
        cache.valid = true
        cache.handRevision = handRevisions[player]
        cache.tile = discardedTile
        cache.liveWallSize = liveWallSize
        cache.lastDiscarder = lastDiscarder
        cache.seabedPlayer = seabedPlayer
        cache.decisionType = decisionType
        cache.lastDrawWasKongSupplement = lastDrawWasKongSupplement
        cache.kongRobWindow = kongRobWindow
        cache.score = if (score > 0) score else -1
        return cache.score
    }

    private fun setConcealedTileCount(player: Int, tile: Int, newCount: Int) {
        check(player in 0 until NUM_PLAYERS)
        check(tile in 0 until NUM_TILE_TYPES)
        check(newCount >= 0)

        val oldCount = players[player].concealed[tile]
        if (oldCount == newCount) return

        players[player].onConcealedCountChanged(tile, oldCount, newCount)
        players[player].concealed[tile] = newCount
    }

    private fun initializeUnknownTileCounts() {
        for (unknown in unknownTileCountsByPlayer) {
            unknown.fill(4)
        }
    }

    private fun addConcealedTile(player: Int, tile: Int, undo: GameStateUndo?) {
        check(player in 0 until NUM_PLAYERS)
        check(tile in 0 until NUM_TILE_TYPES)

        recordCellChange(
            undo,
            GameStateArrayKind.Concealed,
            player,
            tile,
            players[player].concealed[tile],
        )
        setConcealedTileCount(player, tile, players[player].concealed[tile] + 1)
        recordCellChange(
            undo,
            GameStateArrayKind.UnknownTileCounts,
            player,
            tile,
            unknownTileCountsByPlayer[player][tile],
        )
        --unknownTileCountsByPlayer[player][tile]
        markPlayerHandChanged(player)
    }

    private fun drawWallTileToPlayer(player: Int, undo: GameStateUndo? = null) {
        check(liveWallSize > 0)
        val drawIndex = LIVE_WALL_DRAWABLE_SIZE - liveWallSize
        val tile = wall[drawIndex]
        addConcealedTile(player, tile, undo)
        --liveWallSize
        lastDrawnTile = tile
        lastDrawnPlayer = player
        // The draw that empties the live wall is the seabed (9.1.1 / riverbed 9.1.2).
        if (liveWallSize == 0) seabedPlayer = player
    }

    private fun onTileRevealedToAllPlayers(tile: Int, count: Int = 1, undo: GameStateUndo? = null) {
        check(tile in 0 until NUM_TILE_TYPES)
        if (count <= 0) return

        // Skip current_player: their unknown counts were already decremented
        // by addConcealedTile when they drew these tiles.
        for (player in 0 until NUM_PLAYERS) {
            if (player == currentPlayer) continue
            recordCellChange(
                undo,
                GameStateArrayKind.UnknownTileCounts,
                player,
                tile,
                unknownTileCountsByPlayer[player][tile],
            )
            val value = unknownTileCountsByPlayer[player][tile]
            unknownTileCountsByPlayer[player][tile] = max(0, value - count)
        }
    }

    private fun applyChoiceImpl(choice: Choice, undo: GameStateUndo?) {
        if (decisionType == DecisionType.ReactToDiscard) {
            applyReactToDiscard(choice, undo)
            return
        }

        when (choice.kind) {
            ChoiceKind.Hu -> {
                handScore = choice.huScore()
                winnerSeat = currentPlayer
                winSource = -1
                handEnded = true
            }
            ChoiceKind.Keep -> {
                val tile = choice.tile()
                check(tile in 0 until NUM_TILE_TYPES)
                check(players[currentPlayer].concealed[tile] == 4) {
                    "Keep only for a current four-of-a-kind, tile=$tile count=${players[currentPlayer].concealed[tile]}"
                }
                check(liveWallSize > 0)
                check(!declinedConcealedKong[tile]) { "already kept concealed kong of tile $tile" }
                declinedConcealedKong[tile] = true
                // Stay ActOnHand: next enumerate may offer another kong gate or discards.
            }
            ChoiceKind.Discard -> {
                val tile = choice.tile()
                check(players[currentPlayer].concealed[tile] > 0)

                recordCellChange(
                    undo,
                    GameStateArrayKind.Concealed,
                    currentPlayer,
                    tile,
                    players[currentPlayer].concealed[tile],
                )
                setConcealedTileCount(
                    currentPlayer,
                    tile,
                    players[currentPlayer].concealed[tile] - 1,
                )
                onTileRevealedToAllPlayers(tile, 1, undo)
                markPlayerHandChanged(currentPlayer)

                lastDiscarded = tile
                lastDiscarder = currentPlayer
                // After discard the hand is fully re-sorted (no right-side drawn tile).
                lastDrawnTile = -1
                lastDrawnPlayer = -1
                lastDrawWasKongSupplement = false
                kongRobWindow = false
                decisionType = DecisionType.ReactToDiscard
                clearPendingReactions()
                clearDeclinedConcealedKongs()
                currentPlayer = (lastDiscarder + 1) % NUM_PLAYERS
            }
            ChoiceKind.ConcealedKong -> {
                val tile = choice.tile()
                check(players[currentPlayer].concealed[tile] == 4)

                recordCellChange(
                    undo,
                    GameStateArrayKind.Concealed,
                    currentPlayer,
                    tile,
                    players[currentPlayer].concealed[tile],
                )
                setConcealedTileCount(currentPlayer, tile, 0)
                appendMeldExposure(
                    undo,
                    currentPlayer,
                    PlayerState.MELD_SET,
                    tile,
                    setState = PlayerState.PK_CONCEALED_KONG,
                )
                recordCellChange(
                    undo,
                    GameStateArrayKind.PungKongCalledFrom,
                    currentPlayer,
                    tile,
                    players[currentPlayer].pungKongCalledFrom[tile],
                )
                players[currentPlayer].pungKongCalledFrom[tile] = -1
                onTileRevealedToAllPlayers(tile, 4, undo)
                markPlayerHandChanged(currentPlayer)

                // Fresh ActOnHand after supplement: re-offer any remaining fours.
                clearDeclinedConcealedKongs()
                drawWallTileToPlayer(currentPlayer, undo)
                lastDrawWasKongSupplement = true
            }
            ChoiceKind.PromoteKong -> {
                val tile = choice.tile()
                val promoter = currentPlayer
                check(players[promoter].concealed[tile] == 1)
                check(players[promoter].pungsKongs[tile] == PlayerState.PK_OPEN_PUNG)
                // Keep pungKongCalledFrom: a promote kong still points at the original discarder.

                recordCellChange(
                    undo,
                    GameStateArrayKind.Concealed,
                    promoter,
                    tile,
                    players[promoter].concealed[tile],
                )
                setConcealedTileCount(promoter, tile, 0)
                onTileRevealedToAllPlayers(tile, 1, undo)
                markPlayerHandChanged(promoter)

                lastDiscarded = tile
                lastDiscarder = promoter
                lastDrawnTile = -1
                lastDrawnPlayer = -1
                lastDrawWasKongSupplement = false
                kongRobWindow = true
                decisionType = DecisionType.ReactToDiscard
                clearPendingReactions()
                clearDeclinedConcealedKongs()

                if (anyOtherPlayerCanRobKong()) {
                    currentPlayer = (promoter + 1) % NUM_PLAYERS
                } else {
                    completePromotedKongAndDraw(undo)
                }
            }
            else -> error("Unexpected ActOnHand choice kind: ${choice.kind}")
        }
    }

    private fun applyReactToDiscard(choice: Choice, undo: GameStateUndo?) {
        check(currentPlayer in 0 until NUM_PLAYERS)
        check(lastDiscarder in 0 until NUM_PLAYERS)
        check(currentPlayer != lastDiscarder)
        if (kongRobWindow) {
            check(choice.kind == ChoiceKind.Pass || choice.kind == ChoiceKind.Hu) {
                "kong rob window only allows Pass/Hu, got ${choice.kind}"
            }
        } else {
            check(isReactionChoiceKind(choice.kind))
        }

        pendingReactions[currentPlayer] = choice
        if (!hasPendingReaction[currentPlayer]) {
            hasPendingReaction[currentPlayer] = true
            ++pendingReactionCount
        }

        if (pendingReactionCount < (NUM_PLAYERS - 1)) {
            for (step in 1 until NUM_PLAYERS) {
                val candidate = (lastDiscarder + step) % NUM_PLAYERS
                if (!hasPendingReaction[candidate]) {
                    currentPlayer = candidate
                    return
                }
            }
            error("Expected a pending reactor after partial reaction collection")
        }

        val resolution = resolveReactions(lastDiscarder, pendingReactions)
        clearPendingReactions()
        applyResolution(resolution, undo)
    }

    /**
     * Apply a resolved reaction decision (either [ReactionResolution.AllPass] or [ReactionResolution.Winning])
     * to mutate the game state into the next decision state.
     */
    fun applyResolution(resolution: ReactionResolution, undo: GameStateUndo? = null) {
        when (resolution) {
            is ReactionResolution.AllPass -> {
                if (kongRobWindow) {
                    completePromotedKongAndDraw(undo)
                    return
                }
                // Everyone passed: the discard stays in the discarder's river.
                if (lastDiscarded in 0 until NUM_TILE_TYPES && lastDiscarder in 0 until NUM_PLAYERS) {
                    discards[lastDiscarder].add(lastDiscarded)
                    if (undo != null) {
                        undo.riverAppendedPlayer = lastDiscarder
                        undo.riverAppendedTile = lastDiscarded
                    }
                }
                currentPlayer = (lastDiscarder + 1) % NUM_PLAYERS
                decisionType = DecisionType.ActOnHand
                lastDrawWasKongSupplement = false
                if (liveWallSize == 0) {
                    // Nothing left to draw and nobody claimed: the hand is a draw.
                    // This is the only place an exhausted wall ends the hand.
                    handEnded = true
                    return
                }
                drawWallTileToPlayer(currentPlayer, undo)
            }
            is ReactionResolution.Winning -> {
                val bestPlayer = resolution.seat
                val bestChoice = resolution.choice
                currentPlayer = bestPlayer

                if (bestChoice.kind == ChoiceKind.Hu) {
                    handScore = bestChoice.huScore()
                    winnerSeat = bestPlayer
                    winSource = lastDiscarder
                    handEnded = true
                    // Keep [kongRobWindow] so terminal scoring still sees Robbing a Kong (9.3).
                    return
                }

                if (bestChoice.kind == ChoiceKind.Pung) {
                    val tile = bestChoice.tile()
                    check(tile == lastDiscarded)
                    check(players[currentPlayer].concealed[tile] >= 2)

                    recordCellChange(
                        undo,
                        GameStateArrayKind.Concealed,
                        currentPlayer,
                        tile,
                        players[currentPlayer].concealed[tile],
                    )
                    setConcealedTileCount(
                        currentPlayer,
                        tile,
                        players[currentPlayer].concealed[tile] - 2,
                    )
                    appendMeldExposure(
                        undo,
                        currentPlayer,
                        PlayerState.MELD_SET,
                        tile,
                        setState = PlayerState.PK_OPEN_PUNG,
                    )
                    recordCellChange(
                        undo,
                        GameStateArrayKind.PungKongCalledFrom,
                        currentPlayer,
                        tile,
                        players[currentPlayer].pungKongCalledFrom[tile],
                    )
                    players[currentPlayer].pungKongCalledFrom[tile] = lastDiscarder
                    onTileRevealedToAllPlayers(tile, 2, undo)
                    markPlayerHandChanged(currentPlayer)
                }

                if (bestChoice.kind == ChoiceKind.Kong) {
                    val tile = bestChoice.tile()
                    check(tile == lastDiscarded)
                    check(players[currentPlayer].concealed[tile] >= 3)

                    recordCellChange(
                        undo,
                        GameStateArrayKind.Concealed,
                        currentPlayer,
                        tile,
                        players[currentPlayer].concealed[tile],
                    )
                    setConcealedTileCount(
                        currentPlayer,
                        tile,
                        players[currentPlayer].concealed[tile] - 3,
                    )
                    appendMeldExposure(
                        undo,
                        currentPlayer,
                        PlayerState.MELD_SET,
                        tile,
                        setState = PlayerState.PK_OPEN_KONG,
                    )
                    recordCellChange(
                        undo,
                        GameStateArrayKind.PungKongCalledFrom,
                        currentPlayer,
                        tile,
                        players[currentPlayer].pungKongCalledFrom[tile],
                    )
                    players[currentPlayer].pungKongCalledFrom[tile] = lastDiscarder
                    onTileRevealedToAllPlayers(tile, 3, undo)
                    markPlayerHandChanged(currentPlayer)
                }

                if (bestChoice.kind == ChoiceKind.Chow) {
                    val first = bestChoice.chowFirstTile()
                    val second = first + 1
                    val third = first + 2
                    check(lastDiscarded in first..third)
                    check(first >= 0 && third < 27)

                    if (first != lastDiscarded) {
                        check(players[currentPlayer].concealed[first] > 0)
                        recordCellChange(
                            undo,
                            GameStateArrayKind.Concealed,
                            currentPlayer,
                            first,
                            players[currentPlayer].concealed[first],
                        )
                        setConcealedTileCount(
                            currentPlayer,
                            first,
                            players[currentPlayer].concealed[first] - 1,
                        )
                        onTileRevealedToAllPlayers(first, 1, undo)
                    }
                    if (second != lastDiscarded) {
                        check(players[currentPlayer].concealed[second] > 0)
                        recordCellChange(
                            undo,
                            GameStateArrayKind.Concealed,
                            currentPlayer,
                            second,
                            players[currentPlayer].concealed[second],
                        )
                        setConcealedTileCount(
                            currentPlayer,
                            second,
                            players[currentPlayer].concealed[second] - 1,
                        )
                        onTileRevealedToAllPlayers(second, 1, undo)
                    }
                    if (third != lastDiscarded) {
                        check(players[currentPlayer].concealed[third] > 0)
                        recordCellChange(
                            undo,
                            GameStateArrayKind.Concealed,
                            currentPlayer,
                            third,
                            players[currentPlayer].concealed[third],
                        )
                        setConcealedTileCount(
                            currentPlayer,
                            third,
                            players[currentPlayer].concealed[third] - 1,
                        )
                        onTileRevealedToAllPlayers(third, 1, undo)
                    }

                    val suitIndex = first / 9
                    val suitPos = first % 9
                    check(suitPos <= 6)
                    val chowIndex = suitIndex * 7 + suitPos
                    appendMeldExposure(
                        undo,
                        currentPlayer,
                        PlayerState.MELD_CHOW,
                        chowIndex,
                        claimTile = lastDiscarded,
                        from = lastDiscarder,
                    )

                    markPlayerHandChanged(currentPlayer)
                }

                if (bestChoice.kind == ChoiceKind.Kong) {
                    drawWallTileToPlayer(currentPlayer, undo)
                    lastDrawWasKongSupplement = true
                }

                decisionType = DecisionType.ActOnHand
                kongRobWindow = false
            }
        }
    }

    /**
     * Finish a small exposed kong after the rob window (nobody Hu'd): upgrade the
     * pung, draw the supplement, stay ActOnHand for the promoter.
     */
    private fun completePromotedKongAndDraw(undo: GameStateUndo?) {
        val tile = lastDiscarded
        val promoter = lastDiscarder
        check(tile in 0 until NUM_TILE_TYPES)
        check(promoter in 0 until NUM_PLAYERS)
        check(players[promoter].pungsKongs[tile] == PlayerState.PK_OPEN_PUNG)
        check(liveWallSize > 0)

        recordCellChange(
            undo,
            GameStateArrayKind.PungsKongs,
            promoter,
            tile,
            players[promoter].pungsKongs[tile],
        )
        players[promoter].promoteMeldToKong(tile)
        markPlayerHandChanged(promoter)

        kongRobWindow = false
        currentPlayer = promoter
        decisionType = DecisionType.ActOnHand
        drawWallTileToPlayer(promoter, undo)
        lastDrawWasKongSupplement = true
    }

    /** True if any seat other than [lastDiscarder] can Hu on the pending promote tile. */
    private fun anyOtherPlayerCanRobKong(): Boolean {
        check(kongRobWindow)
        check(lastDiscarded in 0 until NUM_TILE_TYPES)
        for (player in 0 until NUM_PLAYERS) {
            if (player == lastDiscarder) continue
            if (discardReactionHuScoreOrNegativeForPlayer(player, lastDiscarded) > 0) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val ENABLE_HU_SCORE_CACHE = true

        private val CANONICAL_WALL: IntArray = IntArray(WALL_SIZE) { it / 4 }

        /**
         * Deal a new hand from a fixed wall order (draw sequence).
         *
         * [wallOrder] is the full [WALL_SIZE]-tile multiset in the order draws will
         * consume it: index 0 is the first draw, then 1, 2, … (see [drawWallTileToPlayer]).
         * Opening deal takes 13 tiles for seats 0..3 in order, then one extra for East.
         *
         * Used for scripted tutorial deals and tests. Validates the multiset once at
         * deal time (not on the MC playout hot path — that uses [copy]/[copyFrom]).
         *
         * Copies [wallOrder] into the new state; the caller's array is not retained.
         *
         * @param eastSeat dealer (East); they receive the 14th tile and act first
         * @param allowChickenHands fixed for this hand (from the enclosing game config)
         * @throws IllegalArgumentException if length, tile indices, or counts are invalid
         */
        fun fromWallOrder(
            wallOrder: IntArray,
            eastSeat: Int = 0,
            allowChickenHands: Boolean = false,
        ): GameState {
            requireValidWallOrder(wallOrder)
            val dealer = ((eastSeat % NUM_PLAYERS) + NUM_PLAYERS) % NUM_PLAYERS
            val gs = GameState()
            gs.eastSeat = dealer
            gs.allowChickenHands = allowChickenHands
            System.arraycopy(wallOrder, 0, gs.wall, 0, WALL_SIZE)
            dealOpeningHand(gs, dealer)
            return gs
        }

        /**
         * Build a mid-hand (or any) [GameState] from a structured [layout].
         *
         * Validates phase fields and the full tile multiset (remaining wall + hands +
         * melds + rivers + pending discard), then [hydrateFrom]. Used for tutorial
         * mid-hand claims/Hu without replaying dozens of moves.
         *
         * Free-play [inego.zungjunggame.persist.SessionSnapshotSchema] shares [hydrateFrom] but
         * does not run multiset validation (binary restore / test fixtures).
         *
         * @throws IllegalArgumentException if layout invariants fail
         */
        fun fromLayout(layout: GameStateLayout): GameState {
            requireValidLayout(layout)
            val gs = GameState()
            gs.hydrateFrom(layout)
            return gs
        }

        /**
         * Structural + multiset checks for author/tutorial layouts.
         * Not used on the MC playout hot path.
         */
        fun requireValidLayout(layout: GameStateLayout) {
            require(layout.liveWallSize in 0..LIVE_WALL_DRAWABLE_SIZE) {
                "liveWallSize ${layout.liveWallSize}, expected 0..$LIVE_WALL_DRAWABLE_SIZE"
            }
            require(layout.eastSeat in 0 until NUM_PLAYERS) {
                "eastSeat ${layout.eastSeat}"
            }
            require(layout.currentPlayer in 0 until NUM_PLAYERS) {
                "currentPlayer ${layout.currentPlayer}"
            }
            require(layout.pendingReactionCount in 0 until NUM_PLAYERS) {
                "pendingReactionCount ${layout.pendingReactionCount}"
            }
            var pendingFlags = 0
            for (p in 0 until NUM_PLAYERS) {
                if (layout.hasPendingReaction[p]) pendingFlags++
            }
            require(pendingFlags == layout.pendingReactionCount) {
                "hasPendingReaction flags ($pendingFlags) != pendingReactionCount " +
                    "${layout.pendingReactionCount}"
            }

            when (layout.decisionType) {
                DecisionType.ActOnHand -> {
                    // lastDiscard* may still hold history; no strict requirement.
                }
                DecisionType.ReactToDiscard -> {
                    require(layout.lastDiscarder in 0 until NUM_PLAYERS) {
                        "ReactToDiscard lastDiscarder ${layout.lastDiscarder}"
                    }
                    require(layout.lastDiscarded in 0 until NUM_TILE_TYPES) {
                        "ReactToDiscard lastDiscarded ${layout.lastDiscarded}"
                    }
                    require(layout.currentPlayer != layout.lastDiscarder) {
                        "reactor cannot be lastDiscarder"
                    }
                    if (layout.kongRobWindow) {
                        val promoter = layout.players[layout.lastDiscarder]
                        require(
                            promoter.pungsKongs[layout.lastDiscarded] == PlayerState.PK_OPEN_PUNG,
                        ) {
                            "kong rob window requires open pung of lastDiscarded on lastDiscarder"
                        }
                    }
                }
            }
            require(!layout.kongRobWindow || layout.decisionType == DecisionType.ReactToDiscard) {
                "kongRobWindow only during ReactToDiscard"
            }

            if (layout.lastDrawnTile >= 0) {
                require(layout.lastDrawnTile in 0 until NUM_TILE_TYPES) {
                    "lastDrawnTile ${layout.lastDrawnTile}"
                }
                require(layout.lastDrawnPlayer in 0 until NUM_PLAYERS) {
                    "lastDrawnPlayer ${layout.lastDrawnPlayer}"
                }
            }
            require(layout.seabedPlayer >= -1 && layout.seabedPlayer < NUM_PLAYERS) {
                "seabedPlayer ${layout.seabedPlayer}"
            }
            require(!layout.handEnded || layout.handScore > 0 || layout.liveWallSize == 0) {
                "handEnded with liveWallSize ${layout.liveWallSize} and handScore ${layout.handScore}"
            }

            for (t in 0 until NUM_TILE_TYPES) {
                require(!layout.declinedConcealedKong[t] || layout.decisionType == DecisionType.ActOnHand) {
                    "declinedConcealedKong only meaningful in ActOnHand"
                }
            }

            for (p in 0 until NUM_PLAYERS) {
                val pl = layout.players[p]
                for (tile in 0 until NUM_TILE_TYPES) {
                    require(pl.concealed[tile] in 0..4) {
                        "player $p concealed[$tile]=${pl.concealed[tile]}"
                    }
                    require(pl.pungsKongs[tile] in PlayerState.PK_NONE..PlayerState.PK_CONCEALED_KONG) {
                        "player $p pungsKongs[$tile]=${pl.pungsKongs[tile]}"
                    }
                }
                for (c in 0 until NUM_CHOW_TYPES) {
                    require(pl.openChows[c] >= 0) { "player $p openChows[$c]=${pl.openChows[c]}" }
                }
                for (tile in layout.discards[p]) {
                    require(tile in 0 until NUM_TILE_TYPES) {
                        "player $p discard tile out of range: $tile"
                    }
                }
            }

            requireValidLayoutMultiset(layout)
        }

        /**
         * Remaining wall tiles + concealed + open melds + rivers + pending discard
         * must form exactly four of each tile type.
         */
        private fun requireValidLayoutMultiset(layout: GameStateLayout) {
            val counts = IntArray(NUM_TILE_TYPES)
            val drawIndex = LIVE_WALL_DRAWABLE_SIZE - layout.liveWallSize
            require(drawIndex in 0..LIVE_WALL_DRAWABLE_SIZE) {
                "drawIndex $drawIndex from liveWallSize ${layout.liveWallSize}"
            }
            for (i in 0 until WALL_SIZE) {
                val tile = layout.wall[i]
                require(tile in 0 until NUM_TILE_TYPES) {
                    "wall[$i] tile out of range: $tile"
                }
            }
            // Undrawn live wall + dead wall still in the wall array.
            for (i in drawIndex until WALL_SIZE) {
                counts[layout.wall[i]]++
            }
            for (p in 0 until NUM_PLAYERS) {
                val pl = layout.players[p]
                for (tile in 0 until NUM_TILE_TYPES) {
                    counts[tile] += pl.concealed[tile]
                    when (pl.pungsKongs[tile]) {
                        PlayerState.PK_NONE -> Unit
                        PlayerState.PK_OPEN_PUNG -> counts[tile] += 3
                        PlayerState.PK_OPEN_KONG, PlayerState.PK_CONCEALED_KONG -> counts[tile] += 4
                        else -> error("unexpected pungsKongs value ${pl.pungsKongs[tile]}")
                    }
                }
                // The exposure log is the meld list the table shows: a chow counts
                // its three tiles here, a set's tiles are counted from pungsKongs
                // above. A slot and its rules-index entry must both exist.
                val loggedSets = BooleanArray(NUM_TILE_TYPES)
                for (slot in 0 until pl.meldCount) {
                    val value = pl.meldValue[slot]
                    when (pl.meldKind[slot]) {
                        PlayerState.MELD_CHOW -> {
                            require(value in 0 until NUM_CHOW_TYPES) {
                                "player $p meldValue[$slot]=$value"
                            }
                            require(pl.openChows[value] > 0) {
                                "player $p meld slot $slot has no open chow $value"
                            }
                            val suit = value / 7
                            val pos = value % 7
                            val first = suit * 9 + pos
                            counts[first]++
                            counts[first + 1]++
                            counts[first + 2]++
                        }
                        PlayerState.MELD_SET -> {
                            require(value in 0 until NUM_TILE_TYPES) {
                                "player $p meldValue[$slot]=$value"
                            }
                            require(pl.pungsKongs[value] != PlayerState.PK_NONE) {
                                "player $p meld slot $slot has no pungsKongs state for tile $value"
                            }
                            loggedSets[value] = true
                        }
                        else -> error("unexpected meldKind value ${pl.meldKind[slot]}")
                    }
                }
                for (tile in 0 until NUM_TILE_TYPES) {
                    require(pl.pungsKongs[tile] == PlayerState.PK_NONE || loggedSets[tile]) {
                        "player $p pungsKongs[$tile]=${pl.pungsKongs[tile]} has no meld log slot"
                    }
                }
                for (tile in layout.discards[p]) {
                    counts[tile]++
                }
            }
            if (layout.decisionType == DecisionType.ReactToDiscard &&
                layout.lastDiscarded in 0 until NUM_TILE_TYPES
            ) {
                // Pending discard is not yet in a river.
                counts[layout.lastDiscarded]++
            }
            for (t in 0 until NUM_TILE_TYPES) {
                require(counts[t] == 4) {
                    "layout tile $t count ${counts[t]}, expected 4"
                }
            }
        }

        /**
         * Deal a new hand. [eastSeat] is the dealer (East); they receive the 14th tile
         * and act first. Seat winds for other seats follow counter-clockwise order.
         * [allowChickenHands] is fixed for this hand (from the enclosing game config).
         *
         * Implementation: shuffle a canonical wall, then [fromWallOrder].
         */
        fun generateRandom(
            rng: Random = Random(System.nanoTime()),
            eastSeat: Int = 0,
            allowChickenHands: Boolean = false,
        ): GameState {
            val wall = IntArray(WALL_SIZE)
            System.arraycopy(CANONICAL_WALL, 0, wall, 0, WALL_SIZE)

            // Fisher-Yates
            for (i in WALL_SIZE - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = wall[i]
                wall[i] = wall[j]
                wall[j] = tmp
            }

            return fromWallOrder(wall, eastSeat = eastSeat, allowChickenHands = allowChickenHands)
        }

        /**
         * Opening deal on a state whose [GameState.wall] is already filled.
         * Clears players / rivers, draws 13+1, sets ActOnHand for [dealer].
         */
        private fun dealOpeningHand(gs: GameState, dealer: Int) {
            for (p in gs.players) p.clear()
            for (p in 0 until NUM_PLAYERS) gs.discards[p].clear()
            gs.initializeUnknownTileCounts()
            gs.liveWallSize = LIVE_WALL_DRAWABLE_SIZE

            for (player in 0 until NUM_PLAYERS) {
                repeat(13) { gs.drawWallTileToPlayer(player) }
            }
            // East (dealer) starts with 14 tiles.
            gs.drawWallTileToPlayer(dealer)

            gs.lastDiscarder = -1
            gs.lastDiscarded = -1
            // Opening deal: all 14 dealer tiles are equal (fully sorted hand).
            // Only mid-hand wall draws park lastDrawnTile for the right-side slot.
            gs.lastDrawnTile = -1
            gs.lastDrawnPlayer = -1
            gs.seabedPlayer = -1
            gs.handEnded = false
            gs.currentPlayer = dealer
            gs.decisionType = DecisionType.ActOnHand
            gs.clearPendingReactions()
            gs.clearDeclinedConcealedKongs()
            gs.lastDrawWasKongSupplement = false
            gs.kongRobWindow = false
            gs.handScore = 0
            gs.winnerSeat = -1
            gs.winSource = -1

            for (player in gs.players) {
                player.ensureActiveConcealedTiles()
            }
        }

        /**
         * [wallOrder] must be length [WALL_SIZE] with exactly four of each tile type 0..33.
         * Deal-time only — do not call from [copyFrom] or MC playouts.
         */
        private fun requireValidWallOrder(wallOrder: IntArray) {
            require(wallOrder.size == WALL_SIZE) {
                "wallOrder size ${wallOrder.size}, expected $WALL_SIZE"
            }
            val counts = IntArray(NUM_TILE_TYPES)
            for (tile in wallOrder) {
                require(tile in 0 until NUM_TILE_TYPES) {
                    "wallOrder tile out of range: $tile"
                }
                counts[tile]++
            }
            for (t in 0 until NUM_TILE_TYPES) {
                require(counts[t] == 4) {
                    "wallOrder tile $t count ${counts[t]}, expected 4"
                }
            }
        }

        private fun isReactionChoiceKind(kind: ChoiceKind): Boolean =
            kind == ChoiceKind.Pass ||
                kind == ChoiceKind.Chow ||
                kind == ChoiceKind.Pung ||
                kind == ChoiceKind.Kong ||
                kind == ChoiceKind.Hu
    }
}
