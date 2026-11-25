package inego.zungjunggame.features.local

import inego.zungjunggame.engine.BotLevel
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.HintMode
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.ui.RouteStateCodec

/**
 * The setup card's own state: the draft the player is picking and how far down the
 * card they had read. One state because they are one card's state and come back
 * together.
 *
 * Text form: `v4:<rounds|unlimited>:<chicken>:<bot>:<hints>:<fastBots>:<offset>`.
 * Earlier `v3:` (without fastBots), `v2:` (without hints), and `v1:` (without bot
 * or hints) snapshots still decode with defaults so stack snapshots written by
 * older builds restore.
 */
data class SetupCardState(val draft: LocalGameConfig, val scroll: Float)

/** Text codec for [SetupCardState], stored as the New Game setup route's UI state. */
object SetupCardCodec : RouteStateCodec<SetupCardState> {
    override val type = "setupCard"

    override fun encode(value: SetupCardState): String =
        "$PREFIX_V4${roundsText(value.draft.rules)}:${value.draft.rules.allowChickenHands}:" +
            "${value.draft.botLevel.id}:${value.draft.hintMode.id}:${value.draft.fastBots}:${value.scroll}"

    /** The card state [text] carries, or null when it is not this state's text. */
    override fun decode(text: String): SetupCardState? {
        val parts = when {
            text.startsWith(PREFIX_V4) -> text.removePrefix(PREFIX_V4).split(':')
                .takeIf { it.size == 6 }
            text.startsWith(PREFIX_V3) -> text.removePrefix(PREFIX_V3).split(':')
                .takeIf { it.size == 5 }
            text.startsWith(PREFIX_V2) -> text.removePrefix(PREFIX_V2).split(':')
                .takeIf { it.size == 4 }
            text.startsWith(PREFIX_V1) -> text.removePrefix(PREFIX_V1).split(':')
                .takeIf { it.size == 3 }
            else -> null
        } ?: return null
        val rounds = if (parts[0] == UNLIMITED) null else parts[0].toIntOrNull() ?: return null
        if (rounds != null && rounds <= 0) return null
        val chicken = parts[1].toBooleanStrictOrNull() ?: return null
        val botLevel = if (parts.size >= 4) BotLevel.fromId(parts[2]) else BotLevel.DEFAULT
        val hintMode = if (parts.size >= 5) HintMode.fromId(parts[3]) else HintMode.DEFAULT
        val fastBots = if (parts.size >= 6) {
            parts[4].toBooleanStrictOrNull() ?: return null
        } else {
            false
        }
        val scroll = parts.last().toFloatOrNull() ?: return null
        if (!scroll.isFinite() || scroll < 0f) return null
        return SetupCardState(
            draft = LocalGameConfig(
                rules = GameConfig(rounds = rounds, allowChickenHands = chicken),
                botLevel = botLevel,
                hintMode = hintMode,
                fastBots = fastBots,
            ),
            scroll = scroll,
        )
    }

    private fun roundsText(rules: GameConfig): String = rules.rounds?.toString() ?: UNLIMITED

    private const val PREFIX_V1 = "v1:"
    private const val PREFIX_V2 = "v2:"
    private const val PREFIX_V3 = "v3:"
    private const val PREFIX_V4 = "v4:"
    private const val UNLIMITED = "unlimited"
}
