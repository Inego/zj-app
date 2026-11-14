package inego.zungjunggame.features.board

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.claim.ClaimActionPanel
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/**
 * Maps the board's published controls to session and navigation intents.
 *
 * The hand-end card's own controls are mapped by
 * [inego.zungjunggame.features.score.ScoreBreakdownActions]; this mapper owns only
 * the board's own controls.
 */
object BoardActions : RouteActions {
    const val DISCARD = "board.discard"
    const val CLAIM = "board.claim"
    const val HINT = "board.hint"
    const val MENU = "board.menu"
    const val SCORE_BREAKDOWN = "board.scoreBreakdown"
    const val PATTERN_INFO = "board.patternInfo"
    const val PARK = "board.park"

    const val KEY_TILE_TYPE = "tileType"
    const val KEY_VISUAL_ID = "visualId"
    const val KEY_IS_DRAWN = "isDrawn"
    const val KEY_CHOICE = "choice"
    const val KEY_PAGE_ID = "pageId"
    const val KEY_CHROME = "chrome"
    const val KEY_PARK_X = "parkX"
    const val KEY_PARK_Y = "parkY"

    /**
     * The action a board-park release resolves. A drag that moved a block reports
     * the place it was left at; a release that never moved ([ParkRelease.consumed]
     * false) is an ordinary tap and falls through to the hit regions, and a
     * consumed release that named no movable block has nothing to report.
     */
    fun actionFor(release: ParkRelease): ComponentAction? {
        if (!release.consumed) return null
        val label = release.parkChromeLabel ?: return null
        return ComponentAction(
            PARK,
            mapOf(
                KEY_CHROME to label,
                KEY_PARK_X to release.parkX,
                KEY_PARK_Y to release.parkY,
            ),
        )
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISCARD -> Intent.Discard(
            tileType = action.payload[KEY_TILE_TYPE] as? Int ?: return null,
            visualId = action.payload[KEY_VISUAL_ID] as? Int ?: -1,
            isDrawn = action.payload[KEY_IS_DRAWN] as? Boolean ?: false,
        )

        CLAIM -> (action.payload[KEY_CHOICE] as? Choice)?.let { Intent.ApplyChoice(it) }

        HINT -> Intent.Hint

        // Same intent the system-back path raises: the menu card's own gate decides
        // whether a card is pushed or the surface already is the menu.
        MENU -> Intent.ShowMenu

        SCORE_BREAKDOWN -> Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE))

        PATTERN_INFO -> Intent.OpenReference(action.payload[KEY_PAGE_ID] as? String)

        // The board's movable chrome reports the place it was left at: the model
        // (`ui.prefs`) keeps the arrangement, not the widget (see [PARK]).
        PARK -> {
            val x = action.payload[KEY_PARK_X] as? Float ?: 0f
            val y = action.payload[KEY_PARK_Y] as? Float ?: 0f
            when (action.payload[KEY_CHROME]) {
                HintButtonOverlay.PARK_LABEL -> Intent.ParkHintButton(x, y)
                ClaimActionPanel.PARK_LABEL -> Intent.ParkClaimPanel(x, y)
                else -> null
            }
        }

        else -> null
    }

    override fun back(route: Route): Intent = Intent.ShowMenu
}
