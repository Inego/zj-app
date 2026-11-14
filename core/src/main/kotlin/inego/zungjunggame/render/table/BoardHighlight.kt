package inego.zungjunggame.render.table

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.render.tiles.TileFocusGlow

/**
 * Tutorial outlines on the live table: one or more [HighlightGroup]s.
 *
 * Each group is a set of tile types in a [HighlightWhere]. Adjacent copies in
 * the **same** group share one outline; disconnected copies each get their own.
 * Different groups never merge, even if they sit next to each other.
 *
 * First matching group wins when a tile fits more than one.
 */
data class BoardHighlight(val groups: List<HighlightGroup>) {
    val isEmpty: Boolean get() = groups.isEmpty()

    fun hasPrompt(): Boolean = groups.any { it.glow == TileFocusGlow.Prompt || it.glow == TileFocusGlow.Secondary }

    /**
     * Types a discard-preamble leader can park on (Prompt groups on the human
     * hand). Empty → no hand anchor.
     */
    fun handAnchorTypes(): Set<Int> {
        if (groups.isEmpty()) return emptySet()
        val out = LinkedHashSet<Int>()
        for ((types, glow, where) in groups) {
            if (glow != TileFocusGlow.Prompt) continue
            when (where) {
                HighlightWhere.You,
                HighlightWhere.YouHand,
                HighlightWhere.Open,
                -> out.addAll(types)
                HighlightWhere.YouMelds,
                HighlightWhere.PendingDiscard,
                HighlightWhere.Rivers,
                HighlightWhere.OpponentHand,
                -> Unit
            }
        }
        return out
    }

    fun match(
        human: Boolean,
        region: PlayerAreaLayout.Region,
        tileType: Int,
        pendingDiscard: Boolean,
        open: Boolean,
    ): Hit? {
        for (i in groups.indices) {
            if (groups[i].matches(human, region, tileType, pendingDiscard, open)) {
                return Hit(groups[i].glow, i)
            }
        }
        return null
    }

    /** Compact fingerprint for coach-present identity (not a parse format). */
    fun describe(): String =
        groups.joinToString(";") { g ->
            "${g.glow.name}:${g.where.name}:${g.types.joinToString(",")}"
        }

    data class Hit(val glow: TileFocusGlow, val groupIndex: Int)

    companion object {
        val None: BoardHighlight = BoardHighlight(emptyList())

        /** Orange Prompt on [types] in [where] (default: your hand + melds). */
        fun prompt(
            vararg types: Int,
            where: HighlightWhere = HighlightWhere.You,
        ): BoardHighlight = typed(TileFocusGlow.Prompt, where, types, "prompt")

        /** Red Deny on [types] in [where] (default: your hand + melds). */
        fun deny(
            vararg types: Int,
            where: HighlightWhere = HighlightWhere.You,
        ): BoardHighlight = typed(TileFocusGlow.Deny, where, types, "deny")

        /** Cyan Secondary on [types] in [where] (default: your hand + melds). */
        fun secondary(
            vararg types: Int,
            where: HighlightWhere = HighlightWhere.You,
        ): BoardHighlight = typed(TileFocusGlow.Secondary, where, types, "secondary")

        /**
         * Dual-color contrast: orange Prompt on [promptTypes], cyan Secondary on [secondaryTypes].
         */
        fun promptAndSecondary(
            promptTypes: IntArray,
            secondaryTypes: IntArray,
            where: HighlightWhere = HighlightWhere.You,
        ): BoardHighlight {
            require(promptTypes.isNotEmpty() && secondaryTypes.isNotEmpty()) {
                "promptAndSecondary needs both prompt and secondary tile types"
            }
            return BoardHighlight(
                listOf(
                    HighlightGroup(
                        types = promptTypes.distinct().sorted(),
                        glow = TileFocusGlow.Prompt,
                        where = where,
                    ),
                    HighlightGroup(
                        types = secondaryTypes.distinct().sorted(),
                        glow = TileFocusGlow.Secondary,
                        where = where,
                    ),
                ),
            )
        }

        fun denyPending(): BoardHighlight =
            BoardHighlight(listOf(HighlightGroup.denyPending()))

        /**
         * Red Deny on the pending discard, orange Prompt on your copies of
         * [types] (exposed pung that blocks a kong; chow companions that cannot
         * claim from Opposite).
         */
        fun denyPendingAndPromptYou(vararg types: Int): BoardHighlight {
            require(types.isNotEmpty()) { "prompt highlight needs at least one tile type" }
            return BoardHighlight(
                listOf(
                    HighlightGroup.denyPending(),
                    HighlightGroup(
                        types = types.distinct().sorted(),
                        glow = TileFocusGlow.Prompt,
                        where = HighlightWhere.You,
                    ),
                ),
            )
        }

        private fun typed(
            glow: TileFocusGlow,
            where: HighlightWhere,
            types: IntArray,
            what: String,
        ): BoardHighlight {
            require(types.isNotEmpty()) { "$what highlight needs at least one tile type" }
            return BoardHighlight(
                listOf(
                    HighlightGroup(
                        types = types.distinct().sorted(),
                        glow = glow,
                        where = where,
                    ),
                ),
            )
        }
    }
}

/** Where on the table a [HighlightGroup] may land. */
enum class HighlightWhere {
    /** Your concealed tiles, parked draw, and open melds — not the river. */
    You,
    /** Your concealed tiles and parked draw only. */
    YouHand,
    /** Your open melds only. */
    YouMelds,
    /** The pending claim discard (last river tile of the last discarder). */
    PendingDiscard,
    /** Every river. */
    Rivers,
    /** Every face-up tile, plus your hand (opponent closed backs stay out). */
    Open,
    /** Opponents' concealed hands (e.g. for revealed tutorial hands). */
    OpponentHand,
}

/**
 * One named cluster of tiles. [types] empty means “any type” (pending-discard
 * Deny). [glow] is [TileFocusGlow.Prompt] or [TileFocusGlow.Deny].
 */
data class HighlightGroup(
    val types: List<Int>,
    val glow: TileFocusGlow,
    val where: HighlightWhere,
) {
    init {
        require(glow.isTutorialOutline) { "glow $glow is not a tutorial outline" }
        for (t in types) {
            require(t in 0 until NUM_TILE_TYPES) { "tile type $t out of 0..${NUM_TILE_TYPES - 1}" }
        }
    }

    fun matches(
        human: Boolean,
        region: PlayerAreaLayout.Region,
        tileType: Int,
        pendingDiscard: Boolean,
        open: Boolean,
    ): Boolean {
        return acceptsType(tileType) && when (where) {
            HighlightWhere.You -> human && region != PlayerAreaLayout.Region.River
            HighlightWhere.YouHand ->
                human &&
                        (region == PlayerAreaLayout.Region.Hand ||
                            region == PlayerAreaLayout.Region.Drawn)

            HighlightWhere.YouMelds -> human && region == PlayerAreaLayout.Region.Meld
            HighlightWhere.PendingDiscard -> pendingDiscard
            HighlightWhere.Rivers -> region == PlayerAreaLayout.Region.River
            HighlightWhere.Open -> open || human
            HighlightWhere.OpponentHand ->
                !human &&
                        (region == PlayerAreaLayout.Region.Hand ||
                            region == PlayerAreaLayout.Region.Drawn)
        }
    }

    private fun acceptsType(tileType: Int): Boolean {
        if (types.isEmpty()) return true
        for (t in types) if (t == tileType) return true
        return false
    }

    companion object {
        fun denyPending(): HighlightGroup =
            HighlightGroup(
                types = emptyList(),
                glow = TileFocusGlow.Deny,
                where = HighlightWhere.PendingDiscard,
            )
    }
}
