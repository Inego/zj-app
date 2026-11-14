package inego.zungjunggame.render.wallbuild

/**
 * One lined-hand tile pose from the wall-build diagram for seeding the live
 * [inego.zungjunggame.render.tiles.TileBoard] during table handoff.
 *
 * Coordinates are content-zone pixels (same space as coach draw).
 */
data class WallBuildHandoffTile(
    val seat: Int,
    val handIndex: Int,
    val tileType: Int,
    val open: Boolean,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rot: Float,
)

/**
 * Closed residual wall tile still on the diagram after the deal (live leftover
 * or dead wall). Used only for the handoff collapse / fade FX — not engine state.
 */
data class WallBuildHandoffPose(
    val tileType: Int,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rot: Float,
)

/**
 * Full wall-build → live table handoff package: hands (multiset continuity) plus
 * residual wall poses for dead-wall fade and live-wall collapse into the marker.
 */
data class WallBuildHandoffSnapshot(
    val hands: List<WallBuildHandoffTile>,
    val residualLive: List<WallBuildHandoffPose> = emptyList(),
    val residualDead: List<WallBuildHandoffPose> = emptyList(),
) {
    val isEmpty: Boolean get() = hands.isEmpty()

    companion object {
        val EMPTY = WallBuildHandoffSnapshot(emptyList())
    }
}
