package inego.zungjunggame.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Faster regular-hand deficiency (suit-wise enumeration) and cached acceptance.
 *
 * Ported from zj-self-play `engine/deficiency_fast.*`.
 *
 * Hot path for MC playouts: every engine [rank] decision. Scratch buffers are
 * [ThreadLocal] so a single worker (or UI thread) reuses arrays/hands without
 * multi-threaded MC; no extra cores are spun up.
 */
object DeficiencyFast {
    private data class Part(val melds: Int = 0, val tatsu: Int = 0, val heads: Int = 0)

    private val suitPartCache = HashMap<Long, List<Part>>()
    private val honorPartCache = HashMap<Long, List<Part>>()

    /** Per-thread reusable buffers (single MC worker + optional UI rank). */
    private class Scratch {
        val counts = IntArray(NUM_TILE_TYPES)
        val chars = IntArray(9)
        val dots = IntArray(9)
        val bams = IntArray(9)
        val honors = IntArray(7)
        val suitWork = IntArray(9)
        val honorWork = IntArray(7)
        val tryTile = BooleanArray(NUM_TILE_TYPES)
        val trialHand = PlayerState()
        @Suppress("UNCHECKED_CAST")
        val partGroups = arrayOfNulls<List<Part>>(4) as Array<List<Part>>
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun hashConcealed(concealed: IntArray): Long {
        var h = -3750763034362895579L // FNV offset basis 14695981039346656037 as signed
        for (t in 0 until NUM_TILE_TYPES) {
            h = h xor (concealed[t] + 1).toLong()
            h *= 1099511628211L
        }
        return h
    }

    fun regularDeficiencyFast(countsIn: IntArray, meldsNeededIn: Int): Int {
        var meldsNeeded = meldsNeededIn
        if (meldsNeeded < 0) meldsNeeded = 0
        val s = scratch.get()
        System.arraycopy(countsIn, 0, s.counts, 0, NUM_TILE_TYPES)
        val counts = s.counts

        if (meldsNeeded == 0) {
            var total = 0
            var pairTypes = 0
            for (t in 0 until NUM_TILE_TYPES) {
                total += counts[t]
                if (counts[t] >= 2) ++pairTypes
            }
            if (total == 2 && pairTypes == 1) return -1
            if (total <= 1) return if (total == 1) 0 else 1
            return max(0, total - 2) + if (pairTypes > 0) 0 else 1
        }

        if (HuCheck.canDecompose(counts, meldsNeeded)) return -1
        return regularFromParts(counts, meldsNeeded, s)
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


    // --- private --------------------------------------------------------

    private fun partDominates(a: Part, b: Part): Boolean =
        a.melds >= b.melds && a.tatsu >= b.tatsu && a.heads >= b.heads

    private fun pushPartPareto(parts: MutableList<Part>, p: Part) {
        for (e in parts) {
            if (partDominates(e, p)) return
        }
        parts.removeAll { e ->
            partDominates(p, e) && !(e.melds == p.melds && e.tatsu == p.tatsu && e.heads == p.heads)
        }
        for ((melds, tatsu, heads) in parts) {
            if (melds == p.melds && tatsu == p.tatsu && heads == p.heads) return
        }
        parts.add(p)
    }

    private fun suitDfs(
        c: IntArray,
        posIn: Int,
        melds: Int,
        tatsu: Int,
        heads: Int,
        out: MutableList<Part>,
    ) {
        var pos = posIn
        while (pos < 9 && c[pos] == 0) ++pos
        if (pos >= 9) {
            pushPartPareto(out, Part(melds, tatsu, heads))
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
        out: MutableList<Part>,
    ) {
        var pos = posIn
        while (pos < 7 && c[pos] == 0) ++pos
        if (pos >= 7) {
            pushPartPareto(out, Part(melds, tatsu, heads))
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

    private fun packSuit9(nine: IntArray): Long {
        var p = 0L
        for (i in 0 until 9) p = p * 5L + nine[i]
        return p
    }

    private fun packHonor7(seven: IntArray): Long {
        var p = 0L
        for (i in 0 until 7) p = p * 5L + seven[i]
        return p
    }

    private fun analyzeSuit(nine: IntArray, work: IntArray): List<Part> {
        val key = packSuit9(nine)
        suitPartCache[key]?.let { return it }

        System.arraycopy(nine, 0, work, 0, 9)
        val out = ArrayList<Part>(32)
        suitDfs(work, 0, 0, 0, 0, out)
        if (out.isEmpty()) out.add(Part())
        suitPartCache[key] = out
        return out
    }

    private fun analyzeHonors(seven: IntArray, work: IntArray): List<Part> {
        val key = packHonor7(seven)
        honorPartCache[key]?.let { return it }

        System.arraycopy(seven, 0, work, 0, 7)
        val out = ArrayList<Part>(16)
        honorDfs(work, 0, 0, 0, 0, out)
        if (out.isEmpty()) out.add(Part())
        honorPartCache[key] = out
        return out
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

    private fun combineRec(
        groups: Array<List<Part>>,
        idx: Int,
        melds: Int,
        tatsu: Int,
        heads: Int,
        meldsNeeded: Int,
        bestHolder: (Int) -> Unit,
        getBest: () -> Int,
    ) {
        if (idx >= 4) {
            bestHolder(min(getBest(), scoreParts(melds, tatsu, heads, meldsNeeded)))
            return
        }
        if (scoreParts(melds + 12, tatsu + 12, heads + 4, meldsNeeded) >= getBest()) return

        for ((melds1, tatsu1, heads1) in groups[idx]) {
            combineRec(
                groups, idx + 1, melds + melds1, tatsu + tatsu1, heads + heads1,
                meldsNeeded, bestHolder, getBest,
            )
            if (getBest() <= -1) return
        }
    }

    private fun regularFromParts(counts: IntArray, meldsNeeded: Int, s: Scratch): Int {
        for (i in 0 until 9) {
            s.chars[i] = counts[i]
            s.dots[i] = counts[9 + i]
            s.bams[i] = counts[18 + i]
        }
        for (i in 0 until 7) {
            s.honors[i] = counts[27 + i]
        }

        s.partGroups[0] = analyzeSuit(s.chars, s.suitWork)
        s.partGroups[1] = analyzeSuit(s.dots, s.suitWork)
        s.partGroups[2] = analyzeSuit(s.bams, s.suitWork)
        s.partGroups[3] = analyzeHonors(s.honors, s.honorWork)

        var best = meldsNeeded * 2
        combineRec(s.partGroups, 0, 0, 0, 0, meldsNeeded, { best = it }, { best })
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
        val meldsNeeded = max(0, 4 - open)
        val reg = regularDeficiencyFast(player.concealed, meldsNeeded)
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

        // silence unused: reg used for parity with C++ structure
        @Suppress("UNUSED_EXPRESSION")
        reg

        for (t in 0 until NUM_TILE_TYPES) {
            if (remaining[t] <= 0) tryTile[t] = false
        }
    }
}
