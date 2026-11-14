package inego.zungjunggame.features.local

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.ui.RouteStateCodec

/**
 * The setup card's own state: the draft the player is picking and how far down the
 * card they had read. One state because they are one card's state and come back
 * together.
 *
 * Text form: `v1:<rounds|unlimited>:<chicken>:<offset>`, unchanged from the text
 * the component used to persist, so a stack snapshot written by the old code still
 * restores.
 */
data class SetupCardState(val draft: GameConfig, val scroll: Float)

/** Text codec for [SetupCardState], stored as the New Game setup route's UI state. */
object SetupCardCodec : RouteStateCodec<SetupCardState> {
    override val type = "setupCard"

    override fun encode(value: SetupCardState): String =
        "$PREFIX${value.draft.rounds ?: UNLIMITED}:${value.draft.allowChickenHands}:${value.scroll}"

    /** The card state [text] carries, or null when it is not this state's text. */
    override fun decode(text: String): SetupCardState? {
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split(':')
        if (parts.size != 3) return null
        val rounds = if (parts[0] == UNLIMITED) null else parts[0].toIntOrNull() ?: return null
        if (rounds != null && rounds <= 0) return null
        val chicken = parts[1].toBooleanStrictOrNull() ?: return null
        val scroll = parts[2].toFloatOrNull() ?: return null
        if (!scroll.isFinite() || scroll < 0f) return null
        return SetupCardState(GameConfig(rounds = rounds, allowChickenHands = chicken), scroll)
    }

    private const val PREFIX = "v1:"
    private const val UNLIMITED = "unlimited"
}
