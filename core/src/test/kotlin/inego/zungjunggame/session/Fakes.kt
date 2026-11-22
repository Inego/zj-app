package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.persist.LocalGameConfigKey
import inego.zungjunggame.persist.SessionSnapshot
import inego.zungjunggame.persist.SessionSnapshotKey
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import kotlin.random.Random

/**
 * Commit-only New Game config slot over in-memory storage, so session tests
 * never touch libGDX preferences.
 */
fun gameConfigSlot(
    backend: InMemoryStateBackend = InMemoryStateBackend(),
): StateSlot<LocalGameConfig> = StateSlot(DefaultStateStore(backend), LocalGameConfigKey)

/** Settable no-op [inego.zungjunggame.session.hosts.PlayHost] used by snapshot tests. */
class FakeHost : PlayHost {
    override var tableView: TableViewState? = null
    override var myDecision: ActionableDecision? = null
    override var decisionTimeLeftMs: Long? = null
    override var connectionStatus: ConnectionStatus = ConnectionStatus.Local
    override var humanSeat: Int = 0
    override var currentHandNumber: Int = 1
    override var maxHands: Int? = null
    override var matchPhase: MatchPhase = MatchPhase.Live
    override var shutdownPhase: String? = null
    override var shutdownRemainingMs: Long? = null
    override var shutdownMessage: String? = null

    var lastChoice: Choice? = null
    var discardedTile: Int? = null
    var nextHandRequests: Int = 0
    var leaveCount: Int = 0

    override fun submitChoice(choice: Choice): Boolean {
        lastChoice = choice
        return true
    }

    override fun submitDiscard(tileType: Int, slotIndex: Int?, isDrawn: Boolean): Boolean {
        discardedTile = tileType
        return true
    }

    override fun requestNextHand() {
        nextHandRequests += 1
    }

    override fun leave() {
        leaveCount += 1
    }
}

/** Settable [LiveHand] used by snapshot tests. */
class FakeHand : LiveHand {
    override var state: GameState? = null
    override var active: Boolean = false
    override var humanSeat: Int = 0
    override val choiceRng: Random = Random(0)
}

/**
 * In-memory `session.freeplay` slot, so tests can assert whether a persist
 * happened without touching Gdx or the filesystem.
 */
fun sessionSlot(
    backend: InMemoryStateBackend = InMemoryStateBackend(),
): StateSlot<SessionSnapshot> = StateSlot(DefaultStateStore(backend), SessionSnapshotKey)
