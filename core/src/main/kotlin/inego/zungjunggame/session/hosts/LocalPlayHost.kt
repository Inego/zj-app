package inego.zungjunggame.session.hosts

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.session.GameSession
import inego.zungjunggame.session.LiveHand
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.table.TableViewState

/**
 * Local play host implementation wrapping [inego.zungjunggame.session.LiveHand] and [inego.zungjunggame.session.board.BoardController].
 *
 * Exposes a view-projected [tableView] and validates/submits human decisions
 * through [board].
 */
class LocalPlayHost(
    private val liveHand: LiveHand,
    private val board: BoardController,
    private val onNextHand: (() -> Unit)? = null,
    private val onLeave: (() -> Unit)? = null,
    private val currentHandNumberProvider: (() -> Int)? = null,
    private val maxHandsProvider: (() -> Int?)? = null,
    private val allowChickenHandsProvider: (() -> Boolean)? = null,
) : PlayHost {

    override val connectionStatus: ConnectionStatus
        get() = ConnectionStatus.Local

    override val humanSeat: Int
        get() = liveHand.humanSeat

    override val currentHandNumber: Int
        get() = currentHandNumberProvider?.invoke() ?: ((liveHand as? GameSession)?.currentHandNumber() ?: 1)

    override val maxHands: Int?
        get() = if (maxHandsProvider != null) {
            maxHandsProvider.invoke()
        } else {
            (liveHand as? GameSession)?.gameConfig?.maxHands
        }

    override val allowChickenHands: Boolean
        get() = allowChickenHandsProvider?.invoke()
            ?: ((liveHand as? GameSession)?.gameConfig?.allowChickenHands ?: false)

    /**
     * Local free play finishes when its [GameSession] has recorded every configured
     * hand — the same predicate [GameSession.isGameComplete] exposes. It is never
     * aborted: a local game is parked, not lost.
     */
    override val matchPhase: MatchPhase
        get() = if ((liveHand as? GameSession)?.isGameComplete() == true) {
            MatchPhase.Finished
        } else {
            MatchPhase.Live
        }

    override val tableView: TableViewState?
        get() {
            val state = liveHand.state ?: return null
            return TableViewState.fromGameState(state, humanSeat)
        }

    override val myDecision: ActionableDecision?
        get() {
            val state = liveHand.state ?: return null
            val view = tableView ?: return null
            if (!view.isHumanInteractiveTurn && !view.isHumanActOnHandPanel) return null

            val decisionId = "${state.currentPlayer}_${state.decisionType}_${state.liveWallSize}"
            return ActionableDecision(
                decisionId = decisionId,
                isDiscard = view.isHumanDiscardTurn,
                isClaim = view.isHumanClaimTurn,
                isActOnHandPanel = view.isHumanActOnHandPanel,
                choices = view.myChoices,
            )
        }

    override val decisionTimeLeftMs: Long? get() = null

    override fun submitChoice(choice: Choice): Boolean {
        val state = liveHand.state ?: return false
        return board.tryApply(state, choice)
    }

    override fun submitDiscard(tileType: Int, slotIndex: Int?, isDrawn: Boolean): Boolean {
        val state = liveHand.state ?: return false
        return board.tryApplyDiscard(state, tileType)
    }

    override fun requestNextHand() {
        onNextHand?.invoke()
    }

    override fun leave() {
        onLeave?.invoke()
    }
}
