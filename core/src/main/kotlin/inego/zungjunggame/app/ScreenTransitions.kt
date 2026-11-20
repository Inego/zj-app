package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.StackState

/**
 * The destinations the app moves to, expressed as stack operations.
 *
 * A thin vocabulary over the router, not a navigation authority: it holds no
 * active destination and persists nothing, so the stack stays the sole record of
 * what is up.
 *
 * Two shapes, and the difference is the model. A **context** goes *above* the
 * floor ([pushLayer]) and is left by [popToRoot], which takes its chrome with it —
 * so a context is always revealed from, and reveals, the floor. A context with a
 * parent of its own is entered *above* that parent, so leaving it reveals the
 * parent: the online table sits above the Online hub, and the tutorial lesson sits
 * above the shell that hosts it (that one is the shell's own port, see
 * `TutorialRoutesPort`, because its lifetime is the lesson session's).
 *
 * The destructive helpers are gone because there is no "leaving a screen" case
 * left: [popToRoot] is the whole cleanup. Chrome layers are raised through the
 * menu-row shapes ([replaceMenuCardWith] / [raiseCardOverMenu], see
 * `RouteModals.kt`) or the `dismiss*` calls, by whoever opened them.
 */
internal class ScreenTransitions(
    private val routes: RouteController,
) {
    private val rootKey = Route.Screen(RouteIds.ROOT_MENU).key
    private val hubKey = Route.Layer(RouteIds.ONLINE_HUB).key
    private val tableKey = Route.Layer(RouteIds.ONLINE_TABLE).key

    /** The `ONLINE_TABLE` arg naming the tab the table was entered from. */
    private val originArg = "origin"

    /** The main menu: pop back to the floor, taking every context and card with it. */
    fun popToRoot() {
        routes.popTo(rootKey)
    }

    /** Place a context above the floor unless it is already up. */
    fun pushLayer(route: Route.Layer) = routes.pushIfAbsent(route)

    /** The context a local game is entered from: New game / Continue / the ledger. */
    fun localMenu() = pushLayer(Route.Layer(RouteIds.LOCAL_MENU))

    /** The local board, above the local menu it was entered from. */
    fun localBoard() = pushLayer(Route.Layer(RouteIds.LOCAL_BOARD))

    /** REPLACE: the score table takes the game-menu card's place over the board. */
    fun openScoreTable() = routes.replaceMenuCardWith(Route.Modal(RouteIds.SCORE_TABLE))

    /** REPLACE: the New-game setup takes the game-menu card's place over the board. */
    fun openNewGameSetup() = routes.replaceMenuCardWith(Route.Modal(RouteIds.NEW_GAME_SETUP))

    /**
     * The unified Online hub: the online context above the floor, entered from
     * the menu or a deep link.
     *
     * Entering the hub is leaving the table — the table is the online context's
     * own child layer — so a table still on the stack goes with the entry and
     * takes the chrome raised over it.
     */
    fun onlineHub() {
        routes.remove(tableKey)
        pushLayer(Route.Layer(RouteIds.ONLINE_HUB))
        clearOnlineChrome()
    }

    /**
     * Leave the hub for the root menu.
     *
     * No-op when the hub is not what is up, or when a live table (the hub's own
     * child) is: a session expiry during a match must not eject the player. The
     * search-stop policy for a leave lives with the search owner; this only navigates.
     */
    fun dismissHub() {
        if (!routes.stack.contains(hubKey) || routes.stack.contains(tableKey)) return
        popToRoot()
    }

    /** The match-history card, above the Online hub it was entered from. */
    fun onlineMatchHistory() {
        onlineHub()
        routes.pushIfAbsent(Route.Modal(RouteIds.MATCH_HISTORY))
    }

    /** The leaderboard card, above the Online hub it was entered from. */
    fun onlineLeaderboard() {
        onlineHub()
        routes.pushIfAbsent(Route.Modal(RouteIds.LEADERBOARD))
    }

    /**
     * The rating-history chart, above the surface it was opened from.
     *
     * Every entry point opens it over what is already up — the hub's own control
     * over the hub, the root menu's account drawer over the menu, a Leaderboard
     * row over the leaderboard — so back reveals that surface exactly as it was:
     * the leaderboard keeps its entry, and with it the pinned reading (its
     * generation) and the route-state scroll offset, because nothing here removes
     * or re-pushes it.
     */
    fun onlineRatingHistory() {
        routes.pushIfAbsent(Route.Modal(RouteIds.RATING_HISTORY))
    }

    /** The Shadow Ranked attempts card, above the Online hub it was entered from. */
    fun onlineShadowRankedAttempts() {
        onlineHub()
        routes.pushIfAbsent(Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS))
    }

    /** The notices inbox modal. */
    fun onlineNotices() {
        routes.pushIfAbsent(Route.Modal(RouteIds.NOTICES))
    }

    /**
     * The live online table, above the Online hub it was entered from.
     *
     * The hub is the table's parent — the online base context directly above the
     * floor — so leaving the table reveals the hub rather than the root, and a table
     * restored from a snapshot comes back in the same place. [origin] is the tab the
     * table was opened from; it is carried in the route's own args (the stack is the
     * tab's record), so [tableOrigin] can restore it when the table leaves.
     *
     * A table that is **already up** is left exactly as it is, origin included: this
     * is also the rejoin/restore path, where popping what is above it would take
     * restored chrome with it.
     */
    fun onlineTable(origin: OnlineTab) {
        if (!routes.stack.contains(hubKey)) {
            routes.pushAboveFloor(Route.Layer(RouteIds.ONLINE_HUB))
        }
        if (!routes.stack.contains(tableKey)) {
            // Nothing sits between the hub and the table it was entered from, so a
            // card left open over the hub goes with the entry.
            routes.popTo(hubKey)
            routes.push(Route.Layer(RouteIds.ONLINE_TABLE, args = mapOf(originArg to origin.name)))
            clearOnlineChrome()
        }
    }

    /** The tab the table on the stack was entered from, or null when there is none. */
    fun tableOrigin(): OnlineTab? {
        val name = routes.stack.entries
            .firstOrNull { it.route.key == tableKey }
            ?.route?.args?.get(originArg)
            ?: return null
        return OnlineTab.entries.firstOrNull { it.name == name }
    }

    /** Drop the table layer: the session it stood for no longer exists. */
    fun dismissTable() {
        routes.remove(tableKey)
    }

    /** The chrome a live online context never shows. */
    private fun clearOnlineChrome() {
        hideOverlays()
        hideGameMenu()
        routes.remove(Route.Modal(RouteIds.MATCH_HISTORY).key)
        routes.remove(Route.Modal(RouteIds.LEADERBOARD).key)
        routes.remove(Route.Modal(RouteIds.RATING_HISTORY).key)
        routes.remove(Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS).key)
        routes.remove(Route.Modal(RouteIds.SHADOW_RANKED_HANDS).key)
        routes.remove(Route.Modal(RouteIds.NOTICES).key)
        routes.remove(Route.Modal(RouteIds.NOTICE_POPUP).key)
        routes.popIfPresent(Route.Reference(StackState.EMPTY).key)
    }

    /**
     * Show the browser over the current layer and run [open] to deep-link it.
     *
     * The [Route.Reference] push is what makes the browser a routable full-screen
     * layer, so back/× always has a route to pop; pushing is idempotent while one
     * is already open, so in-browser navigation does not stack a second browser.
     *
     * The browser is a stack layer, not a screen replacement: the layers below
     * (menu card, board, lesson, modals) stay on the stack, get no input while the
     * browser owns it, and are revealed when it pops.
     */
    fun openReference(open: () -> Unit = {}) {
        routes.raiseCardOverMenu(Route.Reference(StackState.EMPTY))
        open()
    }

    /** Dismiss the browser; the layer underneath reappears. */
    fun dismissReference() {
        routes.popIfPresent(Route.Reference(StackState.EMPTY).key)
    }

    /**
     * Add the Settings panel above whatever is up, never changing the floor. The
     * layer underneath (menu card or tutorial shell) stays on the stack and is
     * revealed on dismiss, so no `returnToMenu` bookkeeping is needed.
     */
    fun openSettings() {
        routes.raiseCardOverMenu(Route.Modal(RouteIds.SETTINGS))
    }

    /** Remove the Settings route; the layer underneath reappears. */
    fun dismissSettings() {
        routes.popIfPresent(Route.Modal(RouteIds.SETTINGS).key)
    }

    /**
     * Add the Account panel above whatever is up (the identity dropdown's
     * **Account…** row). The menu card stays on the stack and is revealed on
     * dismiss, so the panel needs no menu bookkeeping of its own.
     */
    fun openAccountPanel() {
        routes.raiseCardOverMenu(Route.Modal(RouteIds.ACCOUNT))
    }

    /** Remove the Account route; the menu underneath reappears. */
    fun dismissAccountPanel() {
        routes.popIfPresent(Route.Modal(RouteIds.ACCOUNT).key)
    }

    /** Add the language picker above whatever is up, keeping the floor. */
    fun openLanguagePicker() {
        routes.raiseCardOverMenu(Route.Modal(RouteIds.LANGUAGE_PICKER))
    }

    /** Remove the language picker; the layer underneath reappears. */
    fun dismissLanguagePicker() {
        routes.popIfPresent(Route.Modal(RouteIds.LANGUAGE_PICKER).key)
    }

    private fun hideGameMenu() = routes.remove(Route.Modal(RouteIds.GAME_MENU).key)

    /** Remove the stack-owned confirm/settings/account/picker routes. */
    private fun hideOverlays() {
        routes.remove(Route.Modal(RouteIds.CONFIRM).key)
        routes.remove(Route.Modal(RouteIds.SETTINGS).key)
        routes.remove(Route.Modal(RouteIds.ACCOUNT).key)
        routes.remove(Route.Modal(RouteIds.LANGUAGE_PICKER).key)
    }
}
