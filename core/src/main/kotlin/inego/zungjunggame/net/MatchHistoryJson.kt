package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Response JSON for `GET /matches`. No Gdx app required (unit-testable).
 */
object MatchHistoryJson {
    fun parseList(body: String): MatchList {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty matches response")
        val matchesVal = root.get("matches")
            ?: throw IllegalArgumentException("missing matches")
        val matches = ArrayList<MatchSummary>()
        if (!matchesVal.isNull) {
            var child = matchesVal.child
            while (child != null) {
                matches.add(parseSummary(child))
                child = child.next
            }
        }
        return MatchList(
            matches = matches,
            total = intField(root, "total"),
            limit = intField(root, "limit"),
            offset = intField(root, "offset"),
        )
    }

    fun parseStatus(raw: String): MatchStatus =
        when (raw) {
            "finished" -> MatchStatus.Finished
            "left" -> MatchStatus.Left
            "aborted" -> MatchStatus.Aborted
            else -> MatchStatus.Finished
        }

    internal fun parseSummary(value: JsonValue): MatchSummary {
        val id = value.getString("id")
            ?: throw IllegalArgumentException("missing match.id")
        val endedAt = value.getString("ended_at")
            ?: throw IllegalArgumentException("missing match.ended_at")
        val rounds = value.getString("rounds")
            ?: throw IllegalArgumentException("missing match.rounds")
        if (!value.has("allow_chicken")) {
            throw IllegalArgumentException("missing match.allow_chicken")
        }
        val statusRaw = value.getString("status", "finished")
        return MatchSummary(
            id = id,
            endedAt = endedAt,
            rounds = rounds,
            allowChicken = value.getBoolean("allow_chicken"),
            status = parseStatus(statusRaw),
            placement = intField(value, "placement"),
            finalScore = intField(value, "final_score"),
            oldRating = optionalDouble(value, "old_rating"),
            newRating = optionalDouble(value, "new_rating"),
            delta = optionalDouble(value, "delta"),
        )
    }

    private fun intField(value: JsonValue, name: String): Int {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return field.asInt()
    }

    private fun optionalDouble(value: JsonValue, name: String): Double? {
        val field = value.get(name) ?: return null
        if (field.isNull) return null
        return field.asDouble()
    }
}
