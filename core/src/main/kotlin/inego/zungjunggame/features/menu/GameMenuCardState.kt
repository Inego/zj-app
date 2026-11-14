package inego.zungjunggame.features.menu

import inego.zungjunggame.ui.RouteStateCodec

/**
 * What the player left the menu card holding: how far down its body was scrolled,
 * and whether the account drawer was open.
 *
 * One state for the card because it is one card with three homes
 * ([inego.zungjunggame.runtime.RouteIds.ROOT_MENU] as the floor, `LOCAL_MENU` as the
 * context menu, `GAME_MENU` as chrome over a running context); each entry carries
 * what the card held while that layer was up, and the entry's route UI state is
 * what the snapshot writes. Both values are the player's to set — an open drawer
 * is a control they opened, not a frame of a live gesture — so the layer keeps
 * them rather than the shared widget.
 *
 * Text form: `v1:<offset>[:<drawer>]` with `1` for open; the drawer field is
 * omitted when it is closed, so `v1:120.0` and `v1:120.0:0` are the same state.
 */
data class GameMenuCardState(
    val scrollOffset: Float,
    val accountDropdownOpen: Boolean,
)

/**
 * Text codec for [GameMenuCardState], stored as each menu entry's route UI state.
 *
 * The format is unchanged from the text the component used to persist, so a
 * stack snapshot written by the old code still restores.
 */
object GameMenuCardCodec : RouteStateCodec<GameMenuCardState> {
    override val type = "gameMenuCard"

    override fun encode(value: GameMenuCardState): String =
        "$PREFIX${value.scrollOffset}" + if (value.accountDropdownOpen) ":$OPEN" else ""

    /** The card state [text] carries, or null when it is not this state's text. */
    override fun decode(text: String): GameMenuCardState? {
        if (!text.startsWith(PREFIX)) return null
        val fields = text.removePrefix(PREFIX).split(":")
        if (fields.size > 2) return null
        val offset = fields[0].toFloatOrNull() ?: return null
        if (!offset.isFinite() || offset < 0f) return null
        val drawer = when (fields.getOrNull(1) ?: CLOSED) {
            OPEN -> true
            CLOSED -> false
            else -> return null
        }
        return GameMenuCardState(offset, drawer)
    }

    private const val PREFIX = "v1:"
    private const val OPEN = "1"
    private const val CLOSED = "0"
}
