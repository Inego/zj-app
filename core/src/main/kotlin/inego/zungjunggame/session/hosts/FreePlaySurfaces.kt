package inego.zungjunggame.session.hosts

import inego.zungjunggame.engine.LocalGameConfig

/**
 * Narrow render-surface ports the free-play host drives.
 *
 * Concrete render types implement these (render → session is allowed); the host
 * never references them by name. This keeps `session/` free of `render` imports.
 * Table restore/reset is a consume-once [TableHandApply] on the host snapshot,
 * not a port into the widget.
 */

/** Consume-once hand apply the free-play host publishes; the table widget consumes it. */
enum class TableHandApply {
    None,
    Reset,
    Restored,
}

/**
 * Session score-table modal surface. Production backs [show]/[hide] with the
 * `score_table` route (push/remove), so the paint range owns what is up.
 */
interface FreePlayScoreTablePort {
    fun show()
    fun hide()
}

/**
 * Inline hand-end breakdown surface. Production backs [show]/[hide] with the
 * `score_breakdown` route (push/remove), so the paint range owns what is up.
 * Card content is [inego.zungjunggame.features.score.ScoreBreakdownContent]
 * on the frame model; this port only announces the route.
 */
interface FreePlayBreakdownPort {
    fun show()
    fun hide()
}

/**
 * New Game setup surface. Production backs [show] with the `new_game_setup` route
 * (push) and [consumeDraft] with taking the route's stored draft and removing the
 * route in one step, so the draft cannot be read after the route's own state is
 * gone. The component renders the stored draft while shown; absence means the
 * player never edited it, so the host's process draft is the value.
 */
interface FreePlaySetupPort {
    fun show()

    /** Take the card's draft and close the card; null when the player never edited it. */
    fun consumeDraft(): LocalGameConfig?
}

/**
 * Local-game menu surface. The app backs [hide] with the `game_menu` route
 * (remove), so the paint range owns what is up.
 *
 * There is no port `show`: the card is **raised** solely by the guarded
 * `showGameMenuCard` (a no-op on a menu surface, which renders the card as its
 * own layer), so a port `show` would be a second, unguarded raiser.
 */
interface FreePlayMenuPort {
    fun hide()
}
