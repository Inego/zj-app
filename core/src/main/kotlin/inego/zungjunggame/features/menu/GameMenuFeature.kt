package inego.zungjunggame.features.menu

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.RatingHistoryTarget
import inego.zungjunggame.ui.stack.Route

/**
 * Registers the layers the menu card is shown by.
 *
 * The card is one view with three homes: [RouteIds.ROOT_MENU] is the main menu
 * (the stack floor), [RouteIds.LOCAL_MENU] is the local play menu a game is
 * entered through, and `game_menu` is the chrome card raised over a running
 * context. A menu surface renders the card as its *own* content, so nothing is
 * ever pushed above it to make the menu visible — the floor alone is a complete
 * screen.
 *
 * The composition root supplies **one** [GameMenuOverlay] for all three entries,
 * and each [GameMenuComponent] owns its entry's player-set card state in the route
 * UI store ([GameMenuCardState]): every frame the widget is handed that entry's
 * stored offset and drawer, and every value the widget reports is written back
 * when it differs. The widget is not cloned, and [overlay] is the one provider the
 * root supplies; a distinct instance per route would multiply the widget for no
 * gain.
 */
object GameMenuFeature {
    fun register(registry: FeatureRegistry) {
        register(registry, overlay = { GameMenuOverlay() })
    }

    /** Registers every layer with host-owned overlay, render, and camera seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> GameMenuOverlay,
        renderInput: (Route) -> GameMenuRenderInput = { GameMenuRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val routes = listOf(
            Route.Modal(RouteIds.GAME_MENU),
            Route.Screen(RouteIds.ROOT_MENU),
            Route.Layer(RouteIds.LOCAL_MENU),
        )
        for (route in routes) {
            registry.register(
                routeKey = route.key,
                factory = { registered ->
                    GameMenuComponent(
                        route = registered,
                        overlay = overlay(),
                        renderInput = renderInput,
                        camera = camera,
                        state = routeUi.port(registered.key, GameMenuCardCodec),
                    )
                },
                spec = RouteSpecs.specFor(route),
                actions = GameMenuActions,
            )
        }
    }
}

/**
 * Maps the menu's published rows to [Intent]s.
 *
 * Several legacy actions have no exact [Intent]; the nearest existing variant is
 * used and flagged here: players/tutorial/
 * exit-to-main-menu use navigation routes; `continue` pushes the local board over
 * the local play menu; `exit` quits
 * via [Intent.ExitApp]; `forfeit` opens the confirm modal the legacy handler
 * showed; the signed-out account dropdown maps its Google row to [Intent.LogIn]
 * and its special-key row to [Intent.LogInSpecialKey]. A signed-in badge only
 * toggles the card's own account drawer, which is the route's UI state, not a
 * feature action; the drawer's **Account…** row opens the account panel and its
 * **Rating history** row opens the viewer's chart.
 */
object GameMenuActions : RouteActions {
    const val SCORE_TABLE = "menu.scoreTable"
    const val REFERENCE = "menu.reference"
    const val ABOUT = "menu.about"
    const val LANGUAGE = "menu.language"
    const val SETTINGS = "menu.settings"
    const val NEW_GAME = "menu.newGame"
    const val CONTINUE = "menu.continue"
    const val PLAY_AGAINST_BOTS = "menu.playAgainstBots"
    const val TUTORIAL = "menu.tutorial"
    const val LOG_IN = "menu.logIn"
    const val LOG_IN_SPECIAL_KEY = "menu.logInSpecialKey"
    const val LOG_OUT = "menu.logOut"
    const val PLAYERS = "menu.players"
    const val FORFEIT = "menu.forfeit"
    const val EXIT_TO_MAIN_MENU = "menu.exitToMainMenu"
    const val EXIT = "menu.exit"
    /** The chrome card's top-right ✕; the only dismiss that is not a row. */
    const val CLOSE = "menu.close"
    const val IDENTITY = "menu.identity"
    const val DROPDOWN_ACCOUNT = "menu.dropdown.account"
    const val DROPDOWN_RATING_HISTORY = "menu.dropdown.ratingHistory"
    const val DROPDOWN_LOG_OUT = "menu.dropdown.logOut"
    const val NOTICES = "menu.notices"

    /** Stable action id for a legacy menu row. */
    fun idFor(action: GameMenuOverlay.Action): String = when (action) {
        GameMenuOverlay.Action.ScoreTable -> SCORE_TABLE
        GameMenuOverlay.Action.Reference -> REFERENCE
        GameMenuOverlay.Action.About -> ABOUT
        GameMenuOverlay.Action.OpenLanguage -> LANGUAGE
        GameMenuOverlay.Action.Settings -> SETTINGS
        GameMenuOverlay.Action.NewGame -> NEW_GAME
        GameMenuOverlay.Action.Continue -> CONTINUE
        GameMenuOverlay.Action.PlayAgainstBots -> PLAY_AGAINST_BOTS
        GameMenuOverlay.Action.Tutorial -> TUTORIAL
        GameMenuOverlay.Action.LogIn -> LOG_IN
        GameMenuOverlay.Action.LogInSpecialKey -> LOG_IN_SPECIAL_KEY
        GameMenuOverlay.Action.LogOut -> LOG_OUT
        GameMenuOverlay.Action.Account -> DROPDOWN_ACCOUNT
        GameMenuOverlay.Action.RatingHistory -> DROPDOWN_RATING_HISTORY
        GameMenuOverlay.Action.Players -> PLAYERS
        GameMenuOverlay.Action.Forfeit -> FORFEIT
        GameMenuOverlay.Action.ExitToMainMenu -> EXIT_TO_MAIN_MENU
        GameMenuOverlay.Action.Close -> CLOSE
        GameMenuOverlay.Action.Exit -> EXIT
        GameMenuOverlay.Action.Notices -> NOTICES
    }

    /**
     * Maps the row a completed press on the card resolved to its action. The
     * overlay reports its own `Action` name, so an unknown id resolves to nothing
     * rather than to a wrong row.
     */
    fun actionFor(verdict: GameMenuVerdict): ComponentAction? {
        val id = verdict.selectedId ?: return null
        val action = runCatching { GameMenuOverlay.Action.valueOf(id) }.getOrNull() ?: return null
        return ComponentAction(idFor(action))
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        SCORE_TABLE -> Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE))
        REFERENCE -> Intent.OpenReference()
        ABOUT -> Intent.OpenReference(ReferenceCatalog.ABOUT_PAGE_ID)
        LANGUAGE -> Intent.PushRoute(Route.Modal(RouteIds.LANGUAGE_PICKER))
        SETTINGS -> Intent.PushRoute(Route.Modal(RouteIds.SETTINGS))
        NEW_GAME -> Intent.NewGame
        // Only the local play menu draws the row: the board context is a layer to
        // push over it. The chrome card's ✕ handles the live-board case instead.
        CONTINUE -> Intent.PushRoute(Route.Layer(RouteIds.LOCAL_BOARD))
        PLAY_AGAINST_BOTS -> Intent.PlayAgainstBots
        TUTORIAL -> Intent.OpenTutorial
        LOG_IN -> Intent.LogIn
        LOG_IN_SPECIAL_KEY -> Intent.LogInSpecialKey
        LOG_OUT -> Intent.LogOut
        // The Online row enters the unified hub context above the root menu. The
        // null tab keeps the hub's remembered tab; a deep entry names one.
        PLAYERS -> Intent.OpenOnline(null)
        FORFEIT -> Intent.PushRoute(ConfirmPrompt.Forfeit.route())
        EXIT_TO_MAIN_MENU -> Intent.ExitToMainMenu
        // The card over a running context is the only home that shows the ✕; it
        // drops that modal and reveals the board or shell it covered.
        CLOSE -> Intent.HideMenu
        EXIT -> Intent.ExitApp
        IDENTITY -> Intent.LogIn
        DROPDOWN_ACCOUNT -> Intent.OpenAccount
        DROPDOWN_RATING_HISTORY -> Intent.OpenRatingHistory(RatingHistoryTarget.Self)
        DROPDOWN_LOG_OUT -> Intent.LogOut
        NOTICES -> Intent.ViewNotices
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.HideMenu
}
