package inego.zungjunggame.session.table

/**
 * Session-owned origin of a discard, so the presentation layer never has to know
 * about render's tile-slot vocabulary.
 *
 * [slot] is the hand slot index the tile left from. [DRAWN] (`-1`) marks a discard
 * off the drawn tile; render maps both onto its own `SlotKey` at the boundary.
 */
data class DiscardOrigin(val seat: Int, val slot: Int) {
    companion object {
        /** Sentinel [slot] for a discard that left the drawn-tile slot. */
        const val DRAWN: Int = -1

        fun drawn(seat: Int): DiscardOrigin = DiscardOrigin(seat, DRAWN)

        fun hand(seat: Int, slot: Int): DiscardOrigin = DiscardOrigin(seat, slot)
    }
}
