package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.ui.stack.SessionKind

/**
 * Desktop chrome and the game-menu card.
 *
 * The menu card over a *context* is a stack route: showing it pushes
 * [RouteIds.GAME_MENU] and the paint range owns the card. On a menu surface —
 * the main menu, the local play menu — there is nothing to push: that layer
 * renders the card as its own content.
 */
internal class AppChromeEffects(
    private val sessionRegistry: SessionRegistry,
    private val routes: RouteController,
    private val toggleFullscreen: () -> Unit,
    private val exitApp: () -> Unit,
) : ChromeEffects {

    private val kind: SessionKind get() = sessionRegistry.kind

    override fun showMenu() = routes.showGameMenuCard(kind)

    override fun hideMenu() = routes.hideGameMenuCard(kind)

    override fun toggleFullscreen() {
        toggleFullscreen.invoke()
    }

    override fun exitApp() {
        exitApp.invoke()
    }
}
