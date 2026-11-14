package inego.zungjunggame.session.table

import inego.zungjunggame.engine.NUM_CHOW_TYPES
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState

data class MeldTile(
    val tileType: Int,
    val open: Boolean,
    /** True → draw rotated 90°; layout uses swapped w/h. */
    val rotated: Boolean,
)

data class MeldGroup(val tiles: List<MeldTile>)

/**
 * Expands open melds and concealed kongs for [ownerSeat].
 *
 * The melds are listed in exposure order ([PlayerState.meldKind]/[PlayerState.meldValue]);
 * a set's current kind (pung, open kong, concealed kong) is read from
 * `pungsKongs`, so a promoted kong keeps its slot and position.
 *
 * Call direction is relative to the owner in turn order (same as screen seats
 * when the human is bottom): +1 = right, +2 = opposite, +3 = left.
 */
fun expandMelds(player: PlayerState, ownerSeat: Int = 0): List<MeldGroup> {
    val groups = ArrayList<MeldGroup>(player.meldCount)

    for (i in 0 until player.meldCount) {
        when (player.meldKind[i]) {
            PlayerState.MELD_CHOW -> {
                val chowIndex = player.meldValue[i]
                val claimed = player.meldClaimTile[i]
                val from = player.meldFrom[i]
                val first = chowFirstTile(chowIndex)
                val rel = relativeSeat(ownerSeat, from)
                groups += MeldGroup(buildOpenChowTiles(first, claimed, rel))
            }
            PlayerState.MELD_SET -> {
                val tile = player.meldValue[i]
                val from = player.pungKongCalledFrom[tile]
                val rel = if (from >= 0) relativeSeat(ownerSeat, from) else 3
                when (player.pungsKongs[tile]) {
                    PlayerState.PK_OPEN_PUNG ->
                        groups += MeldGroup(buildOpenSetTiles(tile, n = 3, relativeFrom = rel))
                    PlayerState.PK_OPEN_KONG ->
                        groups += MeldGroup(buildOpenSetTiles(tile, n = 4, relativeFrom = rel))
                    PlayerState.PK_CONCEALED_KONG ->
                        groups += MeldGroup(buildConcealedKongTiles(tile))
                    else -> error("meld $i set of tile $tile has no pungsKongs state")
                }
            }
            else -> error("unexpected meld kind ${player.meldKind[i]} at slot $i")
        }
    }

    return groups
}

/**
 * Reconstruct a [PlayerState] from a visible [SeatView] (own seat or terminal reveal).
 * Populates concealed tiles (including parked drawn tile if present) and melds, in the
 * meld-list order the view carries. Returns null if [SeatView.concealedTiles] is null.
 */
fun SeatView.toPlayerState(): PlayerState? {
    val concealed = concealedTiles ?: return null
    val ps = PlayerState()
    for (t in concealed) {
        if (t in 0 until NUM_TILE_TYPES) {
            ps.concealed[t]++
        }
    }
    if (hasDrawnTile && drawnTile in 0 until NUM_TILE_TYPES) {
        ps.concealed[drawnTile]++
    }
    for ((tiles) in melds) {
        // A view with more melds than a hand can hold is malformed; record what fits.
        if (ps.meldCount >= PlayerState.MAX_OPEN_MELDS) break
        if (tiles.size == 3) {
            val t0 = tiles[0].tileType
            val t1 = tiles[1].tileType
            val t2 = tiles[2].tileType
            if (t0 == t1 && t1 == t2 && t0 in 0 until NUM_TILE_TYPES) {
                ps.recordMeldExposure(PlayerState.MELD_SET, t0, setState = PlayerState.PK_OPEN_PUNG)
            } else if (t0 in 0 until 27 && t1 in 0 until 27 && t2 in 0 until 27) {
                val minTile = minOf(t0, minOf(t1, t2))
                val chowIndex = (minTile / 9) * 7 + (minTile % 9)
                if (chowIndex in 0 until NUM_CHOW_TYPES) {
                    ps.recordMeldExposure(PlayerState.MELD_CHOW, chowIndex)
                }
            }
        } else if (tiles.size == 4) {
            val t0 = tiles[0].tileType
            if (t0 in 0 until NUM_TILE_TYPES) {
                val allOpen = tiles.all { it.open }
                if (allOpen) {
                    ps.recordMeldExposure(PlayerState.MELD_SET, t0, setState = PlayerState.PK_OPEN_KONG)
                } else {
                    ps.recordMeldExposure(PlayerState.MELD_SET, t0, setState = PlayerState.PK_CONCEALED_KONG)
                }
            }
        }
    }
    return ps
}

/**
 * Relative offset of [otherSeat] from [ownerSeat] in turn order:
 * 1 = next (right of bottom seat), 2 = opposite, 3 = previous (left).
 */
fun relativeSeat(ownerSeat: Int, otherSeat: Int): Int =
    ((otherSeat - ownerSeat) % NUM_PLAYERS + NUM_PLAYERS) % NUM_PLAYERS

/**
 * Index of the sideways claim tile within a meld of size [n], from the
 * owner's view (left → 0, opposite → middle, right → last).
 */
fun sidewaysIndex(relativeFrom: Int, n: Int): Int {
    require(n >= 1)
    return when (relativeFrom) {
        3 -> 0              // from left
        2 -> (n - 1) / 2    // from opposite
        1 -> n - 1          // from right
        else -> 0
    }
}

/** Concealed kong: outer tiles closed, middle two open; none rotated. */
fun buildConcealedKongTiles(tile: Int): List<MeldTile> = listOf(
    MeldTile(tile, open = false, rotated = false),
    MeldTile(tile, open = true, rotated = false),
    MeldTile(tile, open = true, rotated = false),
    MeldTile(tile, open = false, rotated = false),
)

/**
 * Open pung/kong of identical [tile]: one sideways tile points at the discarder.
 */
fun buildOpenSetTiles(tile: Int, n: Int, relativeFrom: Int): List<MeldTile> {
    val side = sidewaysIndex(relativeFrom, n)
    return List(n) { i ->
        MeldTile(tile, open = true, rotated = i == side)
    }
}

/**
 * Open chow: claimed discard is the sideways tile, placed on the end that
 * faces the discarder; remaining sequence tiles fill the other slots in order.
 */
fun buildOpenChowTiles(first: Int, claimedTile: Int, relativeFrom: Int): List<MeldTile> {
    val seq = intArrayOf(first, first + 1, first + 2)
    require(claimedTile in seq[0]..seq[2]) {
        "claimed tile $claimedTile not in chow $first-${first + 2}"
    }
    val side = sidewaysIndex(relativeFrom, 3)
    val others = seq.filter { it != claimedTile }
    val ordered = IntArray(3)
    ordered[side] = claimedTile
    var oi = 0
    for (i in 0 until 3) {
        if (i == side) continue
        ordered[i] = others[oi++]
    }
    return List(3) { i ->
        MeldTile(ordered[i], open = true, rotated = i == side)
    }
}

/** Chow index 0..20 → first tile type of the chow (same as engine). */
fun chowFirstTile(chowIndex: Int): Int {
    require(chowIndex in 0 until NUM_CHOW_TYPES)
    val suit = chowIndex / 7
    val pos = chowIndex % 7
    return suit * 9 + pos
}
