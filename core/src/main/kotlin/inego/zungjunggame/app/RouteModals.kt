package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.SessionKind

/**
 * Shared route-layer helpers over [RouteController].
 *
 * Effects own *which* route represents a surface and *when* it is up; these are
 * the shapes that reach-in takes, so the per-domain effects state them once
 * instead of each keeping its own copy of the `Route.Modal(...).key` dance.
 *
 * ## How a game-menu row raises its surface
 *
 * A row on the `game_menu` card opens a surface in exactly one of two shapes, and
 * the raise site names the one it chose:
 *
 * | Shape | Raise | After the surface is dismissed |
 * |---|---|---|
 * | REPLACE | [replaceMenuCardWith]: remove `game_menu`, then raise the route | the context under the menu (board/table) is revealed; its own back re-raises the menu |
 * | CHILD | [raiseCardOverMenu]: raise the route, leaving `game_menu` standing | the menu card the row was tapped on is revealed |
 *
 * **REPLACE** is the surface that *is* the menu's answer for the moment: the
 * confirm question (any row), the score table (free play and online), and the
 * New-game setup. The account sign-in flow is the one REPLACE **variant**: it
 * takes the card down while its own surface (the hub the Google flow lands on)
 * stands in its place (`AppAccountEffects.logIn`).
 *
 * **CHILD** is a modal utility card the player returns from: Reference, Settings,
 * the Account panel, the language picker, the display-name editor
 * (`CHANGE_DISPLAY_NAME`), and the Google Play reviewer's special-key field
 * (`SPECIAL_KEY`).
 *
 * On a menu surface (the root menu, the local play menu) the card is the layer's
 * own content and no `game_menu` route exists, so removing it is a no-op and the
 * two shapes collapse to a plain raise.
 */

/** Push a modal unless it is already on the stack. */
internal fun RouteController.pushModal(id: String) = pushIfAbsent(Route.Modal(id))

/**
 * REPLACE: take the game-menu card down and raise [route] in its place.
 *
 * A menu row that opens a card must not leave the menu standing under it: a card
 * under a card swallows its input, and the leftover menu route would eat the
 * first back — after the card is dismissed the menu is top again, so back would
 * hide it instead of raising it. Taking the card down reveals the context
 * instead, whose own back re-raises the menu.
 */
internal fun RouteController.replaceMenuCardWith(route: Route) {
    removeModal(RouteIds.GAME_MENU)
    pushIfAbsent(route)
}

/**
 * CHILD: raise [route] over the game-menu card, leaving the card standing.
 *
 * The surface stands over the menu; dismissing it reveals the card the row was
 * tapped on.
 */
internal fun RouteController.raiseCardOverMenu(route: Route) {
    pushIfAbsent(route)
}

/** Pop a modal when it is the top layer. */
internal fun RouteController.popModal(id: String) = popIfPresent(Route.Modal(id).key)

/**
 * Remove a modal even when another layer sits above it.
 *
 * Used for owned layers: the surface is being torn down by its owner (a session
 * exit, a transition), not dismissed by the user, so it must not depend on being
 * topmost.
 */
internal fun RouteController.removeModal(id: String) = remove(Route.Modal(id).key)

/**
 * The entry a modal with [id] has on the stack, or null when it is not up.
 *
 * For a modal whose *content* is durable — the confirm card's question is its route
 * — so the reader resolves the entry the pusher created instead of keeping a copy
 * of it.
 */
internal fun RouteController.modalEntry(id: String): RouteInstance? =
    stack.entries.firstOrNull { it.route.key == Route.Modal(id).key }


/**
 * Raise the game-menu card over the current context.
 *
 * This is the **only** way `GAME_MENU` is raised (there is no port `show`), so the
 * "never over a menu surface" invariant has one owner.
 *
 * A menu surface — the main menu, the local play menu — renders the card as its
 * own content, and the root context has no session menu to raise. In both cases
 * there is nothing to push, and pushing would draw the menu twice.
 */
internal fun RouteController.showGameMenuCard(kind: SessionKind) {
    if (kind == SessionKind.Menu || stack.menuSurface) return
    pushModal(RouteIds.GAME_MENU)
}

/**
 * Drop the game-menu card, revealing the context it was raised over.
 *
 * The mirror of [showGameMenuCard]: on a menu surface the card is the layer, so
 * dismissing it would leave the menu's own surface blank.
 */
internal fun RouteController.hideGameMenuCard(kind: SessionKind) {
    if (kind == SessionKind.Menu || stack.menuSurface) return
    removeModal(RouteIds.GAME_MENU)
}
