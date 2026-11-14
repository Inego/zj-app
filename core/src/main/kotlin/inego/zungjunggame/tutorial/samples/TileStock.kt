package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.random.Random

/**
 * The tile stock a hand-built fixture deals from, and the ordered log of what it dealt.
 *
 * A fixture starts from a full set ([NUM_TILE_TYPES] × 4), then takes the human's tiles
 * and an opponent's discards explicitly and the remaining hands at random. [drawn] is
 * the deal in order, so the live wall keeps that prefix and only the tail is shuffled —
 * which is why the first live-wall draws are the same ones the deal already fixed.
 *
 * [takeRandom] weights a pick by how many copies remain, and consumes exactly one [rng]
 * value per pick, so a fixture's `rng` seed fully determines its board.
 */
internal class TileStock(
    private val rng: Random,
    /** How many copies of each tile type remain undealt. */
    private val counts: IntArray = IntArray(NUM_TILE_TYPES) { 4 },
    /** Every tile taken so far, in order. */
    val drawn: ArrayList<Int> = ArrayList(),
) {
    /** Copies of [tile] still undealt. */
    fun remaining(tile: Int): Int = counts[tile]

    /** Take one copy of [tile]; fails when the stock is exhausted. */
    fun take(tile: Int): Int {
        check(counts[tile] > 0) { "tile $tile exhausted" }
        counts[tile]--
        drawn.add(tile)
        return tile
    }

    /**
     * Take a random copy, chosen with probability proportional to what remains, skipping
     * any type with a positive count in [avoid]. Consumes exactly one [rng] value.
     */
    fun takeRandom(avoid: IntArray? = null): Int {
        var total = 0
        for (t in 0 until NUM_TILE_TYPES) {
            if (avoid != null && avoid[t] > 0) continue
            total += counts[t]
        }
        check(total > 0) { "stock empty" }
        var pick = rng.nextInt(total)
        for (t in 0 until NUM_TILE_TYPES) {
            if (avoid != null && avoid[t] > 0) continue
            pick -= counts[t]
            if (pick < 0) return take(t)
        }
        error("takeRandom missed")
    }

    /** Deal [count] tiles at random, returned as a per-type count. */
    fun dealConcealed(count: Int): IntArray {
        val concealed = IntArray(NUM_TILE_TYPES)
        repeat(count) {
            val tile = takeRandom()
            concealed[tile]++
        }
        return concealed
    }

    /**
     * Fill the live wall: the already-drawn tiles keep their order, the remainder is
     * shuffled and appended. [expectedDrawn] guards the caller's own tile accounting.
     */
    fun completeWall(expectedDrawn: Int): IntArray {
        val wall = IntArray(WALL_SIZE)
        check(drawn.size == expectedDrawn) { "drawn ${drawn.size}, expected $expectedDrawn" }
        for (i in drawn.indices) wall[i] = drawn[i]
        var w = drawn.size
        val rest = ArrayList<Int>(WALL_SIZE - drawn.size)
        for (t in 0 until NUM_TILE_TYPES) {
            repeat(counts[t]) { rest.add(t) }
        }
        rest.shuffle(rng)
        for (tile in rest) wall[w++] = tile
        check(w == WALL_SIZE)
        return wall
    }
}