package inego.zungjunggame.engine

/**
 * One completing tile for a discard under assessment.
 *
 * @param tileType the tile that completes a scorable Hu after the discard
 * @param unseen how many copies of [tileType] this seat cannot see
 * @param score the pattern total of the finished 14-tile hand, scored without a claimed
 *   tile; see [DiscardWaits] for what that leaves out
 */
data class DiscardWait(
    val tileType: Int,
    val unseen: Int,
    val score: Int,
)

/**
 * Ready-discard table for one human discard turn: each legal discard tile type
 * that leaves a scorable wait mapped to its waits, in tile-type order. Immutable;
 * empty off a discard turn or when nothing is ready.
 *
 * [readyTypes] is every discard that leaves at least one scorable wait. The
 * magnifier reads [waitsFor] for the tile under the finger / cursor.
 */
class DiscardWaitTable internal constructor(
    private val byDiscard: Map<Int, List<DiscardWait>>,
) {
    val readyTypes: Set<Int> = byDiscard.keys

    fun waitsFor(tileType: Int): List<DiscardWait> = byDiscard[tileType] ?: emptyList()

    val isEmpty: Boolean get() = byDiscard.isEmpty()

    companion object {
        val Empty = DiscardWaitTable(emptyMap())
    }
}

/**
 * Ready-discard assessment for the human hand.
 *
 * A discard tile type X is **ready** when throwing it leaves at least one unseen
 * tile Y that completes a scorable Hu. The engine owns the assessment; the frame
 * projection ([inego.zungjunggame.features.board.boardTableInputs]) decides when
 * to run it and publishes the result.
 *
 * For each legal discard X:
 *  1. drop one X from a scratch copy of the hand (melds stay);
 *  2. [DeficiencyFast.handDeficiencyCached] must be 0 (one tile from a complete
 *     shape) or the discard is not ready;
 *  3. walk the acceptance candidates ([DeficiencyFast.acceptanceTiles]) with an
 *     unseen copy, keep Y only when adding it makes the deficiency -1;
 *  4. score the finished 14-tile hand with [Scoring.computeHandScoreDetails]
 *     (the [PlayerState] overload) and keep Y only when the total is above 0. The
 *     wait is scored as if drawn from the wall, so the total is a hint rather than a
 *     promise: a tile that is later claimed and completes a pung loses its series 4.2
 *     credit, and the 9.x incidentals are never part of this figure.
 *
 * The table never calls this. It is memoized so a repeat of the same decision is
 * a key compare; the per-decision deficiency and score maps are dropped when the
 * decision key changes.
 */
object DiscardWaits {
    private const val CHICKEN_SALT = -0x61C8864680B583EBL

    /** Reused working copy of the hand under assessment. */
    private val scratch = PlayerState()
    private val accept = BooleanArray(NUM_TILE_TYPES)
    private val defCache = HashMap<Long, Int>()
    private val scoreCache = HashMap<Long, Int>()

    private var haveMemo = false
    private val memoConcealed = IntArray(NUM_TILE_TYPES)
    private val memoOpenChows = IntArray(NUM_CHOW_TYPES)
    private val memoPungsKongs = IntArray(NUM_TILE_TYPES)
    private val memoUnseen = IntArray(NUM_TILE_TYPES)
    private val memoDiscards = IntArray(NUM_TILE_TYPES)
    private val incomingDiscards = IntArray(NUM_TILE_TYPES)
    private var memoSeat = -1
    private var memoEast = -1
    private var memoChicken = false
    private var memoTable: DiscardWaitTable = DiscardWaitTable.Empty

    /**
     * Assess [player]'s legal discards. [discardTypes] are the tile types offered
     * as [ChoiceKind.Discard] choices, [unseen] is this seat's 34-type unseen
     * count, [playerIndex] is the seat, [eastSeat] the dealer.
     */
    fun assess(
        player: PlayerState,
        discardTypes: IntArray,
        unseen: IntArray,
        playerIndex: Int,
        eastSeat: Int,
        allowChickenHands: Boolean,
    ): DiscardWaitTable {
        incomingDiscards.fill(0)
        for (x in discardTypes) {
            if (x in 0 until NUM_TILE_TYPES) incomingDiscards[x] = 1
        }
        if (haveMemo && memoMatches(player, unseen, playerIndex, eastSeat, allowChickenHands)) {
            return memoTable
        }
        storeMemo(player, unseen, playerIndex, eastSeat, allowChickenHands)
        defCache.clear()
        scoreCache.clear()
        memoTable = compute(player, discardTypes, unseen, playerIndex, eastSeat, allowChickenHands)
        return memoTable
    }

    private fun memoMatches(
        player: PlayerState,
        unseen: IntArray,
        playerIndex: Int,
        eastSeat: Int,
        allowChickenHands: Boolean,
    ): Boolean {
        if (memoSeat != playerIndex || memoEast != eastSeat || memoChicken != allowChickenHands) {
            return false
        }
        if (!memoConcealed.contentEquals(player.concealed)) return false
        if (!memoOpenChows.contentEquals(player.openChows)) return false
        if (!memoPungsKongs.contentEquals(player.pungsKongs)) return false
        if (!memoUnseen.contentEquals(unseen)) return false
        return memoDiscards.contentEquals(incomingDiscards)
    }

    private fun storeMemo(
        player: PlayerState,
        unseen: IntArray,
        playerIndex: Int,
        eastSeat: Int,
        allowChickenHands: Boolean,
    ) {
        player.concealed.copyInto(memoConcealed)
        player.openChows.copyInto(memoOpenChows)
        player.pungsKongs.copyInto(memoPungsKongs)
        unseen.copyInto(memoUnseen)
        incomingDiscards.copyInto(memoDiscards)
        memoSeat = playerIndex
        memoEast = eastSeat
        memoChicken = allowChickenHands
        haveMemo = true
    }

    private fun compute(
        player: PlayerState,
        discardTypes: IntArray,
        unseen: IntArray,
        playerIndex: Int,
        eastSeat: Int,
        allowChickenHands: Boolean,
    ): DiscardWaitTable {
        val meldSig = meldSignature(player)
        val seatWind = SeatWinds.tileForPlayer(playerIndex, eastSeat)
        val byDiscard = HashMap<Int, List<DiscardWait>>()
        for (x in discardTypes) {
            if (x !in 0 until NUM_TILE_TYPES) continue
            if (player.concealed[x] <= 0) continue

            scratch.copyFrom(player)
            scratch.concealed[x]--
            scratch.activeConcealedTileCount = -1
            val base = DeficiencyFast.handDeficiencyCached(scratch, defCache)
            if (base != 0) continue

            DeficiencyFast.acceptanceTiles(scratch, unseen, base, accept)
            var waits: ArrayList<DiscardWait>? = null
            for (y in 0 until NUM_TILE_TYPES) {
                if (!accept[y]) continue
                val avail = unseen[y]
                if (avail <= 0) continue
                scratch.concealed[y]++
                scratch.activeConcealedTileCount = -1
                val next = DeficiencyFast.handDeficiencyCached(scratch, defCache)
                if (next == -1) {
                    val score = scoreFor(
                        scratch,
                        playerIndex,
                        eastSeat,
                        seatWind,
                        meldSig,
                        allowChickenHands,
                    )
                    if (score > 0) {
                        if (waits == null) waits = ArrayList()
                        waits.add(DiscardWait(y, avail, score))
                    }
                }
                scratch.concealed[y]--
            }
            if (waits != null) byDiscard[x] = waits
        }
        return DiscardWaitTable(byDiscard)
    }

    private fun scoreFor(
        ps: PlayerState,
        playerIndex: Int,
        eastSeat: Int,
        seatWind: Int,
        meldSig: Long,
        allowChickenHands: Boolean,
    ): Int {
        val key = DeficiencyFast.hashConcealed(ps.concealed) xor
            meldSig xor
            (seatWind.toLong() shl 40) xor
            (if (allowChickenHands) CHICKEN_SALT else 0L)
        scoreCache[key]?.let { return it }
        val score = Scoring.computeHandScoreDetails(
            ps,
            playerIndex,
            eastSeat,
            allowChickenHands,
        ).totalScore
        scoreCache[key] = score
        return score
    }

    private fun meldSignature(player: PlayerState): Long {
        var h = -3750763034362895579L
        for (c in player.openChows) h = (h xor (c + 1).toLong()) * 1099511628211L
        for (c in player.pungsKongs) h = (h xor (c + 1).toLong()) * 1099511628211L
        return h
    }
}
