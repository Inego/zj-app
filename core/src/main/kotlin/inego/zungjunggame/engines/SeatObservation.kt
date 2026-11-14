package inego.zungjunggame.engines

import inego.zungjunggame.engine.PlayerState

/**
 * Privacy-preserving observation of a seat's state, used by [DefaultEngine]
 * for legal choice ranking without omniscient knowledge of opponent concealed
 * hands or remaining wall tiles.
 */
data class SeatObservation(
    val seat: Int,
    val seatWind: Int,
    val liveWallSize: Int,
    val player: PlayerState,
    val remainingTiles: IntArray,
    val lastDiscarded: Int,
    val lastDiscarder: Int,
)
