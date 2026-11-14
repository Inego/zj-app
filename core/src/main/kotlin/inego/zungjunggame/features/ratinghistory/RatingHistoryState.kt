package inego.zungjunggame.features.ratinghistory

import inego.zungjunggame.net.RatingHistoryTiles
import inego.zungjunggame.state.RecordReader
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.ui.RouteStateCodec
import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * The window the player left the rating chart on: a visible range in **epoch
 * milliseconds**.
 *
 * The edges are absolute instants, not offsets from the history's own start: the
 * all-time start is server metadata the client only learns once the screen opens,
 * so a window stored as an offset from an unknown origin could not be restored
 * into the same place.
 */
data class RatingHistoryWindow(
    val fromEpochMs: Double,
    val toEpochMs: Double,
)

/**
 * The rating-history screen's own state: whose history it shows, the window it
 * was left on, and the rung that window was drawn at.
 *
 * The fetched candles are deliberately absent. They are large, regenerable
 * server data with their own byte-bounded cache (outside the persisted stack),
 * while this value is small, player-set, and belongs in the route UI store: the
 * route is a remembering one, so the value outlives its entry and the process —
 * a relaunch with the chart open, and a close/reopen of the same target, restore
 * the window and the rung it was left with. The open effect does not replay
 * either onto a different target: they describe one reading's instants and
 * density, so doing that would show another player's candles at that position
 * and density.
 */
data class RatingHistoryState(
    val target: RatingHistoryTarget,
    /** Null until the player has moved the window; the screen then opens on all time. */
    val window: RatingHistoryWindow? = null,
    /**
     * The ladder rung the window was drawn at (`2^level` ms buckets), or null when
     * the chart had not chosen one.
     *
     * Remembered rather than re-derived: the rung is picked with hysteresis, so the
     * same window does not imply the same rung — a fresh density pick on reopen
     * could draw the same instants one rung finer, splitting the candles the player
     * was just looking at.
     */
    val level: Int? = null,
)

/**
 * Text codec for [RatingHistoryState], stored as the rating-history route's UI
 * state.
 *
 * v2, length-framed fields (the target handle is an opaque server string, so a
 * delimiter-joined form would need an escaping rule it cannot state):
 *
 * ```
 * version, target kind, [history id when a player], window kind, [from, to],
 * rung kind, [level]
 * ```
 *
 * The window block is present exactly when the player has one, and the rung
 * exactly when the chart had chosen one. Unknown versions and kinds, a blank
 * handle, a non-finite or inverted range, an off-ladder rung, and trailing fields
 * all decode to null, so unreadable text opens the screen on its default view
 * instead of a wrong one.
 *
 * v1 is still read: it carried the same shape without the rung, and a stack
 * persisted then must keep the window it was left on (a lost window is a lost
 * view; the rung falls back to the ladder's own choice). Its trailing `follow`
 * field — a mode since removed — is consumed and discarded, because the range is
 * unchanged and refusing it would throw that window away.
 */
object RatingHistoryCodec : RouteStateCodec<RatingHistoryState> {
    override val type = "ratingHistory"

    const val VERSION: Int = 2

    /** The version that carried no rung; read so a stack persisted then keeps its window. */
    private const val LEGACY_VERSION: Int = 1

    private const val SELF = "self"
    private const val PLAYER = "player"
    private const val NO_WINDOW = "none"
    private const val RANGE = "range"
    private const val NO_LEVEL = "none"
    private const val LEVEL = "level"

    override fun encode(value: RatingHistoryState): String {
        val writer = RecordWriter().int(VERSION)
        when (val target = value.target) {
            RatingHistoryTarget.Self -> writer.field(SELF)
            is RatingHistoryTarget.Player -> writer.field(PLAYER).field(target.historyId)
        }
        val window = value.window
        if (window == null) {
            writer.field(NO_WINDOW)
        } else {
            writer.field(RANGE)
                .field(window.fromEpochMs.toString())
                .field(window.toEpochMs.toString())
        }
        val level = value.level
        if (level == null) {
            writer.field(NO_LEVEL)
        } else {
            writer.field(LEVEL).int(level)
        }
        return writer.build().toString(Charsets.UTF_8)
    }

    override fun decode(text: String): RatingHistoryState? = try {
        val reader = RecordReader(text.toByteArray(Charsets.UTF_8))
        when (reader.int()) {
            VERSION -> {
                val target = targetOf(reader)
                val window = windowOf(reader)
                val level = levelOf(reader)
                if (!reader.atEnd()) malformed("trailing fields")
                RatingHistoryState(target = target, window = window, level = level)
            }

            LEGACY_VERSION -> {
                val target = targetOf(reader)
                val window = windowOf(reader)
                // A record written before the follow-latest mode was removed ends
                // with its flag; consume it so the range still restores.
                if (!reader.atEnd()) reader.boolean()
                if (!reader.atEnd()) malformed("trailing fields")
                RatingHistoryState(target = target, window = window)
            }

            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun targetOf(reader: RecordReader): RatingHistoryTarget =
        when (val kind = reader.field()) {
            SELF -> RatingHistoryTarget.Self
            PLAYER -> RatingHistoryTarget.Player(
                reader.field().takeIf { it.isNotBlank() } ?: malformed("blank history id")
            )

            else -> malformed("unknown target kind '$kind'")
        }

    private fun windowOf(reader: RecordReader): RatingHistoryWindow? =
        when (val kind = reader.field()) {
            NO_WINDOW -> null
            RANGE -> {
                val from = reader.field().toDoubleOrNull() ?: malformed("window start is not a number")
                val to = reader.field().toDoubleOrNull() ?: malformed("window end is not a number")
                if (!from.isFinite() || !to.isFinite() || to <= from) {
                    malformed("inverted or non-finite window")
                }
                RatingHistoryWindow(from, to)
            }

            else -> malformed("unknown window kind '$kind'")
        }

    private fun levelOf(reader: RecordReader): Int? =
        when (val kind = reader.field()) {
            NO_LEVEL -> null
            LEVEL -> {
                val level = reader.field().toIntOrNull() ?: malformed("rung is not a number")
                if (level !in RatingHistoryTiles.MIN_LEVEL..RatingHistoryTiles.MAX_TILE_LEVEL) {
                    malformed("rung $level is off the ladder")
                }
                level
            }

            else -> malformed("unknown rung kind '$kind'")
        }

    private fun malformed(message: String): Nothing = throw IllegalArgumentException(message)
}
