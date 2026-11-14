package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.platform.newRandom
import kotlin.random.Random

/**
 * Lesson-scoped durable state for the wall-build diagram.
 *
 * Survives coach [WallBuildView.suspend] (hide, complete panel, other diagrams)
 * and same-lesson Back/Next. Cleared only by [WallBuildView.clearLesson] when
 * the host starts a different lesson, restarts, or leaves the tutorial.
 *
 * Two durable facts, independent of “whatever the last frame happened to show”:
 * - **Dice roll** — break gap / dead wall / deal / count caption.
 * - **Wall slot → tile map** — which diagram tile (face) sits in each formed-wall
 *   slot. Claimed from the shuffle heap when the player builds the wall; otherwise
 *   a full random permutation on first need (cold entry / process restart mid-lesson).
 *   Deal and look-hands read this same map so open East is never identity-sorted.
 */
class WallBuildLessonState {
    var faceA: Int? = null
        private set
    var faceB: Int? = null
        private set

    /**
     * Formed-wall slot id → index in the diagram tile array.
     * Null until [commitWallMapping] or [ensureShuffledWallMapping].
     */
    private var slotToTile: IntArray? = null

    val hasRoll: Boolean
        get() = faceA != null && faceB != null

    val hasWallMapping: Boolean
        get() = slotToTile != null

    /** Dice sum in 2..12 when a roll exists; else 0. */
    val stackCount: Int
        get() {
            val a = faceA ?: return 0
            val b = faceB ?: return 0
            return (a + b).coerceIn(2, 12)
        }

    fun commit(a: Int, b: Int) {
        require(a in 1..6 && b in 1..6) { "dice faces must be 1..6 (got $a, $b)" }
        faceA = a
        faceB = b
    }

    /**
     * Install a full slot→tile permutation (typically from shuffle heap claim).
     * Overwrites any previous mapping for this lesson run.
     */
    fun commitWallMapping(mapping: IntArray) {
        require(mapping.size == WALL_SIZE) {
            "wall mapping size ${mapping.size}, expected $WALL_SIZE"
        }
        val seen = BooleanArray(WALL_SIZE)
        for (v in mapping) {
            require(v in 0 until WALL_SIZE && !seen[v]) {
                "wall mapping must be a permutation of 0..${WALL_SIZE - 1}"
            }
            seen[v] = true
        }
        slotToTile = mapping.copyOf()
    }

    /**
     * Guarantee a wall mapping exists. If none yet, installs a Fisher–Yates
     * shuffle of tile indices (cold mid-lesson entry). Does **not** re-shuffle
     * an existing map — heap claim and cold fill both stick until [clear].
     */
    fun ensureShuffledWallMapping(rng: Random = newRandom()) {
        if (slotToTile != null) return
        val perm = IntArray(WALL_SIZE) { it }
        for (i in WALL_SIZE - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]
            perm[i] = perm[j]
            perm[j] = tmp
        }
        slotToTile = perm
    }

    /** Tile index in slot [slot]. Requires a mapping ([ensureShuffledWallMapping] or commit). */
    fun tileAtSlot(slot: Int): Int {
        val map = slotToTile
        require(map != null) { "wall mapping not established" }
        require(slot in map.indices) { "slot $slot out of range" }
        return map[slot]
    }

    /** Drop dice only (pre-roll beats). Wall mapping is independent. */
    fun clearRoll() {
        faceA = null
        faceB = null
    }

    /** Drop dice and wall mapping (leave / restart lesson). */
    fun clear() {
        clearRoll()
        slotToTile = null
    }

    fun facesOrNull(): Triple<Int, Int, Int>? {
        val a = faceA ?: return null
        val b = faceB ?: return null
        return Triple(a, b, (a + b).coerceIn(2, 12))
    }

    /**
     * Portable deal when both dice and wall map are committed; null otherwise.
     */
    fun exportDeal(): WallBuildDeal? {
        val a = faceA ?: return null
        val b = faceB ?: return null
        val map = slotToTile ?: return null
        return WallBuildDeal(faceA = a, faceB = b, slotToTile = map.copyOf())
    }

    /**
     * Install a previously committed deal (Back / cold restore / host reinstall).
     * Overwrites any current roll and mapping.
     */
    fun installDeal(deal: WallBuildDeal) {
        faceA = deal.faceA
        faceB = deal.faceB
        slotToTile = deal.slotToTile.copyOf()
    }
}
