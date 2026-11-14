package inego.zungjunggame.engine

/**
 * Seat-wind helpers for Zung Jung.
 *
 * Absolute seats (0..3) are fixed around the table. Each hand, one seat is East
 * (dealer); the others follow counter-clockwise: South, West, North.
 */
object SeatWinds {
    fun tileForPlayer(playerIndex: Int, eastSeat: Int = 0): Int {
        if (playerIndex !in 0 until NUM_PLAYERS) return -1
        val east = ((eastSeat % NUM_PLAYERS) + NUM_PLAYERS) % NUM_PLAYERS
        val offset = (playerIndex - east + NUM_PLAYERS) % NUM_PLAYERS
        return Tiles.EAST + offset
    }

    fun name(windTile: Int): String = when (windTile) {
        Tiles.EAST -> "East"
        Tiles.SOUTH -> "South"
        Tiles.WEST -> "West"
        Tiles.NORTH -> "North"
        else -> "?"
    }
}
