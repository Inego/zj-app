package inego.zungjunggame.render.seats

/**
 * Screen-side placement of a player's area relative to the human viewer.
 *
 * Local layout is always "hand along the near edge, discards toward table center".
 * [rotationDeg] orients open faces so each seat reads their tiles upright
 * (bottom of the symbol toward that seat's edge, top toward the table center).
 *
 * libGDX rotations are counter-clockwise: 0 upright, +90 top→left, -90 top→right.
 */
enum class SeatSide(val rotationDeg: Float) {
    /** Human / near edge of the table in screen space. */
    Bottom(0f),

    /** Counter-clockwise from human; faces point left (readable from the right edge). */
    Right(90f),

    /** Opposite the human (far screen edge). */
    Opposite(180f),

    /** Clockwise from human; faces point right (readable from the left edge). */
    Left(-90f),
    ;

    /**
     * Maps local (lx along seat edge rightward from player view, ly toward table center)
     * into world coordinates. [edgeInset] is the distance from the screen edge to the
     * hand-row tile centers.
     *
     * [alongCenter] is the world position of the seat's row centre on the edge. It is
     * the **felt's** centre, not the pane's: the bottom edge may also carry the safe
     * inset, the coach reserve, and the portrait rules-credit strip, so the bottom
     * row can sit higher than the top rim, and a row centred on the raw pane would
     * sit closer to the human than to the opposite seat.
     */
    fun localToWorld(
        lx: Float,
        ly: Float,
        screenW: Float,
        screenH: Float,
        edgeInset: Float,
        alongCenter: Float,
    ): Pair<Float, Float> = when (this) {
        Bottom -> (alongCenter + lx) to (edgeInset + ly)
        Right -> (screenW - edgeInset - ly) to (alongCenter + lx)
        Opposite -> (alongCenter - lx) to (screenH - edgeInset - ly)
        Left -> (edgeInset + ly) to (alongCenter - lx)
    }

    /**
     * Rotation for seat-name labels, from the human (bottom) viewer's perspective.
     * Bottom and opposite stay upright; left reads bottom-to-top; right reads
     * top-to-bottom. Distinct from [rotationDeg], which orients tiles toward
     * each seated player.
     */
    val labelRotationDeg: Float
        get() = when (this) {
            Bottom, Opposite -> 0f
            Right -> -90f
            Left -> 90f
        }

    companion object {
        /**
         * Seat index relative to [humanSeat]: 0 = You (near edge), then right,
         * opposite, left.
         */
        fun forRelativeSeat(relativeSeat: Int): SeatSide = when (relativeSeat and 3) {
            0 -> Bottom
            1 -> Right
            2 -> Opposite
            else -> Left
        }
    }
}
