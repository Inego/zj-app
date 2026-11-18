package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Request/response JSON for `POST /challenges` and `GET /challenges`. No Gdx app
 * required (unit-testable). Wire name retained.
 */
object ChallengeJson {
    fun startRequestBody(rounds: ChallengeRounds, allowChicken: Boolean): String =
        """{"rounds":${AuthJson.quote(rounds.wire)},"allow_chicken":$allowChicken}"""

    fun parseStart(body: String): ChallengeStart {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty challenge response")
        val publicId = root.getString("public_id")
            ?: throw IllegalArgumentException("missing public_id")
        val tableId = root.getString("table_id")
            ?: throw IllegalArgumentException("missing table_id")
        val ticket = root.getString("ticket")
            ?: throw IllegalArgumentException("missing ticket")
        return ChallengeStart(
            publicId = publicId,
            tableId = tableId,
            seat = root.getInt("seat", 0),
            ticket = ticket,
        )
    }

    /**
     * Parse `GET /challenges/pool` into the selected draft's two counts.
     *
     * A missing or negative count is malformed, not zero: the badge distinguishes
     * "none available" from "the server did not answer", so it must not turn an
     * absent field into a real zero.
     */
    fun parsePool(body: String): ShadowRankedPoolCounts {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty pool response")
        val available = root.getInt("available", -1)
        val createdOpen = root.getInt("created_open", -1)
        require(available >= 0) { "missing or negative available" }
        require(createdOpen >= 0) { "missing or negative created_open" }
        return ShadowRankedPoolCounts(available = available, createdOpen = createdOpen)
    }

    /** Parse `GET /challenges` into the hub's attempts list, newest first. */
    fun parseList(body: String): ChallengeList {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty challenge list response")
        val array = root.get("challenges")
            ?: throw IllegalArgumentException("missing challenges")
        val entries = ArrayList<ChallengeEntry>()
        if (!array.isNull) {
            var child = array.child
            while (child != null) {
                entries.add(parseEntry(child))
                child = child.next
            }
        }
        val nextCursor = if (root.has("next_cursor") && !root.get("next_cursor").isNull) {
            root.getString("next_cursor")
        } else {
            null
        }
        val hasMore = root.getBoolean("has_more", false)
        return ChallengeList(entries, nextCursor, hasMore)
    }

    /** Parse `GET /challenges/{publicId}/hands` into one challenge's hand tables. */
    fun parseHands(body: String): ChallengeHands {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty hand tables response")
        val publicId = root.getString("public_id")
            ?: throw IllegalArgumentException("missing public_id")
        val array = root.get("attempts")
            ?: throw IllegalArgumentException("missing attempts")
        val attempts = ArrayList<AttemptHands>()
        if (!array.isNull) {
            var child = array.child
            while (child != null) {
                attempts.add(parseAttemptHands(child))
                child = child.next
            }
        }
        return ChallengeHands(
            publicId = publicId,
            rounds = root.getString("rounds", ""),
            attempts = attempts,
        )
    }

    private fun parseAttemptHands(value: JsonValue): AttemptHands {
        // `hands` is null when the corpus was unavailable. `parseScoreRows` erases
        // null to an empty list, so call it only when the field is a real array.
        val handsValue = value.get("hands")
        val hands = if (handsValue != null && !handsValue.isNull && handsValue.isArray) {
            WireJson.parseScoreRows(handsValue)
        } else {
            null
        }
        return AttemptHands(
            seq = value.getInt("seq", 0),
            isBot = value.getBoolean("is_bot", false),
            isYou = value.getBoolean("is_you", false),
            name = value.getString("name", null),
            rating = optionalDouble(value, "rating"),
            score = value.getInt("score", 0),
            hands = hands,
        )
    }

    fun parseStatus(raw: String): ChallengeStatus = when (raw) {
        "finished" -> ChallengeStatus.Finished
        "abandoned" -> ChallengeStatus.Abandoned
        "voided" -> ChallengeStatus.Voided
        // An unknown value must not reveal a score list, so it reads as in-flight.
        else -> ChallengeStatus.Started
    }

    internal fun parseEntry(value: JsonValue): ChallengeEntry {
        val publicId = value.getString("public_id")
            ?: throw IllegalArgumentException("missing challenge.public_id")
        val rounds = value.getString("rounds")
            ?: throw IllegalArgumentException("missing challenge.rounds")
        if (!value.has("allow_chicken")) {
            throw IllegalArgumentException("missing challenge.allow_chicken")
        }
        val startedAt = value.getString("started_at")
            ?: throw IllegalArgumentException("missing challenge.started_at")
        return ChallengeEntry(
            publicId = publicId,
            rounds = rounds,
            allowChicken = value.getBoolean("allow_chicken"),
            createdAt = value.getString("created_at", ""),
            status = parseStatus(value.getString("status", "started")),
            totalScore = optionalInt(value, "total_score"),
            startedAt = startedAt,
            finishedAt = value.getString("finished_at", null),
            players = value.getInt("players", 0),
            ratingDelta = value.getDouble("rating_delta", 0.0),
            scores = parseScores(value.get("scores")),
        )
    }

    private fun parseScores(array: JsonValue?): List<ChallengeScoreRow> {
        if (array == null || array.isNull) return emptyList()
        val rows = ArrayList<ChallengeScoreRow>()
        var child = array.child
        while (child != null) {
            rows.add(
                ChallengeScoreRow(
                    name = child.getString("name", null),
                    score = child.getInt("score", 0),
                    isBot = child.getBoolean("is_bot", false),
                    isYou = child.getBoolean("is_you", false),
                ),
            )
            child = child.next
        }
        return rows
    }

    private fun optionalInt(value: JsonValue, name: String): Int? {
        val field = value.get(name) ?: return null
        if (field.isNull) return null
        return field.asInt()
    }

    private fun optionalDouble(value: JsonValue, name: String): Double? {
        val field = value.get(name) ?: return null
        if (field.isNull) return null
        return field.asDouble()
    }
}
