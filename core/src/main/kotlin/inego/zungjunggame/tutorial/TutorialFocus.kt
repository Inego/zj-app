package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.render.table.BoardHighlight

/**
 * UI focus for a coach / quiz beat: what to emphasize and an optional nearby callout.
 *
 * **Target ids** are stable strings resolved by the active chrome:
 * - Diagram tiles: [TutorialTargets.tile] → `"tile.<0..33>"`
 * - Board / seat (existing): `"river.last"`, `"hand"`, `"wall.dead"`, `"seat.you"`, …
 *
 * **[highlight]** is the live-table outline spec (any types, any groups, anywhere
 * on the board). Null = derive from [targetId]: `tile.N` prompts your copies of
 * N; `river.last` denies the pending discard and prompts matching You copies;
 * `wall.live` prompts a parked wall-draw. Explicit [BoardHighlight.None] draws
 * no tile outlines.
 *
 * **[calloutKey]** parks a leader balloon on [targetId] (diagram tile, or live
 * table: `tile.N`, `river.last`, `wall.live`). Independent of [highlight].
 * Claim-wait preambles still park on the matching claim button. Null = no balloon.
 *
 * `{0}` in coach copy still injects [targetId] when it is a tile.
 */
data class TutorialFocus(
    val targetId: String,
    /** L10n key for a callout bubble anchored near the target; null = highlight only. */
    val calloutKey: String? = null,
    val highlight: BoardHighlight? = null,
) {
    init {
        require(targetId.isNotBlank()) { "focus targetId blank" }
        calloutKey?.let {
            require(it.isNotBlank()) { "focus calloutKey blank" }
        }
    }

    /** Table outlines for this beat: explicit spec, else derived from [targetId]. */
    fun resolvedHighlight(state: GameState?): BoardHighlight {
        highlight?.let { return it }
        TutorialTargets.parseTile(targetId)?.let { return BoardHighlight.prompt(it) }
        if (targetId == "wall.live") {
            val drawn = state?.lastDrawnTile ?: -1
            return if (drawn >= 0) BoardHighlight.prompt(drawn) else BoardHighlight.None
        }
        if (targetId == "river.last") {
            val discarded = state?.lastDiscarded ?: -1
            return if (discarded >= 0) {
                BoardHighlight.denyPendingAndPromptYou(discarded)
            } else {
                BoardHighlight.denyPending()
            }
        }
        return BoardHighlight.None
    }
}

/**
 * Canonical target-id helpers so lessons do not invent string formats ad hoc.
 */
object TutorialTargets {
    fun tile(type: Int): String {
        require(type in 0..33) { "tile type $type out of 0..33" }
        return "tile.$type"
    }

    fun parseTile(targetId: String): Int? {
        if (!targetId.startsWith("tile.")) return null
        return targetId.removePrefix("tile.").toIntOrNull()?.takeIf { it in 0..33 }
    }
}
