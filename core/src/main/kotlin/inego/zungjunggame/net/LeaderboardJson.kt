package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Response JSON for `GET /leaderboard`. No Gdx app required (unit-testable).
 */
object LeaderboardJson {
    fun parsePage(body: String): LeaderboardPage {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty leaderboard response")
        val rowsVal = root.get("rows")
            ?: throw IllegalArgumentException("missing rows")
        val rows = ArrayList<LeaderboardRow>()
        if (!rowsVal.isNull) {
            var child = rowsVal.child
            while (child != null) {
                rows.add(parseRow(child))
                child = child.next
            }
        }
        return LeaderboardPage(
            generation = longField(root, "generation"),
            replaced = root.getBoolean("replaced", false),
            total = intField(root, "total"),
            offset = intField(root, "offset"),
            limit = intField(root, "limit"),
            rows = rows,
            self = parseSelf(root.get("self")),
        )
    }

    internal fun parseRow(value: JsonValue): LeaderboardRow = LeaderboardRow(
        place = intField(value, "place"),
        displayName = value.getString("display_name")
            ?: throw IllegalArgumentException("missing row.display_name"),
        historyId = value.getString("history_id")
            ?: throw IllegalArgumentException("missing row.history_id"),
        rating = intField(value, "rating"),
        liveGames = intField(value, "live_games"),
        shadowRankedGames = intField(value, "shadow_ranked_games"),
    )

    private fun parseSelf(value: JsonValue?): LeaderboardSelf? {
        if (value == null || value.isNull) return null
        return LeaderboardSelf(
            offset = intField(value, "offset"),
            place = intField(value, "place"),
        )
    }

    private fun intField(value: JsonValue, name: String): Int {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return field.asInt()
    }

    private fun longField(value: JsonValue, name: String): Long {
        val field = value.get(name) ?: throw IllegalArgumentException("missing $name")
        return field.asLong()
    }
}
