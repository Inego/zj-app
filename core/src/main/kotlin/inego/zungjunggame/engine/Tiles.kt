package inego.zungjunggame.engine

/** Tile type indices for the standard 34-type Zung Jung set. */
object Tiles {
    // Characters: indices 0-8
    const val CHAR_1 = 0
    const val CHAR_2 = 1
    const val CHAR_3 = 2
    const val CHAR_4 = 3
    const val CHAR_5 = 4
    const val CHAR_6 = 5
    const val CHAR_7 = 6
    const val CHAR_8 = 7
    const val CHAR_9 = 8

    // Dots: indices 9-17
    const val DOT_1 = 9
    const val DOT_2 = 10
    const val DOT_3 = 11
    const val DOT_4 = 12
    const val DOT_5 = 13
    const val DOT_6 = 14
    const val DOT_7 = 15
    const val DOT_8 = 16
    const val DOT_9 = 17

    // Bamboos: indices 18-26
    const val BAMBOO_1 = 18
    const val BAMBOO_2 = 19
    const val BAMBOO_3 = 20
    const val BAMBOO_4 = 21
    const val BAMBOO_5 = 22
    const val BAMBOO_6 = 23
    const val BAMBOO_7 = 24
    const val BAMBOO_8 = 25
    const val BAMBOO_9 = 26

    // Winds: indices 27-30
    const val EAST = 27
    const val SOUTH = 28
    const val WEST = 29
    const val NORTH = 30

    // Dragons: indices 31-33
    const val RED = 31
    const val GREEN = 32
    const val WHITE = 33

    /**
     * SIMPLES_MASK represents tiles 2-8 in each suit.
     * Bit is 1 if the tile index is a simple tile.
     */
    const val SIMPLES_MASK: Long = 0x3F9FCFEL

    fun isSimple(tile: Int): Boolean {
        return tile in 0..<64 && ((SIMPLES_MASK ushr tile) and 1L) != 0L
    }

    fun isTerminalOrHonor(tile: Int): Boolean {
        return tile in 0..<NUM_TILE_TYPES && !isSimple(tile)
    }
}
