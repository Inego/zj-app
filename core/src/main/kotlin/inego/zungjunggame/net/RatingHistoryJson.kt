package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Response JSON for the rating-history endpoints. No Gdx app required.
 *
 * The zj-server rating-history API contract promises every object
 * field on every response, with unavailable values as `null` rather than omitted,
 * so a missing field is a contract violation and throws rather than defaulting:
 * the transport turns the throw into [RatingHistoryFailure.Unreadable], and a
 * chart does not silently draw a body that mixed an old shape with new candles.
 */
object RatingHistoryJson {
    fun parsePage(body: String): RatingHistoryPage {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty rating history response")
        return RatingHistoryPage(
            state = parseStatus(string(root, "state")),
            displayName = string(root, "display_name"),
            epoch = decimal(root, "epoch"),
            publishedSequence = decimal(root, "published_sequence"),
            eventCount = longOrNull(root, "event_count"),
            initialRating = doubleOrNull(root, "initial_rating"),
            lastRating = doubleOrNull(root, "last_rating"),
            firstEventAt = longOrNull(root, "first_event_at"),
            lastEventAt = longOrNull(root, "last_event_at"),
            resolution = parseResolution(root.get("resolution") ?: missing("resolution")),
            tiles = parseTiles(root.get("tiles") ?: missing("tiles")),
            carryRating = doubleOrNull(root, "carry_rating"),
            candles = parseCandles(root.get("candles") ?: missing("candles")),
        )
    }

    private fun parseStatus(value: String): RatingHistoryStatus = when (value) {
        "ready" -> RatingHistoryStatus.Ready
        "empty" -> RatingHistoryStatus.Empty
        "pending" -> RatingHistoryStatus.Pending
        "not_ready" -> RatingHistoryStatus.NotReady
        else -> throw IllegalArgumentException("unknown rating history state '$value'")
    }

    private fun parseTileStatus(value: String): RatingTileStatus = when (value) {
        "covered" -> RatingTileStatus.Covered
        "before_history" -> RatingTileStatus.BeforeHistory
        "after_history" -> RatingTileStatus.AfterHistory
        "unavailable" -> RatingTileStatus.Unavailable
        else -> throw IllegalArgumentException("unknown rating tile status '$value'")
    }

    private fun parseResolution(value: JsonValue): RatingHistoryLevel? {
        if (value.isNull) return null
        val level = int(value, "level")
        if (level !in RatingHistoryTiles.MIN_LEVEL..RatingHistoryTiles.MAX_LEVEL) {
            throw IllegalArgumentException("resolution level $level is off the ladder")
        }
        return RatingHistoryLevel(level = level, bucketMs = long(value, "bucket_ms"))
    }

    /** The contract promises the field is present (null when unavailable). */
    private fun missing(name: String): Nothing = throw IllegalArgumentException("missing $name")

    private fun parseTiles(tiles: JsonValue): List<RatingHistoryTile> {
        if (tiles.isNull) return emptyList()
        val out = ArrayList<RatingHistoryTile>(tiles.size)
        var child = tiles.child
        while (child != null) {
            out.add(
                RatingHistoryTile(
                    index = long(child, "index"),
                    startMs = long(child, "start_ms"),
                    endMs = long(child, "end_ms"),
                    status = parseTileStatus(string(child, "status")),
                ),
            )
            child = child.next
        }
        return out
    }

    /**
     * The compact `[bucket_start_ms, open, high, low, close, count]` tuples. At
     * least the six documented fields are read; a trailing field would be an
     * extension the client does not invent meaning for.
     */
    private fun parseCandles(candles: JsonValue): List<RatingCandle> {
        if (candles.isNull) return emptyList()
        val out = ArrayList<RatingCandle>(candles.size)
        var child = candles.child
        while (child != null) {
            if (child.size < 6) throw IllegalArgumentException("candle needs six fields")
            out.add(
                RatingCandle(
                    startMs = child.get(0).asLong(),
                    open = child.get(1).asDouble(),
                    high = child.get(2).asDouble(),
                    low = child.get(3).asDouble(),
                    close = child.get(4).asDouble(),
                    count = child.get(5).asLong(),
                ),
            )
            child = child.next
        }
        return out
    }

    /** A required string field; null is a contract violation, not an empty name. */
    private fun string(value: JsonValue, name: String): String {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        if (field.isNull) throw IllegalArgumentException("$name is null")
        return field.asString()
    }

    /**
     * An opaque 64-bit revision carried as a decimal **string** (§4). Parsed with
     * exact integer arithmetic: a value that is not plain unsigned decimal, or that
     * does not fit an `i64`, fails the body instead of rounding through a
     * `Double`.
     */
    private fun decimal(value: JsonValue, name: String): Long? {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        if (field.isNull) return null
        val text = field.asString()
            ?: throw IllegalArgumentException("$name is not a string")
        if (text.isEmpty() || !text.all { it in '0'..'9' }) {
            throw IllegalArgumentException("$name is not a decimal revision: '$text'")
        }
        return text.toLongOrNull()
            ?: throw IllegalArgumentException("$name is outside the signed 64-bit range: '$text'")
    }

    private fun int(value: JsonValue, name: String): Int {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return field.asInt()
    }

    private fun long(value: JsonValue, name: String): Long {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return field.asLong()
    }

    private fun longOrNull(value: JsonValue, name: String): Long? {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return if (field.isNull) null else field.asLong()
    }

    private fun doubleOrNull(value: JsonValue, name: String): Double? {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return if (field.isNull) null else field.asDouble()
    }
}
