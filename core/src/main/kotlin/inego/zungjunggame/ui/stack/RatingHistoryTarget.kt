package inego.zungjunggame.ui.stack

/**
 * Who a rating-history chart belongs to: the signed-in viewer, or one listed
 * player named by their public handle.
 *
 * Declared here, beside [OnlineTab], because the target is a navigation fact the
 * global intent vocabulary carries: `input/Intent` may not depend on `features/`,
 * and both entry points — the hub's own control and a Leaderboard row — name the
 * history they open. It is also what the route's own state persists, so the
 * screen comes back on the same player after a relaunch.
 *
 * [Player.historyId] is the opaque random handle the Leaderboard carries for a
 * listed account. It is never an account id, a display name, or a tag.
 */
sealed interface RatingHistoryTarget {
    /** The viewer's own history; the server answers the signed-in account's. */
    data object Self : RatingHistoryTarget

    /** A listed player's public history, addressed by [historyId]. */
    data class Player(val historyId: String) : RatingHistoryTarget
}
