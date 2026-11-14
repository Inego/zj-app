package inego.zungjunggame.engine

/** Per-player visible state. */
class PlayerState {
    val concealed = IntArray(NUM_TILE_TYPES)
    val openChows = IntArray(NUM_CHOW_TYPES)
    val pungsKongs = IntArray(NUM_TILE_TYPES)

    /**
     * Absolute seat that discarded the tile for an open pung/kong of each type.
     * `-1` when none, or for a concealed kong.
     * Preserved when a pung is promoted to an open kong.
     */
    val pungKongCalledFrom = IntArray(NUM_TILE_TYPES) { -1 }

    /**
     * Exposed melds in declaration (exposure) order — the list the table shows,
     * max one meld per slot and at most 4 melds.
     *
     * Parallel arrays: [MELD_CHOW] or [MELD_SET] and its chow index or tile type;
     * [meldClaimTile]/[meldFrom] are the chow's claimed discard tile and discarder
     * seat (sets keep their call source in [pungKongCalledFrom]).
     */
    val meldKind = IntArray(MAX_OPEN_MELDS)
    val meldValue = IntArray(MAX_OPEN_MELDS)
    val meldClaimTile = IntArray(MAX_OPEN_MELDS)
    val meldFrom = IntArray(MAX_OPEN_MELDS)
    var meldCount: Int = 0

    /** Cached list of tile types with positive concealed count. */
    val activeConcealedTiles = IntArray(NUM_TILE_TYPES)
    var activeConcealedTileCount: Int = -1

    /**
     * Append one exposed meld to the log and to the rules index it mirrors: a chow
     * counts in [openChows], a set takes [setState] in [pungsKongs].
     *
     * The only writer of [meldKind]/[meldValue]/[meldClaimTile]/[meldFrom], so the
     * exposure order and the rules index cannot drift.
     */
    fun recordMeldExposure(
        kind: Int,
        value: Int,
        claimTile: Int = -1,
        from: Int = -1,
        setState: Int = PK_NONE,
    ) {
        require(meldCount < MAX_OPEN_MELDS) { "meld log full" }
        if (kind == MELD_CHOW) {
            require(value in 0 until NUM_CHOW_TYPES) { "chow index $value" }
            ++openChows[value]
        } else {
            require(kind == MELD_SET) { "meld kind $kind" }
            require(value in 0 until NUM_TILE_TYPES) { "tile type $value" }
            require(setState != PK_NONE) { "set of tile $value without pungsKongs state" }
            pungsKongs[value] = setState
        }
        val slot = meldCount
        meldKind[slot] = kind
        meldValue[slot] = value
        meldClaimTile[slot] = claimTile
        meldFrom[slot] = from
        meldCount = slot + 1
    }

    /**
     * Promote an exposed pung to an open kong in place: the meld keeps its log slot
     * and its chronological position, and [meldKind]/[meldValue] do not change —
     * the display reads the current kind from [pungsKongs].
     */
    fun promoteMeldToKong(tile: Int) {
        require(pungsKongs[tile] == PK_OPEN_PUNG) { "tile $tile is not an open pung" }
        pungsKongs[tile] = PK_OPEN_KONG
    }

    fun clear() {
        concealed.fill(0)
        openChows.fill(0)
        pungsKongs.fill(0)
        pungKongCalledFrom.fill(-1)
        meldCount = 0
        activeConcealedTileCount = -1
    }

    fun copy(): PlayerState {
        val other = PlayerState()
        other.copyFrom(this)
        return other
    }

    /** Overwrite this instance from [source] (no allocations). */
    fun copyFrom(source: PlayerState) {
        System.arraycopy(source.concealed, 0, concealed, 0, NUM_TILE_TYPES)
        System.arraycopy(source.openChows, 0, openChows, 0, NUM_CHOW_TYPES)
        System.arraycopy(source.pungsKongs, 0, pungsKongs, 0, NUM_TILE_TYPES)
        System.arraycopy(source.pungKongCalledFrom, 0, pungKongCalledFrom, 0, NUM_TILE_TYPES)
        System.arraycopy(source.meldKind, 0, meldKind, 0, MAX_OPEN_MELDS)
        System.arraycopy(source.meldValue, 0, meldValue, 0, MAX_OPEN_MELDS)
        System.arraycopy(source.meldClaimTile, 0, meldClaimTile, 0, MAX_OPEN_MELDS)
        System.arraycopy(source.meldFrom, 0, meldFrom, 0, MAX_OPEN_MELDS)
        meldCount = source.meldCount
        System.arraycopy(source.activeConcealedTiles, 0, activeConcealedTiles, 0, NUM_TILE_TYPES)
        activeConcealedTileCount = source.activeConcealedTileCount
    }

    fun ensureActiveConcealedTiles() {
        if (activeConcealedTileCount >= 0) return
        rebuildActiveConcealedTiles()
    }

    fun onConcealedCountChanged(tile: Int, oldCount: Int, newCount: Int) {
        require(tile in 0 until NUM_TILE_TYPES)

        if (activeConcealedTileCount < 0 || oldCount == newCount) return

        if (oldCount == 0 && newCount > 0) {
            activeConcealedTiles[activeConcealedTileCount++] = tile
            return
        }

        if (oldCount > 0 && newCount == 0) {
            for (index in 0 until activeConcealedTileCount) {
                if (activeConcealedTiles[index] != tile) continue
                activeConcealedTiles[index] = activeConcealedTiles[activeConcealedTileCount - 1]
                --activeConcealedTileCount
                return
            }
            error("Expected tile $tile in active concealed tile cache")
        }
    }

    private fun rebuildActiveConcealedTiles() {
        activeConcealedTileCount = 0
        for (tile in 0 until NUM_TILE_TYPES) {
            if (concealed[tile] > 0) {
                activeConcealedTiles[activeConcealedTileCount++] = tile
            }
        }
    }

    companion object {
        const val PK_NONE = 0
        const val PK_OPEN_PUNG = 1
        const val PK_OPEN_KONG = 2
        const val PK_CONCEALED_KONG = 3

        /** A chow in the exposure log; [meldValue] is the chow index. */
        const val MELD_CHOW = 0

        /** A pung or kong; [meldValue] is the tile type, its kind in [PlayerState.pungsKongs]. */
        const val MELD_SET = 1

        /** At most four melds in a regular hand. */
        const val MAX_OPEN_MELDS = 4
    }
}
