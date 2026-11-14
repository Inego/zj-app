package inego.zungjunggame.engine

/** Hand-shape decomposition utilities for regular and special hands. */
object HuCheck {
    enum class SetType {
        Pung,
        Chow,
    }

    /**
     * A concealed set extracted during decomposition.
     * - For pung: [baseTile] is the tile type of the pung.
     * - For chow: [baseTile] is the leftmost tile of the chow.
     */
    data class ConcealedSet(
        val type: SetType = SetType.Pung,
        val baseTile: Int = -1,
    )

    data class DecompositionTraversal(
        val onSetEnter: ((ConcealedSet) -> Unit)? = null,
        val onSetExit: ((ConcealedSet) -> Unit)? = null,
        val onLeaf: ((sets: List<ConcealedSet>, pairTile: Int) -> Unit)? = null,
    )


    fun traverseDecompositions(
        counts: IntArray,
        setsNeeded: Int,
        traversal: DecompositionTraversal,
    ): Boolean {
        if (setsNeeded !in 0..4) return false
        var found = false
        val working = counts.copyOf()
        val currentSets = ArrayList<ConcealedSet>(setsNeeded)
        var pairTile = -1

        collectDecompositions(
            working,
            pos = 0,
            setsNeeded = setsNeeded,
            needPair = true,
            currentSets = currentSets,
            getPair = { pairTile },
            setPair = { pairTile = it },
            onSetEnter = { set -> traversal.onSetEnter?.invoke(set) },
            onSetExit = { set -> traversal.onSetExit?.invoke(set) },
            onLeaf = {
                found = true
                traversal.onLeaf?.invoke(currentSets, pairTile)
            },
        )
        return found
    }

    fun canDecompose(counts: IntArray, setsNeeded: Int): Boolean {
        if (setsNeeded !in 0..4) return false
        var found = false
        val working = counts.copyOf()
        val currentSets = ArrayList<ConcealedSet>(setsNeeded)
        var pairTile = -1

        collectDecompositions(
            working,
            pos = 0,
            setsNeeded = setsNeeded,
            needPair = true,
            currentSets = currentSets,
            getPair = { pairTile },
            setPair = { pairTile = it },
            onSetEnter = {},
            onSetExit = {},
            onLeaf = {
                found = true
            },
            stopWhenFound = { found },
        )
        return found
    }

    fun isSevenPairsHand(counts: IntArray): Boolean {
        var pairCount = 0
        for (count in counts) {
            if (count == 0) continue
            when (count) {
                2 -> ++pairCount
                4 -> pairCount += 2
                else -> return false
            }
        }
        return pairCount == 7
    }

    /**
     * Thirteen Terminals: each of the 13 terminal/honor types has count 1 or 2,
     * and exactly one pair.
     */
    fun isThirteenTerminalsHand(counts: IntArray): Boolean {
        var excessPairs = 0

        fun check(tile: Int): Boolean {
            val n = counts[tile]
            if ((n - 1).toUInt() > 1u) return false
            excessPairs += n - 1
            return true
        }

        if (!check(0)) return false // CHAR_1
        if (!check(8)) return false // CHAR_9
        if (!check(9)) return false // DOT_1
        if (!check(17)) return false // DOT_9
        if (!check(18)) return false // BAMBOO_1
        if (!check(26)) return false // BAMBOO_9
        if (!check(27)) return false // EAST
        if (!check(28)) return false // SOUTH
        if (!check(29)) return false // WEST
        if (!check(30)) return false // NORTH
        if (!check(31)) return false // RED
        if (!check(32)) return false // GREEN
        if (!check(33)) return false // WHITE

        return excessPairs == 1
    }

    /**
     * Nine Gates completed-hand shape in one suit:
     * base form 1112345678999 plus exactly one extra tile of the same suit.
     * [suit] must be 0 (chars), 1 (dots), or 2 (bamboos).
     */
    fun isNineGatesSuit(counts: IntArray, suit: Int): Boolean {
        if (suit !in 0..2) return false
        val base = suit * 9
        var extras = 0

        val n1 = counts[base]
        if ((n1 - 3).toUInt() > 1u) return false
        extras += n1 - 3

        for (rank in 1..7) {
            val n = counts[base + rank]
            if ((n - 1).toUInt() > 1u) return false
            extras += n - 1
        }

        val n9 = counts[base + 8]
        if ((n9 - 3).toUInt() > 1u) return false
        extras += n9 - 3

        return extras == 1
    }

    private fun isSuited(tile: Int): Boolean = tile < 27
    private fun suitPos(tile: Int): Int = tile % 9

    private fun collectDecompositions(
        counts: IntArray,
        pos: Int,
        setsNeeded: Int,
        needPair: Boolean,
        currentSets: ArrayList<ConcealedSet>,
        getPair: () -> Int,
        setPair: (Int) -> Unit,
        onSetEnter: (ConcealedSet) -> Unit,
        onSetExit: (ConcealedSet) -> Unit,
        onLeaf: () -> Unit,
        stopWhenFound: () -> Boolean = { false },
    ) {
        var p = pos
        while (p < NUM_TILE_TYPES && counts[p] == 0) ++p

        if (p == NUM_TILE_TYPES) {
            if (setsNeeded == 0 && !needPair) onLeaf()
            return
        }

        if (stopWhenFound()) return

        if (needPair && counts[p] >= 2) {
            counts[p] -= 2
            setPair(p)
            collectDecompositions(
                counts, p, setsNeeded, false, currentSets,
                getPair, setPair, onSetEnter, onSetExit, onLeaf, stopWhenFound,
            )
            setPair(-1)
            counts[p] += 2
            if (stopWhenFound()) return
        }

        if (setsNeeded > 0) {
            if (counts[p] >= 3) {
                val set = ConcealedSet(SetType.Pung, p)
                counts[p] -= 3
                currentSets.add(set)
                onSetEnter(set)
                collectDecompositions(
                    counts, p, setsNeeded - 1, needPair, currentSets,
                    getPair, setPair, onSetEnter, onSetExit, onLeaf, stopWhenFound,
                )
                onSetExit(set)
                currentSets.removeAt(currentSets.lastIndex)
                counts[p] += 3
                if (stopWhenFound()) return
            }

            if (isSuited(p) && suitPos(p) <= 6 &&
                counts[p + 1] >= 1 && counts[p + 2] >= 1
            ) {
                val set = ConcealedSet(SetType.Chow, p)
                --counts[p]
                --counts[p + 1]
                --counts[p + 2]
                currentSets.add(set)
                onSetEnter(set)
                collectDecompositions(
                    counts, p, setsNeeded - 1, needPair, currentSets,
                    getPair, setPair, onSetEnter, onSetExit, onLeaf, stopWhenFound,
                )
                onSetExit(set)
                currentSets.removeAt(currentSets.lastIndex)
                ++counts[p]
                ++counts[p + 1]
                ++counts[p + 2]
            }
        }
    }
}
