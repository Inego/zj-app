package inego.zungjunggame.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Zero-boxing open-addressed `Long -> Int` cache for per-decision hand deficiency
 * lookups (replaces `HashMap<Long, Int>` in hot engine ranking loops).
 *
 * Uses a generation stamp so [clear] is O(1) with zero allocations.
 * Not thread-safe: owned by a single engine instance / thread.
 */
class DeficiencyCache(initialCapacity: Int = 1024) {
    private var mask: Int
    private var keys: LongArray
    private var values: IntArray
    private var stamps: IntArray
    private var generation: Int = 1
    private var count: Int = 0
    private var maxCount: Int

    init {
        var cap = 16
        while (cap < initialCapacity) cap = cap shl 1
        mask = cap - 1
        keys = LongArray(cap)
        values = IntArray(cap)
        stamps = IntArray(cap)
        maxCount = (cap * 2) / 3
    }

    fun clear() {
        generation++
        count = 0
        if (generation == 0) {
            stamps.fill(0)
            generation = 1
        }
    }

    fun getOrMissing(key: Long): Int {
        var slot = mix64(key) and mask
        val gen = generation
        while (true) {
            if (stamps[slot] != gen) return MISSING
            if (keys[slot] == key) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    fun put(key: Long, value: Int) {
        if (count >= maxCount) grow()
        var slot = mix64(key) and mask
        val gen = generation
        while (stamps[slot] == gen) {
            if (keys[slot] == key) {
                values[slot] = value
                return
            }
            slot = (slot + 1) and mask
        }
        stamps[slot] = gen
        keys[slot] = key
        values[slot] = value
        count++
    }

    private fun grow() {
        val oldCap = keys.size
        val newCap = oldCap shl 1
        val newMask = newCap - 1
        val newKeys = LongArray(newCap)
        val newValues = IntArray(newCap)
        val newStamps = IntArray(newCap)
        val gen = generation
        for (i in 0 until oldCap) {
            if (stamps[i] != gen) continue
            val k = keys[i]
            var slot = mix64(k) and newMask
            while (newStamps[slot] == 1) {
                slot = (slot + 1) and newMask
            }
            newStamps[slot] = 1
            newKeys[slot] = k
            newValues[slot] = values[i]
        }
        keys = newKeys
        values = newValues
        stamps = newStamps
        mask = newMask
        generation = 1
        maxCount = (newCap * 2) / 3
    }

    companion object {
        const val MISSING: Int = Int.MIN_VALUE

        private fun mix64(k: Long): Int {
            val x = k xor (k ushr 33)
            return (x xor (x ushr 17)).toInt()
        }
    }
}

/**
 * Faster regular-hand deficiency (suit-wise enumeration) and cached acceptance.
 *
 * Ported from zj-self-play `engine/deficiency_fast.*`.
 *
 * Hot path for MC / Omniscient playouts: every engine [rank] decision.
 * Suit/honor Pareto frontiers are cached as packed primitive [IntArray]s in
 * lock-free-on-hit [IntPartCache] tables so cache hits perform zero heap
 * allocations, zero boxing, and zero [ThreadLocal] lookups.
 */
object DeficiencyFast {
    /**
     * Lock-free-on-hit primitive `Int -> IntArray` cache.
     *
     * Entries are immutable ([val] fields guarantee JMM safe publication of
     * [packed] contents on ARM64/x86 without locking on reads); table resizes
     * publish a fresh [Table] snapshot via [@Volatile].
     */
    private class IntPartCache(initialCapacity: Int) {
        class Entry(val key: Int, val packed: IntArray)
        class Table(val entries: Array<Entry?>, val mask: Int)

        @Volatile
        private var table: Table = Table(arrayOfNulls(initialCapacity), initialCapacity - 1)
        private var size: Int = 0

        fun get(key: Int): IntArray? {
            val t = table
            var slot = mix32(key) and t.mask
            while (true) {
                val e = t.entries[slot] ?: return null
                if (e.key == key) return e.packed
                slot = (slot + 1) and t.mask
            }
        }

        fun getOrPut(key: Int, compute: () -> IntArray): IntArray {
            get(key)?.let { return it }
            synchronized(this) {
                get(key)?.let { return it }
                val packed = compute()
                val entry = Entry(key, packed)
                var t = table
                if ((size + 1) * 3 >= t.entries.size * 2) {
                    val newCap = t.entries.size shl 1
                    val newMask = newCap - 1
                    val newEntries = arrayOfNulls<Entry>(newCap)
                    for (old in t.entries) {
                        if (old == null) continue
                        var s = mix32(old.key) and newMask
                        while (newEntries[s] != null) s = (s + 1) and newMask
                        newEntries[s] = old
                    }
                    var s = mix32(key) and newMask
                    while (newEntries[s] != null) s = (s + 1) and newMask
                    newEntries[s] = entry
                    size++
                    table = Table(newEntries, newMask)
                } else {
                    var s = mix32(key) and t.mask
                    while (t.entries[s] != null) s = (s + 1) and t.mask
                    t.entries[s] = entry
                    size++
                }
                return packed
            }
        }

        private fun mix32(k: Int): Int {
            var h = k
            h = h xor (h ushr 16)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            return h
        }
    }

    private class IntBuf(initialCap: Int) {
        var arr: IntArray = IntArray(initialCap)
        var size: Int = 0

        fun add(v: Int) {
            if (size == arr.size) {
                arr = arr.copyOf(arr.size shl 1)
            }
            arr[size++] = v
        }

        fun toIntArray(): IntArray = arr.copyOf(size)
    }

    private val suitPartCache = IntPartCache(4096)
    private val honorPartCache = IntPartCache(1024)

    /** Per-thread reusable buffers for acceptance sweeps. */
    private class Scratch {
        val tryTile = BooleanArray(NUM_TILE_TYPES)
        val trialHand = PlayerState()
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    private fun packPart(melds: Int, tatsu: Int, heads: Int): Int =
        (melds shl 16) or (tatsu shl 8) or heads

    private fun partMelds(p: Int): Int = p ushr 16
    private fun partTatsu(p: Int): Int = (p ushr 8) and 0xFF
    private fun partHeads(p: Int): Int = p and 0xFF

    fun hashConcealed(concealed: IntArray): Long {
        var h = -3750763034362895579L // FNV offset basis 14695981039346656037 as signed
        for (t in 0 until NUM_TILE_TYPES) {
            h = h xor (concealed[t] + 1).toLong()
            h *= 1099511628211L
        }
        return h
    }

    fun regularDeficiencyFast(countsIn: IntArray, meldsNeededIn: Int): Int {
        val meldsNeeded = if (meldsNeededIn < 0) 0 else meldsNeededIn

        if (meldsNeeded == 0) {
            var total = 0
            var pairTypes = 0
            for (t in 0 until NUM_TILE_TYPES) {
                val c = countsIn[t]
                total += c
                if (c >= 2) ++pairTypes
            }
            if (total == 2 && pairTypes == 1) return -1
            if (total <= 1) return if (total == 1) 0 else 1
            return max(0, total - 2) + if (pairTypes > 0) 0 else 1
        }

        if (HuCheck.canDecompose(countsIn, meldsNeeded)) return -1
        return regularFromParts(countsIn, meldsNeeded)
    }

    fun handDeficiencyFast(player: PlayerState): Int {
        val open = Deficiency.countOpenMelds(player)
        val meldsNeeded = max(0, 4 - open)
        var best = regularDeficiencyFast(player.concealed, meldsNeeded)
        if (open == 0) {
            best = min(best, Deficiency.sevenPairsDeficiency(player.concealed))
            best = min(best, Deficiency.thirteenTerminalsDeficiency(player.concealed))
        }
        return best
    }

    fun handDeficiencyCached(
        player: PlayerState,
        defCache: MutableMap<Long, Int>?,
    ): Int {
        if (defCache == null) return handDeficiencyFast(player)

        val key = hashConcealed(player.concealed) xor
            (Deficiency.countOpenMelds(player).toLong() shl 56)
        defCache[key]?.let { return it }
        val value = handDeficiencyFast(player)
        defCache[key] = value
        return value
    }

    fun handDeficiencyCached(
        player: PlayerState,
        defCache: DeficiencyCache?,
    ): Int {
        if (defCache == null) return handDeficiencyFast(player)

        val key = hashConcealed(player.concealed) xor
            (Deficiency.countOpenMelds(player).toLong() shl 56)
        val cached = defCache.getOrMissing(key)
        if (cached != DeficiencyCache.MISSING) return cached
        val value = handDeficiencyFast(player)
        defCache.put(key, value)
        return value
    }

    /**
     * Fill [out] with the tile types that could reduce [player]'s deficiency given
     * [remaining] (the per-seat unseen count). The candidate walk is the same one
     * [acceptanceCountFast] uses, without adding each candidate; [out] is copied
     * out of the shared scratch so the caller may keep it across calls.
     */
    fun acceptanceTiles(
        player: PlayerState,
        remaining: IntArray,
        baseDeficiency: Int,
        out: BooleanArray,
    ) {
        if (baseDeficiency < 0) {
            out.fill(false)
            return
        }
        val s = scratch.get()
        collectAcceptanceTiles(player, remaining, baseDeficiency, s.tryTile)
        System.arraycopy(s.tryTile, 0, out, 0, NUM_TILE_TYPES)
    }

    fun acceptanceCountFast(
        player: PlayerState,
        remaining: IntArray,
        baseDeficiency: Int,
        defCache: MutableMap<Long, Int>? = null,
    ): Int {
        if (baseDeficiency < 0) return 0

        val s = scratch.get()
        val tryTile = s.tryTile
        collectAcceptanceTiles(player, remaining, baseDeficiency, tryTile)

        var total = 0
        val trial = s.trialHand
        trial.copyFrom(player)
        for (tile in 0 until NUM_TILE_TYPES) {
            if (!tryTile[tile]) continue
            val avail = remaining[tile]
            if (avail <= 0) continue
            ++trial.concealed[tile]
            trial.activeConcealedTileCount = -1
            val next = handDeficiencyCached(trial, defCache)
            --trial.concealed[tile]
            if (next < baseDeficiency) total += avail
        }
        trial.activeConcealedTileCount = -1
        return total
    }

    fun acceptanceCountFast(
        player: PlayerState,
        remaining: IntArray,
        baseDeficiency: Int,
        defCache: DeficiencyCache?,
    ): Int {
        if (baseDeficiency < 0) return 0

        val s = scratch.get()
        val tryTile = s.tryTile
        collectAcceptanceTiles(player, remaining, baseDeficiency, tryTile)

        var total = 0
        val trial = s.trialHand
        trial.copyFrom(player)
        for (tile in 0 until NUM_TILE_TYPES) {
            if (!tryTile[tile]) continue
            val avail = remaining[tile]
            if (avail <= 0) continue
            ++trial.concealed[tile]
            trial.activeConcealedTileCount = -1
            val next = handDeficiencyCached(trial, defCache)
            --trial.concealed[tile]
            if (next < baseDeficiency) total += avail
        }
        trial.activeConcealedTileCount = -1
        return total
    }

    // --- private --------------------------------------------------------

    private fun pushPartPareto(buf: IntBuf, melds: Int, tatsu: Int, heads: Int) {
        val packed = packPart(melds, tatsu, heads)
        var n = buf.size
        val a = buf.arr
        var i = 0
        while (i < n) {
            val e = a[i]
            val em = partMelds(e)
            val et = partTatsu(e)
            val eh = partHeads(e)
            if (em >= melds && et >= tatsu && eh >= heads) return
            if (melds >= em && tatsu >= et && heads >= eh) {
                a[i] = a[--n]
                continue
            }
            i++
        }
        buf.size = n
        buf.add(packed)
    }

    private fun suitDfs(
        c: IntArray,
        posIn: Int,
        melds: Int,
        tatsu: Int,
        heads: Int,
        out: IntBuf,
    ) {
        var pos = posIn
        while (pos < 9 && c[pos] == 0) ++pos
        if (pos >= 9) {
            pushPartPareto(out, melds, tatsu, heads)
            return
        }

        if (c[pos] >= 3) {
            c[pos] -= 3
            suitDfs(c, pos, melds + 1, tatsu, heads, out)
            c[pos] += 3
        }

        if (pos <= 6 && c[pos] > 0 && c[pos + 1] > 0 && c[pos + 2] > 0) {
            --c[pos]
            --c[pos + 1]
            --c[pos + 2]
            suitDfs(c, pos, melds + 1, tatsu, heads, out)
            ++c[pos]
            ++c[pos + 1]
            ++c[pos + 2]
        }

        if (c[pos] >= 2) {
            c[pos] -= 2
            suitDfs(c, pos, melds, tatsu, heads + 1, out)
            suitDfs(c, pos, melds, tatsu + 1, heads, out)
            c[pos] += 2
        }

        if (pos <= 7 && c[pos] > 0 && c[pos + 1] > 0) {
            --c[pos]
            --c[pos + 1]
            suitDfs(c, pos, melds, tatsu + 1, heads, out)
            ++c[pos]
            ++c[pos + 1]
        }

        if (pos <= 6 && c[pos] > 0 && c[pos + 2] > 0) {
            --c[pos]
            --c[pos + 2]
            suitDfs(c, pos, melds, tatsu + 1, heads, out)
            ++c[pos]
            ++c[pos + 2]
        }

        --c[pos]
        suitDfs(c, pos, melds, tatsu, heads, out)
        ++c[pos]
    }

    private fun honorDfs(
        c: IntArray,
        posIn: Int,
        melds: Int,
        tatsu: Int,
        heads: Int,
        out: IntBuf,
    ) {
        var pos = posIn
        while (pos < 7 && c[pos] == 0) ++pos
        if (pos >= 7) {
            pushPartPareto(out, melds, tatsu, heads)
            return
        }

        if (c[pos] >= 3) {
            c[pos] -= 3
            honorDfs(c, pos, melds + 1, tatsu, heads, out)
            c[pos] += 3
        }
        if (c[pos] >= 2) {
            c[pos] -= 2
            honorDfs(c, pos, melds, tatsu, heads + 1, out)
            honorDfs(c, pos, melds, tatsu + 1, heads, out)
            c[pos] += 2
        }
        --c[pos]
        honorDfs(c, pos, melds, tatsu, heads, out)
        ++c[pos]
    }

    /** Base-5 pack of 9 suit counts (`0..5^9-1`), or `-1` if any count is outside `0..4`. */
    private fun packSuit9(counts: IntArray, base: Int): Int {
        var p = 0
        for (i in 0 until 9) {
            val c = counts[base + i]
            if (c < 0 || c > 4) return -1
            p = p * 5 + c
        }
        return p
    }

    /** Base-5 pack of 7 honor counts (`0..5^7-1`), or `-1` if any count is outside `0..4`. */
    private fun packHonor7(counts: IntArray, base: Int): Int {
        var p = 0
        for (i in 0 until 7) {
            val c = counts[base + i]
            if (c < 0 || c > 4) return -1
            p = p * 5 + c
        }
        return p
    }

    private fun computeSuitParts(counts: IntArray, base: Int): IntArray {
        val work = IntArray(9) { counts[base + it] }
        val out = IntBuf(16)
        suitDfs(work, 0, 0, 0, 0, out)
        if (out.size == 0) out.add(0)
        return out.toIntArray()
    }

    private fun computeHonorParts(counts: IntArray, base: Int): IntArray {
        val work = IntArray(7) { counts[base + it] }
        val out = IntBuf(8)
        honorDfs(work, 0, 0, 0, 0, out)
        if (out.size == 0) out.add(0)
        return out.toIntArray()
    }

    private fun analyzeSuit(counts: IntArray, base: Int): IntArray {
        val key = packSuit9(counts, base)
        if (key < 0) return computeSuitParts(counts, base)
        suitPartCache.get(key)?.let { return it }
        return suitPartCache.getOrPut(key) { computeSuitParts(counts, base) }
    }

    private fun analyzeHonors(counts: IntArray, base: Int): IntArray {
        val key = packHonor7(counts, base)
        if (key < 0) return computeHonorParts(counts, base)
        honorPartCache.get(key)?.let { return it }
        return honorPartCache.getOrPut(key) { computeHonorParts(counts, base) }
    }

    private fun scoreParts(melds: Int, tatsuIn: Int, headsIn: Int, meldsNeeded: Int): Int {
        var h = headsIn
        var t = tatsuIn
        if (h > 1) {
            t += h - 1
            h = 1
        }
        val hasPair = h > 0
        var complete = melds
        if (complete > meldsNeeded) complete = meldsNeeded
        var usable = t
        if (complete + usable > meldsNeeded) usable = meldsNeeded - complete
        if (usable < 0) usable = 0
        return meldsNeeded * 2 - complete * 2 - usable - if (hasPair) 1 else 0
    }

    private fun regularFromParts(counts: IntArray, meldsNeeded: Int): Int {
        val g0 = analyzeSuit(counts, 0)
        val g1 = analyzeSuit(counts, 9)
        val g2 = analyzeSuit(counts, 18)
        val g3 = analyzeHonors(counts, 27)

        var best = meldsNeeded * 2
        for (i0 in g0.indices) {
            val p0 = g0[i0]
            val m0 = partMelds(p0)
            val t0 = partTatsu(p0)
            val h0 = partHeads(p0)
            for (i1 in g1.indices) {
                val p1 = g1[i1]
                val m1 = m0 + partMelds(p1)
                val t1 = t0 + partTatsu(p1)
                val h1 = h0 + partHeads(p1)
                for (i2 in g2.indices) {
                    val p2 = g2[i2]
                    val m2 = m1 + partMelds(p2)
                    val t2 = t1 + partTatsu(p2)
                    val h2 = h1 + partHeads(p2)
                    for (i3 in g3.indices) {
                        val p3 = g3[i3]
                        val sc = scoreParts(
                            m2 + partMelds(p3),
                            t2 + partTatsu(p3),
                            h2 + partHeads(p3),
                            meldsNeeded,
                        )
                        if (sc < best) {
                            best = sc
                            if (best <= -1) return -1
                        }
                    }
                }
            }
        }
        return best
    }

    private fun collectAcceptanceTiles(
        player: PlayerState,
        remaining: IntArray,
        base: Int,
        tryTile: BooleanArray,
    ) {
        tryTile.fill(false)

        val open = Deficiency.countOpenMelds(player)
        var sp7 = 99
        var sp13 = 99
        if (open == 0) {
            sp7 = Deficiency.sevenPairsDeficiency(player.concealed)
            sp13 = Deficiency.thirteenTerminalsDeficiency(player.concealed)
        }

        for (t in 0 until NUM_TILE_TYPES) {
            if (player.concealed[t] <= 0) continue
            tryTile[t] = true
            if (t < 27) {
                val rank = t % 9
                val baseSuit = t - rank
                if (rank >= 1) tryTile[baseSuit + rank - 1] = true
                if (rank <= 7) tryTile[baseSuit + rank + 1] = true
                if (rank >= 2) tryTile[baseSuit + rank - 2] = true
                if (rank <= 6) tryTile[baseSuit + rank + 2] = true
            }
        }

        if (open == 0 && sp7 <= base + 2) {
            tryTile.fill(true)
        } else if (open == 0 && sp13 <= base + 2) {
            // Fixed terminals set (no per-call allocation).
            tryTile[0] = true
            tryTile[8] = true
            tryTile[9] = true
            tryTile[17] = true
            tryTile[18] = true
            tryTile[26] = true
            for (t in 27 until 34) tryTile[t] = true
        }

        for (t in 0 until NUM_TILE_TYPES) {
            if (remaining[t] <= 0) tryTile[t] = false
        }
    }
}
