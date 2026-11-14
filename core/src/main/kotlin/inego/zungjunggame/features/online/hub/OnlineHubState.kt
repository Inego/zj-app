package inego.zungjunggame.features.online.hub

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.ui.RouteStateCodec
import inego.zungjunggame.ui.stack.OnlineTab

/**
 * The Online hub's player-set state: which tab the player last read, how far down
 * each tab's body they had read, and the Shadow Ranked draft they were picking.
 *
 * One value because it is one screen's state: the tab, both scroll offsets, and
 * the draft are written and restored together.
 */
data class OnlineHubState(
    val tab: OnlineTab,
    val liveScroll: Float = 0f,
    val shadowScroll: Float = 0f,
    val draft: GameConfig? = null,
) {
    /** How far down [tab]'s body the player had read. */
    fun scrollFor(tab: OnlineTab): Float = when (tab) {
        OnlineTab.Live -> liveScroll
        OnlineTab.ShadowRanked -> shadowScroll
    }

    /** This state with [tab]'s body scroll replaced; the other fields are kept. */
    fun withScroll(tab: OnlineTab, offset: Float): OnlineHubState = when (tab) {
        OnlineTab.Live -> copy(liveScroll = offset)
        OnlineTab.ShadowRanked -> copy(shadowScroll = offset)
    }
}

/** The hub's default state: the Live tab, top of both bodies, no draft. */
val DEFAULT_ONLINE_HUB_STATE: OnlineHubState = OnlineHubState(OnlineTab.Live)

/**
 * Text codec for [OnlineHubState], stored as the Online hub route's UI state.
 *
 * v1: a version field, the tab name, the two scroll offsets, then a presence flag
 * and — when present — the Shadow Ranked draft's rounds (`empty` = unlimited) and
 * chicken flag. Unknown versions, unknown tabs, non-positive rounds, and malformed
 * text all decode to null, so an unreadable value opens the hub on its default tab.
 */
object OnlineHubCodec : RouteStateCodec<OnlineHubState> {
    override val type = "onlineHub"

    const val VERSION: Int = 1

    override fun encode(value: OnlineHubState): String {
        val writer = RecordWriter()
            .int(VERSION)
            .field(value.tab.name)
            .float(value.liveScroll)
            .float(value.shadowScroll)
            .boolean(value.draft != null)
        value.draft?.let { draft ->
            writer.field(draft.rounds?.toString().orEmpty())
            writer.boolean(draft.allowChickenHands)
        }
        return writer.build().toString(Charsets.UTF_8)
    }

    override fun decode(text: String): OnlineHubState? = try {
        val reader = RecordReader(text.toByteArray(Charsets.UTF_8))
        val version = reader.int()
        if (version != VERSION) return null
        val tabName = reader.field()
        val liveScroll = reader.float()
        val shadowScroll = reader.float()
        val hasDraft = reader.boolean()
        val draft = if (hasDraft) {
            val rawRounds = reader.field()
            val rounds = if (rawRounds.isEmpty()) null else rawRounds.toIntOrNull() ?: return null
            GameConfig(rounds = rounds, allowChickenHands = reader.boolean())
        } else {
            null
        }
        if (!reader.atEnd()) return null
        val tab = OnlineTab.entries.firstOrNull { it.name == tabName } ?: return null
        OnlineHubState(
            tab = tab,
            liveScroll = liveScroll.coerceAtLeast(0f),
            shadowScroll = shadowScroll.coerceAtLeast(0f),
            draft = draft,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
