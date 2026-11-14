package inego.zungjunggame.features.board

import inego.zungjunggame.engine.ChoiceKind

/**
 * Names under which [BoardComponent] publishes its geometry in `Layout.regions`.
 * The board owns both the names and the values; a layer above reads them from
 * the board's cached layout instead of reaching into the table.
 */
object BoardAnchors {
    /**
     * The playable rect the board laid the table into, in this layer's content
     * space. A sibling layer composed into the same space (the hand-end card)
     * bounds itself by this instead of the whole space.
     */
    const val PANE = "board.pane"

    /**
     * The hovered selectable hand tile's box. Present only while a hand tile is
     * under the pointer, so its absence is "nothing hovered".
     */
    const val HAND_HOVER = "board.hand.hover"

    /** The prompted / highlighted hand tile, hover-adjusted; absent when none. */
    const val HAND_PROMPT = "board.hand.prompt"

    private const val TARGET = "board.target."
    private const val CLAIM = "board.claim."
    private const val ANY = "*"

    /** The resolved anchor for a live-table target id (`river.last`, `wall.live`, `tile.N`). */
    fun target(targetId: String): String = TARGET + targetId

    /** The anchor of the claim button matching [kind] and [payload] exactly. */
    fun claim(kind: ChoiceKind, payload: Int): String = "$CLAIM${kind.name}.$payload"

    /** The anchor of the first claim button of [kind], matching any payload. */
    fun claimAny(kind: ChoiceKind): String = "$CLAIM${kind.name}.$ANY"
}
