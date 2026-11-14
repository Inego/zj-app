package inego.zungjunggame.render.wallbuild

/**
 * Diagram phase for one wall-build beat. Independent of coach body keys
 * ([WallBuildBeats] maps keys → phase + overlays).
 */
enum class WallBuildPhase {
    Faces,
    /** ×4 open copies packed in the diagram (no table square yet). */
    Copies,
    /** Face-down at fixed 40:800 scale; table square rim appears here. */
    FaceDown,
    Shuffle,
    /** Axis-aligned 4×17×2 wall on the table rim. */
    Wall,
    /**
     * Walls pushed in: 4-fold pinwheel joint
     * ([WallSlotLayout.WALL_PUSH_OFFSET_TILES] / [WallSlotLayout.WALL_PUSH_ANGLE_DEG]).
     */
    WallPush,
}
