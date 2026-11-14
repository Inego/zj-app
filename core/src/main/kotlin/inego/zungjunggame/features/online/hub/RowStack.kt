package inego.zungjunggame.features.online.hub

/**
 * How much air a row needs where it meets its neighbours.
 *
 * Ordered by strength, so the stronger of two neighbours sets the boundary:
 * [Tight] keeps a dense list reading as one column, [Line] continues the rhythm
 * of the current group, [Section] ends one block of content and opens the next,
 * and [Block] marks a change of kind — text to controls, settings to the action
 * they start.
 */
internal enum class HubGap {
    Tight,
    Line,
    Section,
    Block,
}

/**
 * A top-down stack of rows that owns the space *between* them.
 *
 * The reason this exists rather than a `gapAfter` field on each row: a gap
 * belongs to the boundary between two rows, but stored on a row it is owned by
 * whichever row happens to come first. Reordering then silently reassigns the
 * gaps on both sides of the row that moved, and nothing reports it.
 *
 * Here each row states what it needs on its own terms — [HubStack.add]'s `lead`
 * and `trail` — and a boundary resolves to the stronger of the two neighbours.
 * A row carries its rhythm with it when it moves, and a neighbour that disagrees
 * wins rather than being overridden.
 *
 * Both hub panels lay out this way, so their block rhythm stays identical.
 */
internal class HubStack<R>(
    private val tightGap: Float,
    private val lineGap: Float,
    private val sectionGap: Float,
    private val blockGap: Float,
) {
    private class Item<R>(
        val row: R,
        val height: Float,
        val lead: HubGap,
        val trail: HubGap,
    )

    /** A row with the top and bottom edges it was laid out at. */
    data class Placed<R>(val row: R, val top: Float, val bottom: Float)

    private val items = ArrayList<Item<R>>(16)

    /**
     * Append a row of [height].
     *
     * [lead] is the separation the row needs above itself and [trail] the
     * separation it leaves below — both statements about what the row *is*, so
     * they stay with it when rows are reordered. The first row's `lead` is
     * ignored: there is nothing above it to separate from.
     */
    fun add(
        row: R,
        height: Float,
        lead: HubGap = HubGap.Line,
        trail: HubGap = HubGap.Line,
    ) {
        items += Item(row, height, lead, trail)
    }

    fun place(topY: Float): List<Placed<R>> {
        val placed = ArrayList<Placed<R>>(items.size)
        var y = topY
        for ((i, item) in items.withIndex()) {
            if (i > 0) y -= gapAbove(i)
            placed += Placed(item.row, top = y, bottom = y - item.height)
            y -= item.height
        }
        return placed
    }

    /** Total height the stack occupies, including every internal boundary. */
    val height: Float
        get() {
            var total = 0f
            for ((i, item) in items.withIndex()) {
                if (i > 0) total += gapAbove(i)
                total += item.height
            }
            return total
        }

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * The space between row `i - 1` and row `i`: the stronger of what the row
     * above leaves and what the row below asks for. Either side may raise the
     * boundary to a block, and neither can lower it behind the other's back.
     */
    private fun gapAbove(i: Int): Float {
        val above = items[i - 1]
        val below = items[i]
        val stronger = if (above.trail.ordinal >= below.lead.ordinal) above.trail else below.lead
        return when (stronger) {
            HubGap.Tight -> tightGap
            HubGap.Line -> lineGap
            HubGap.Section -> sectionGap
            HubGap.Block -> blockGap
        }
    }
}
