package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.platform.newRandom
import kotlin.random.Random

/**
 * Portable, durable wall-build deal for one lesson run.
 *
 * This is the **single source of truth** for which tiles sit where after the wall
 * is formed: dice break count + slot→tile-index permutation. It survives coach
 * suspend, same-lesson Back/Next, process death (via tutorial session snapshot),
 * and is what board-overlay handoff re-materializes from.
 *
 * Not tied to a specific coach body key — any post-deal beat (or board overlay)
 * must reinstall the same deal rather than inventing a new random one.
 */
data class WallBuildDeal(
    /** First d6 face (1..6). */
    val faceA: Int,
    /** Second d6 face (1..6). */
    val faceB: Int,
    /**
     * Formed-wall slot id → diagram tile index (permutation of `0 until WALL_SIZE`).
     * Tile index `i` has fixed type `i / 4` (four copies of each face).
     */
    val slotToTile: IntArray,
) {
    init {
        require(faceA in 1..6 && faceB in 1..6) {
            "dice faces must be 1..6 (got $faceA, $faceB)"
        }
        require(slotToTile.size == WALL_SIZE) {
            "wall mapping size ${slotToTile.size}, expected $WALL_SIZE"
        }
        val seen = BooleanArray(WALL_SIZE)
        for (v in slotToTile) {
            require(v in 0 until WALL_SIZE && !seen[v]) {
                "wall mapping must be a permutation of 0..${WALL_SIZE - 1}"
            }
            seen[v] = true
        }
    }

    /** Dice sum coerced to a legal break stack count (2..12). */
    val stackCount: Int
        get() = (faceA + faceB).coerceIn(2, 12)

    fun copyDeep(): WallBuildDeal = WallBuildDeal(
        faceA = faceA,
        faceB = faceB,
        slotToTile = slotToTile.copyOf(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallBuildDeal) return false
        return faceA == other.faceA &&
            faceB == other.faceB &&
            slotToTile.contentEquals(other.slotToTile)
    }

    override fun hashCode(): Int {
        var h = 31 * faceA + faceB
        h = 31 * h + slotToTile.contentHashCode()
        return h
    }

    companion object {
        /** Fresh dice + shuffled slot map. Used when no committed deal exists. */
        fun invent(rng: Random = newRandom()): WallBuildDeal {
            val faceA = rng.nextInt(1, 7)
            val faceB = rng.nextInt(1, 7)
            val perm = IntArray(WALL_SIZE) { it }
            for (i in WALL_SIZE - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = perm[i]
                perm[i] = perm[j]
                perm[j] = tmp
            }
            return WallBuildDeal(faceA = faceA, faceB = faceB, slotToTile = perm)
        }
    }
}
