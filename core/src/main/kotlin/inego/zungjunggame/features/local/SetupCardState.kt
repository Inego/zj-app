package inego.zungjunggame.features.local

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.ui.RouteStateCodec

/**
 * The setup card's own state: the draft the player is picking and how far down the
 * card they had read. One state because they are one card's state and come back
 * together.
 *
 * Text form: `v2:<rounds|unlimited>:<chicken>:<bot>:<offset>`. A `v1:` value —
 * `rounds:chicken:offset`, written before bot levels existed — still decodes as
 * Standard, so a stack snapshot written by the old code restores.
 */
data class SetupCardState(val draft: LocalGameConfig, val scroll: Float)

/** Text codec for [SetupCardState], stored as the New Game setup route's UI state. */
object SetupCardCodec : RouteStateCodec<SetupCardState> {
    override val type = "setupCard"

    override fun encode(value: SetupCardState): String =
        "$PREFIX_V2${roundsText(value.draft.rules)}:${value.draft.rules.allowChickenHands}:" +
            "${value.draft.botLevel.id}:${value.scroll}"

    /** The card state [text] carries, or null when it is not this state's text. */
    override fun decode(text: String): SetupCardState? {
        val parts = when {
            text.startsWith(PREFIX_V2) -> text.removePrefix(PREFIX_V2).split(':')
                .takeIf { it.size == 4 }
            text.startsWith(PREFIX_V1) -> text.removePrefix(PREFIX_V1).split(':')
                .takeIf { it.size == 3 }
            else -> null
        } ?: return null
        val rounds = if (parts[0] == UNLIMITED) null else parts[0].toIntOrNull() ?: return null
        if (rounds != null && rounds <= 0) return null
        val chicken = parts[1].toBooleanStrictOrNull() ?: return null
        val botLevel = if (parts.size == 4) BotLevel.fromId(parts[2]) else BotLevel.DEFAULT
        val scroll = parts.last().toFloatOrNull() ?: return null
        if (!scroll.isFinite() || scroll < 0f) return null
        return SetupCardState(
            draft = LocalGameConfig(
                rules = GameConfig(rounds = rounds, allowChickenHands = chicken),
                botLevel = botLevel,
            ),
            scroll = scroll,
        )
    }

    private fun roundsText(rules: GameConfig): String = rules.rounds?.toString() ?: UNLIMITED

    private const val PREFIX_V1 = "v1:"
    private const val PREFIX_V2 = "v2:"
    private const val UNLIMITED = "unlimited"
}
