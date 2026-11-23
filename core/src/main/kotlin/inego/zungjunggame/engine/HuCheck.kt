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
        var s0 = 0
        for (i in 0 until 9) {
            val c = counts[i]
            if (c < 0) return false
            s0 += c
        }
        var s1 = 0
        for (i in 9 until 18) {
            val c = counts[i]
            if (c < 0) return false
            s1 += c
        }
        var s2 = 0
        for (i in 18 until 27) {
            val c = counts[i]
            if (c < 0) return false
            s2 += c
        }
        var s3 = 0
        for (i in 27 until NUM_TILE_TYPES) {
            val c = counts[i]
            if (c < 0) return false
            s3 += c
        }
        val total = s0 + s1 + s2 + s3
        if (total != setsNeeded * 3 + 2) return false

        val m0 = s0 % 3
        val m1 = s1 % 3
        val m2 = s2 % 3
        val m3 = s3 % 3
        if (m0 == 1 || m1 == 1 || m2 == 1 || m3 == 1) return false
        // Exactly one group must hold the pair (sum % 3 == 2).
        var pairGroups = 0
        if (m0 == 2) pairGroups++
        if (m1 == 2) pairGroups++
        if (m2 == 2) pairGroups++
        if (m3 == 2) pairGroups++
        if (pairGroups != 1) return false

        // Honors (no chows): every non-zero tile must be 3 (or 2 once if pair is in honors).
        if (s3 > 0) {
            var pairs = 0
            for (i in 27 until NUM_TILE_TYPES) {
                val c = counts[i]
                if (c == 0) continue
                val rem = c % 3
                if (rem == 1) return false
                if (rem == 2) {
                    pairs++
                    if (pairs > 1) return false
                }
            }
            if ((m3 == 2) != (pairs == 1)) return false
        }

        if (s0 > 0 && !(if (m0 == 2) canDecomposeSuitWithPair(counts, 0) else canDecomposeSuitNoPair(counts, 0))) {
            return false
        }
        if (s1 > 0 && !(if (m1 == 2) canDecomposeSuitWithPair(counts, 9) else canDecomposeSuitNoPair(counts, 9))) {
            return false
        }
        if (s2 > 0 && !(if (m2 == 2) canDecomposeSuitWithPair(counts, 18) else canDecomposeSuitNoPair(counts, 18))) {
            return false
        }
        return true
    }

    /**
     * Exact zero-allocation suit decomposition check without a pair: because 3 identical
     * chows `(r, r+1, r+2)` consume the exact same tiles as 3 pungs, taking `rem % 3`
     * chows starting at the leftmost rank `r` is always optimal.
     */
    private fun canDecomposeSuitNoPair(counts: IntArray, base: Int): Boolean {
        var sub1 = 0
        var sub2 = 0
        for (r in 0 until 9) {
            val rem = counts[base + r] - sub1
            if (rem < 0) return false
            val chows = rem % 3
            if (chows > 0 && r > 6) return false
            sub1 = sub2 + chows
            sub2 = chows
        }
        return sub1 == 0 && sub2 == 0
    }

    private fun canDecomposeSuitWithPair(counts: IntArray, base: Int): Boolean {
        for (pairR in 0 until 9) {
            if (counts[base + pairR] < 2) continue
            var sub1 = 0
            var sub2 = 0
            var ok = true
            for (r in 0 until 9) {
                val c = if (r == pairR) counts[base + r] - 2 else counts[base + r]
                val rem = c - sub1
                if (rem < 0) {
                    ok = false
                    break
                }
                val chows = rem % 3
                if (chows > 0 && r > 6) {
                    ok = false
                    break
                }
                sub1 = sub2 + chows
                sub2 = chows
            }
            if (ok && sub1 == 0 && sub2 == 0) return true
        }
        return false
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
