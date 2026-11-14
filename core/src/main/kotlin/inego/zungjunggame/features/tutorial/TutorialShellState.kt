package inego.zungjunggame.features.tutorial

import inego.zungjunggame.ui.RouteStateCodec

/**
 * The player-set state of the tutorial shell's lesson TOC: how far down the list
 * they had scrolled, whether the compact drawer was open, and where they left the
 * pull tab.
 *
 * One state, because they are one control's state and come back together. The
 * shell route carries it in the route UI store while the shell is up, and
 * remembers it after the shell leaves, so a return from the main menu opens the
 * drawer the way the player left it. The widget is controlled: the component
 * hands this in each frame and the shell reports what a gesture or a reveal
 * resolved.
 *
 * The defaults are the shell's view defaults, used when nothing is stored.
 */
data class TutorialShellState(
    val tocScrollY: Float = 0f,
    val drawerOpen: Boolean = true,
    val pullTabYFrac: Float = 0.5f,
)

/**
 * Text codec for [TutorialShellState], stored as the shell route's route UI state.
 *
 * Text form: `v1:<scroll>:<drawer>:<pullTab>`, with `1`/`0` for the drawer. A
 * negative scroll clamps to zero and an out-of-range pull-tab ratio clamps to
 * `0..1`; malformed text, a missing field, or an unknown version decodes to null,
 * so an unreadable value leaves the shell at its own default.
 */
object TutorialShellCodec : RouteStateCodec<TutorialShellState> {
    override val type = "tutorialShell"

    override fun encode(value: TutorialShellState): String =
        "$PREFIX${value.tocScrollY}:${if (value.drawerOpen) OPEN else CLOSED}:${value.pullTabYFrac}"

    /** The shell state [text] carries, or null when it is not this state's text. */
    override fun decode(text: String): TutorialShellState? {
        if (!text.startsWith(PREFIX)) return null
        val fields = text.removePrefix(PREFIX).split(":")
        if (fields.size != 3) return null
        val scroll = fields[0].toFloatOrNull() ?: return null
        if (!scroll.isFinite()) return null
        val drawer = when (fields[1]) {
            OPEN -> true
            CLOSED -> false
            else -> return null
        }
        val pullTab = fields[2].toFloatOrNull() ?: return null
        if (!pullTab.isFinite()) return null
        return TutorialShellState(
            tocScrollY = scroll.coerceAtLeast(0f),
            drawerOpen = drawer,
            pullTabYFrac = pullTab.coerceIn(0f, 1f),
        )
    }

    private const val PREFIX = "v1:"
    private const val OPEN = "1"
    private const val CLOSED = "0"
}
