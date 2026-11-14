package inego.zungjunggame.engine

/** Number of distinct tile types (9 chars + 9 dots + 9 bamboo + 7 honors). */
const val NUM_TILE_TYPES = 34

/** Number of distinct chow types per suit (7) * 3 suits. */
const val NUM_CHOW_TYPES = 21

const val NUM_PLAYERS = 4

/** Total wall size (including 14 dead-wall tiles). */
const val WALL_SIZE = 136
const val DEAD_WALL_SIZE = 14
const val LIVE_WALL_DRAWABLE_SIZE = WALL_SIZE - DEAD_WALL_SIZE
const val INITIAL_DECISION_LIVE_WALL_SIZE =
    LIVE_WALL_DRAWABLE_SIZE - (NUM_PLAYERS * 13 + 1)
const val MAX_LEGAL_CHOICES = 64
const val MAX_GAME_STATE_CELL_CHANGES = 16

/** Suited tile count for scoring summaries (chars+dots+bamboos). */
const val NUM_SUITED_TILES = 27
