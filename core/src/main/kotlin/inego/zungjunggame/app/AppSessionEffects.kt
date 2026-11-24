package inego.zungjunggame.app

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.online.OnlineCoordinator
import inego.zungjunggame.features.board.FreePlayPresenter
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.session.hosts.FreePlayHost
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.SessionRegistry

/**
 * Board commits, the free-play session, the score ledger/setup, and the shared
 * confirm card.
 *
 * This effect owns **what an answer does**, not the question. The question is the
 * route's ([ConfirmPrompt]): the card the player sees and the action its Yes
 * applies are one entry of the stack, which is why a restored card asks the same
 * thing and still means it. Raising the card is therefore just pushing its route.
 *
 * The branches that finish in another domain take that domain's finishing call as
 * a constructor dependency ([logout], [restartTutorialLesson], [openOnlineHub])
 * instead of reaching into its effect object; the state machine itself stays in
 * one place. A branch that finishes nowhere is a question the raiser never hears
 * the answer to, so every prompt here has one.
 *
 * It also owns the **board commit gate**: `Intent.Discard` / `Intent.ApplyChoice`
 * and the free-play presenter's MC-hint choice run through the same legality
 * checks, so a hint that claims a tile and a tap on it cannot diverge.
 */
internal class AppSessionEffects(
    private val table: PlayTableView,
    private val freePlayHost: FreePlayHost,
    private val freePlayPresenter: FreePlayPresenter,
    private val sessionRegistry: SessionRegistry,
    private val onlineCoordinator: OnlineCoordinator,
    private val routes: RouteController,
    private val transitions: ScreenTransitions,
    /**
     * Re-read the signed-in account (`GET /me`). A match that ends while its table
     * is **parked** is never reported through the table port, so the floor that
     * parking lands on has to ask the account rather than keep the rating it
     * carried into the match.
     */
    private val refreshAccount: () -> Unit,
    /** `Intent.LogOut` accepted: leave the table, log out, land on the menu. */
    private val logout: () -> Unit,
    /** The running lesson restarts from step 0 (`Intent.TutorialSelect` accepted). */
    private val restartTutorialLesson: () -> Unit,
    /** `Intent.Forfeit` accepted: return to the Online hub. */
    private val openOnlineHub: () -> Unit,
) : SessionEffects {

    private val hand get() = sessionRegistry.liveHand

    /** Live kind, derived from the stack floor; the effect never sets it. */
    private val kind: SessionKind get() = sessionRegistry.kind

    override fun discard(tileType: Int, visualId: Int, isDrawn: Boolean) {
        commitDiscard(tileType, visualId, isDrawn)
    }

    override fun discardDrawnTile() {
        val activePlayHost = sessionRegistry.playHost
        val view = activePlayHost.tableView ?: return
        if (!view.canDiscardDrawnTile) return
        val ownSeatView = view.seats.getOrNull(view.mySeat) ?: return
        val tile = ownSeatView.drawnTile
        if (tile < 0) return
        commitDiscard(tile, visualId = -1, isDrawn = true)
    }

    override fun applyChoice(choice: Choice) {
        commitChoice(choice)
    }

    override fun hint() {
        freePlayPresenter.highlightEngineHint(hand)
    }

    override fun aiAssist() {
        freePlayHost.aiAssistHuman(hand)
    }

    override fun requestNextHand() {
        if (kind == SessionKind.Online) {
            sessionRegistry.playHost.requestNextHand()
        } else {
            freePlayHost.requestNextHand(hand)
        }
    }

    override fun requestNewGame() {
        freePlayHost.requestNewGame()
    }

    override fun startFromSetup() {
        freePlayHost.startFromSetup()
        transitions.localBoard()
    }

    override fun cancelSetup() {
        // Closing the card is the whole exit: the local menu it was opened from is
        // the layer underneath, and the board is above that one when a game is live.
        freePlayHost.dismissNewGameSetup()
    }

    override fun enterSetup() {
        // The host pushes the setup route through its route-backed setup port; no
        // separate modal push here.
        freePlayHost.showNewGameSetup()
    }

    override fun openScoreTable() {
        // The host authorizes the ledger and the route-backed port raises it; the
        // port uses the REPLACE shape for free play and online alike, so the menu
        // card does not survive under the table (see [replaceMenuCardWith]).
        freePlayHost.openScoreTable(hand)
    }

    override fun dismissScoreTable() {
        routes.popModal(RouteIds.SCORE_TABLE)
    }

    override fun continueParked() {
        if (kind != SessionKind.FreePlay) return
        if (!hand.active) return
        transitions.localBoard()
    }

    override fun enterFromRoot() {
        if (kind != SessionKind.Menu) return
        freePlayHost.enterLocalContext()
        // The local play menu is the context; a game that came back goes above it.
        transitions.localMenu()
        if (hand.active) transitions.localBoard()
    }

    override fun exitToMainMenu() {
        when (kind) {
            SessionKind.FreePlay -> freePlayHost.parkToMenu()
            // The lesson shell is the context: dropping the live lesson is the whole
            // transition, and the pop below takes the shell with it.
            SessionKind.Tutorial -> exitTutorial()
            SessionKind.Online -> {
                refreshAccount()
                onlineCoordinator.parkOnlineTable()
            }
            SessionKind.Menu -> Unit
        }
        // Leaving a context for the main menu *is* popping back to the floor.
        transitions.popToRoot()
    }

    override fun confirm(prompt: ConfirmPrompt) {
        // The route *is* the card, the question included: raising it replaces the
        // game-menu card a menu row was tapped on. Leaving that card beneath would
        // swallow the first back after the question is dismissed (see
        // [replaceMenuCardWith]); a question raised off the board or the shell has
        // no menu card and is unaffected.
        routes.replaceMenuCardWith(prompt.route())
    }

    override fun confirmYes() {
        // The stack holds the question, so read it before the card leaves the stack.
        val prompt = confirmPrompt() ?: return
        dismissConfirm()
        when (prompt) {
            ConfirmPrompt.AbandonHand -> freePlayHost.onConfirmNextHand()
            ConfirmPrompt.RestartLesson -> restartTutorialLesson()
            ConfirmPrompt.LogOut -> logout()
            ConfirmPrompt.Forfeit -> {
                // No rating read here: the settlement is committed after the table
                // finishes, and presence announces it (see
                // OnlineCoordinator.onSettledMatch) long after this lands.
                onlineCoordinator.leaveOnlineTable()
                removeGameMenu()
                openOnlineHub()
            }
        }
    }

    override fun confirmNo() {
        dismissConfirm()
    }

    // ---- Cross-effect seams ---------------------------------------------------

    /** Live lesson exit, resolved by the graph (`SessionKind.Tutorial` only). */
    internal var exitTutorial: () -> Unit = {}

    /** Drop the shared confirm card; the question leaves with the route that carried it. */
    internal fun dismissConfirm() {
        routes.removeModal(RouteIds.CONFIRM)
    }

    /** The question the confirm card on the stack asks; nothing is asked without one. */
    private fun confirmPrompt(): ConfirmPrompt? =
        routes.modalEntry(RouteIds.CONFIRM)?.let { ConfirmPrompt.of(it.route) }

    /** Drop the game-menu route even when a card sits above it. */
    internal fun removeGameMenu() {
        routes.removeModal(RouteIds.GAME_MENU)
    }

    /**
     * Put the game-menu card back, unless the layer underneath already is the menu.
     *
     * Callers that "restore the card" after closing a surface above it therefore
     * need no branch of their own: the rule lives with the route ([showGameMenuCard]).
     */
    internal fun pushGameMenu() {
        routes.showGameMenuCard(kind)
    }

    // ---- Board commit gate ----------------------------------------------------

    /**
     * Commit a human discard through the same gate a tap goes through.
     *
     * Used by the free-play presenter's `playHintChoice` port to play a unique MC
     * co-best, so a hint cannot claim a tile the gate would refuse.
     */
    fun commitDiscard(tileType: Int, visualId: Int = -1, isDrawn: Boolean = false): Boolean {
        val activePlayHost = sessionRegistry.playHost
        val view = activePlayHost.tableView ?: return false
        if (!view.isHumanDiscardTurn) return false
        if (tileType < 0) return false

        val ownSeatView = view.seats[view.mySeat]
        val hasTile = ownSeatView.concealedTiles?.contains(tileType) == true ||
            (ownSeatView.hasDrawnTile && ownSeatView.drawnTile == tileType)
        if (!hasTile) return false

        if (visualId >= 0) table.preferVisualForNextMatch(visualId)
        if (!activePlayHost.submitDiscard(tileType, null, isDrawn)) return false
        if (onlineCoordinator.onlineHost == null) freePlayHost.onHumanBoardApplied()
        return true
    }

    /** Commit a human choice through the same gate a tap goes through. */
    fun commitChoice(choice: Choice): Boolean {
        val activePlayHost = sessionRegistry.playHost
        val view = activePlayHost.tableView ?: return false
        if (!view.isHumanInteractiveTurn) return false
        if (!activePlayHost.submitChoice(choice)) return false
        if (onlineCoordinator.onlineHost == null) freePlayHost.onHumanBoardApplied()
        return true
    }
}
