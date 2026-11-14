package inego.zungjunggame.session.snapshots

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.session.LiveHand

/** Immutable point-in-time projection of a [inego.zungjunggame.session.LiveHand] board for features and render. */
data class HandSnapshot(
    val state: GameState?,
    val active: Boolean,
    val humanSeat: Int,
) {
    companion object {
        /** Adapts a live hand to an immutable snapshot. */
        fun from(hand: LiveHand): HandSnapshot = HandSnapshot(
            state = hand.state,
            active = hand.active,
            humanSeat = hand.humanSeat,
        )
    }
}
