package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.table.TableViewState

/**
 * [PlayHost] adapter over the live [TutorialRunner], so human board commits flow
 * through the same kind-agnostic path as free play and online.
 *
 * The board is projected from the runner's live-hand state; commits and
 * lifecycle are delegated through the narrow seams wired to [TutorialHost] in
 * the composition root.
 */
class TutorialPlayHost(
    private val runnerSource: () -> TutorialRunner?,
    private val revealedSeats: () -> Set<Int>,
    private val onChoice: (Choice) -> Boolean,
    private val onDiscard: (Int) -> Boolean,
    private val onLeave: () -> Unit = {},
) : PlayHost {

    /** True while a tutorial runner is hosted, so the registry may select this host. */
    val isActive: Boolean
        get() = runnerSource() != null

    override val connectionStatus: ConnectionStatus
        get() = ConnectionStatus.Local

    override val humanSeat: Int
        get() = runnerSource()?.humanSeat ?: 0

    override val allowChickenHands: Boolean
        get() = runnerSource()?.state?.allowChickenHands ?: false

    override val tableView: TableViewState?
        get() {
            val runner = runnerSource() ?: return null
            val state = runner.state ?: return null
            return TableViewState.fromGameState(state, runner.humanSeat, revealedSeats())
        }

    override val myDecision: ActionableDecision?
        get() {
            val state = runnerSource()?.state ?: return null
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

    override fun submitChoice(choice: Choice): Boolean = onChoice(choice)

    override fun submitDiscard(tileType: Int, slotIndex: Int?, isDrawn: Boolean): Boolean =
        onDiscard(tileType)

    override fun requestNextHand() {}

    override fun leave() {
        onLeave()
    }
}
