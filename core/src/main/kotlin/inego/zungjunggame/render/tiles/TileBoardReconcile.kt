package inego.zungjunggame.render.tiles

/** Mutable bookkeeping shared by the focused reconcile matching strategies. */
internal class TileBoardReconcileContext(
    val tiles: List<VisualTile>,
    val desired: List<DesiredSlot>,
    val dealStagger: Boolean,
    private val retarget: (VisualTile, DesiredSlot) -> Unit,
) {
    private val usedVisuals = BooleanArray(tiles.size)
    private val usedDesired = BooleanArray(desired.size)

    val visualCount: Int get() = tiles.size
    val desiredCount: Int get() = desired.size
    val hasUnusedVisuals: Boolean get() = usedVisuals.any { !it }

    fun isVisualUsed(index: Int): Boolean = usedVisuals[index]

    fun isDesiredUsed(index: Int): Boolean = usedDesired[index]

    fun use(visualIndex: Int, desiredIndex: Int) {
        usedVisuals[visualIndex] = true
        usedDesired[desiredIndex] = true
        retarget(tiles[visualIndex], desired[desiredIndex])
    }
}

internal interface TileBoardMatchStrategy {
    fun apply(context: TileBoardReconcileContext)
}

/**
 * Applies explicit discard-origin hints before ordinary matching can steal them.
 *
 * A hint must not land on a river slot that already has a sitting occupant
 * (same-type copy already in the river). Unused hints are kept for a later
 * reconcile — online clicks arrive frames before the snapshot grows the river.
 */
internal class PreferredDiscardMatchStrategy(
    private val preferredVisualId: Int,
    private val preferredOrigin: SlotKey?,
) : TileBoardMatchStrategy {
    var visualHintUsed: Boolean = preferredVisualId < 0
        private set
    var originHintUsed: Boolean = preferredOrigin == null
        private set

    override fun apply(context: TileBoardReconcileContext) {
        val matched = matchPreferredVisual(context) || matchPreferredOrigin(context)
        if (matched) {
            visualHintUsed = true
            originHintUsed = true
            return
        }
        if (preferredVisualId >= 0 && context.tiles.none { it.id == preferredVisualId }) {
            visualHintUsed = true
        }
        if (preferredOrigin != null && originVisualIndex(context, unusedOnly = false) == null) {
            originHintUsed = true
        }
    }

    private fun matchPreferredVisual(context: TileBoardReconcileContext): Boolean {
        if (preferredVisualId < 0) return false
        val visualIndex = context.tiles.indexOfFirst { it.id == preferredVisualId }
        if (visualIndex < 0 || context.isVisualUsed(visualIndex)) return false

        val visual = context.tiles[visualIndex]
        if (visual.slotKey.kind == SlotKind.River) return true

        val desiredIndex = context.latestUnoccupiedRiverIndex {
            it.seatIndex == visual.seatIndex && it.tileType == visual.tileType
        }
        if (desiredIndex < 0) return false
        context.use(visualIndex, desiredIndex)
        return true
    }

    private fun matchPreferredOrigin(context: TileBoardReconcileContext): Boolean {
        val origin = preferredOrigin ?: return false
        val visualIndex = originVisualIndex(context, unusedOnly = true) ?: return false
        val desiredIndex = context.latestUnoccupiedRiverIndex { it.seatIndex == origin.seat }
        if (desiredIndex < 0) return false
        context.use(visualIndex, desiredIndex)
        return true
    }

    private fun originVisualIndex(
        context: TileBoardReconcileContext,
        unusedOnly: Boolean,
    ): Int? {
        val origin = preferredOrigin ?: return null
        return context.tiles.indices.firstOrNull { index ->
            if (unusedOnly && context.isVisualUsed(index)) return@firstOrNull false
            val visual = context.tiles[index]
            visual.seatIndex == origin.seat && visual.slotKey == origin
        }
    }
}

/** Keeps a visual attached to its logical slot and reveals concealed tiles in place. */
internal object ExactSlotMatchStrategy : TileBoardMatchStrategy {
    override fun apply(context: TileBoardReconcileContext) {
        for (visualIndex in 0 until context.visualCount) {
            if (context.isVisualUsed(visualIndex)) continue
            val visual = context.tiles[visualIndex]
            val desiredIndex = context.desired.indices.firstOrNull { index ->
                if (context.isDesiredUsed(index)) return@firstOrNull false
                val desired = context.desired[index]
                visual.slotKey == desired.slotKey && visual.tileType == desired.tileType
            }
            if (desiredIndex != null) context.use(visualIndex, desiredIndex)
        }

        for (visualIndex in 0 until context.visualCount) {
            if (context.isVisualUsed(visualIndex)) continue
            val visual = context.tiles[visualIndex]
            if (visual.tileType != -1) continue
            val desiredIndex = context.desired.indices.firstOrNull { index ->
                if (context.isDesiredUsed(index)) return@firstOrNull false
                val desired = context.desired[index]
                visual.slotKey == desired.slotKey &&
                    visual.slotKey.kind in setOf(SlotKind.Hand, SlotKind.Drawn)
            }
            if (desiredIndex != null) context.use(visualIndex, desiredIndex)
        }
    }
}

/** Reuses known tiles by the cheapest same-type movement still available. */
internal object DistanceMatchStrategy : TileBoardMatchStrategy {
    override fun apply(context: TileBoardReconcileContext) {
        val candidates = ArrayList<TileBoardMatchCandidate>(context.visualCount * 2)
        for (visualIndex in 0 until context.visualCount) {
            if (context.isVisualUsed(visualIndex)) continue
            val visual = context.tiles[visualIndex]
            if (visual.tileType < 0) continue
            for (desiredIndex in 0 until context.desiredCount) {
                if (context.isDesiredUsed(desiredIndex)) continue
                val desired = context.desired[desiredIndex]
                if (visual.tileType != desired.tileType) continue
                // River tiles stay in their own river; claims still match river → meld.
                if (visual.slotKey.kind == SlotKind.River &&
                    desired.slotKey.kind == SlotKind.River &&
                    visual.seatIndex != desired.seatIndex
                ) continue
                candidates += TileBoardMatchCandidate(
                    cost = squaredDistance(visual, desired),
                    visualIndex = visualIndex,
                    desiredIndex = desiredIndex,
                )
            }
        }
        candidates.sortBy { it.cost }
        for ((_, visualIndex, desiredIndex) in candidates) {
            if (context.isVisualUsed(visualIndex) ||
                context.isDesiredUsed(desiredIndex)
            ) continue
            context.use(visualIndex, desiredIndex)
        }
    }
}

/** Preserves hand movement when a draw/discard changes slot counts or ordering. */
internal object HandSlideMatchStrategy : TileBoardMatchStrategy {
    override fun apply(context: TileBoardReconcileContext) {
        if (context.dealStagger) return
        val seats = (context.tiles.map { it.seatIndex } + context.desired.map { it.seatIndex }).distinct()
        for (seat in seats) {
            matchHandToRiverOrMeld(context, seat)
            matchRemainingHandSlots(context, seat)
        }
    }

    private fun matchHandToRiverOrMeld(context: TileBoardReconcileContext, seat: Int) {
        val visualIndices = context.tiles.indices
            .filter { index ->
                !context.isVisualUsed(index) &&
                    context.tiles[index].seatIndex == seat &&
                    context.tiles[index].slotKey.kind in setOf(SlotKind.Drawn, SlotKind.Hand)
            }
            .sortedWith(
                compareByDescending<Int> { context.tiles[it].slotKey.kind == SlotKind.Drawn }
                    .thenByDescending { context.tiles[it].slotKey.index },
            )
        val desiredIndices = context.desired.indices
            .filter { index ->
                !context.isDesiredUsed(index) &&
                    context.desired[index].seatIndex == seat &&
                    context.desired[index].slotKey.kind in setOf(SlotKind.River, SlotKind.Meld)
            }
            .sortedWith(
                compareBy<Int> { if (context.desired[it].slotKey.kind == SlotKind.River) 0 else 1 }
                    .thenBy { context.desired[it].slotKey.index },
            )

        for (i in 0 until minOf(visualIndices.size, desiredIndices.size)) {
            context.use(visualIndices[i], desiredIndices[i])
        }
    }

    private fun matchRemainingHandSlots(context: TileBoardReconcileContext, seat: Int) {
        val visualIndices = context.tiles.indices.filter { index ->
            !context.isVisualUsed(index) &&
                context.tiles[index].seatIndex == seat &&
                context.tiles[index].slotKey.kind in setOf(SlotKind.Drawn, SlotKind.Hand)
        }
        val desiredIndices = context.desired.indices.filter { index ->
            !context.isDesiredUsed(index) &&
                context.desired[index].seatIndex == seat &&
                context.desired[index].slotKey.kind in setOf(SlotKind.Drawn, SlotKind.Hand)
        }
        if (visualIndices.isEmpty() || desiredIndices.isEmpty()) return

        val candidates = ArrayList<TileBoardMatchCandidate>(visualIndices.size * desiredIndices.size)
        for (visualIndex in visualIndices) {
            for (desiredIndex in desiredIndices) {
                val visualType = context.tiles[visualIndex].tileType
                val desiredType = context.desired[desiredIndex].tileType
                if (!handSlotTypesCompatible(visualType, desiredType)) continue
                candidates += TileBoardMatchCandidate(
                    cost = squaredDistance(context.tiles[visualIndex], context.desired[desiredIndex]),
                    visualIndex = visualIndex,
                    desiredIndex = desiredIndex,
                )
            }
        }
        candidates.sortBy { it.cost }
        for ((_, visualIndex, desiredIndex) in candidates) {
            if (context.isVisualUsed(visualIndex) ||
                context.isDesiredUsed(desiredIndex)
            ) continue
            context.use(visualIndex, desiredIndex)
        }
    }
}

private data class TileBoardMatchCandidate(
    val cost: Float,
    val visualIndex: Int,
    val desiredIndex: Int,
)

private fun TileBoardReconcileContext.latestUnoccupiedRiverIndex(
    predicate: (DesiredSlot) -> Boolean,
): Int {
    var best = -1
    for (index in desired.indices) {
        if (isDesiredUsed(index)) continue
        val slot = desired[index]
        if (slot.slotKey.kind != SlotKind.River || !predicate(slot)) continue
        if (hasSittingOccupant(slot.slotKey)) continue
        if (best < 0 || slot.slotKey.index > desired[best].slotKey.index) best = index
    }
    return best
}

private fun TileBoardReconcileContext.hasSittingOccupant(slotKey: SlotKey): Boolean {
    for (index in tiles.indices) {
        if (isVisualUsed(index)) continue
        if (tiles[index].slotKey == slotKey) return true
    }
    return false
}

private fun squaredDistance(visual: VisualTile, desired: DesiredSlot): Float {
    val dx = visual.current.x - desired.pose.x
    val dy = visual.current.y - desired.pose.y
    return dx * dx + dy * dy
}

private fun handSlotTypesCompatible(visualType: Int, desiredType: Int): Boolean {
    if (visualType >= 0 && desiredType < 0) return false
    if (visualType >= 0 && visualType != desiredType) return false
    return true
}
