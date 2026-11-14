package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue

/**
 * Request/response JSON for `/presence`. No Gdx app required (unit-testable).
 */
object PresenceJson {
    fun heartbeatRequestBody(
        activity: PresenceActivity,
        minOtherHumans: Int? = null,
        rounds: QueueRounds? = null,
        chicken: QueueChicken? = null,
    ): String {
        if (activity != PresenceActivity.Waiting) {
            return """{"activity":${AuthJson.quote(wireActivity(activity))}}"""
        }
        val m = minOtherHumans ?: 1
        val r = rounds ?: QueueRounds.Either
        val c = chicken ?: QueueChicken.Either
        return """{"activity":"waiting","min_other_humans":$m,"rounds":${AuthJson.quote(wireRounds(r))},"chicken":${AuthJson.quote(wireChicken(c))}}"""
    }

    fun parseSnapshot(body: String): PresenceSnapshot {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty presence response")
        if (!root.has("online") || !root.has("playing") || !root.has("waiting")) {
            throw IllegalArgumentException("missing presence counts")
        }
        val selfVal = root.get("self")
        val self = if (selfVal == null || selfVal.isNull) null else parseSelf(selfVal)
        val shutdownVal = root.get("server_shutdown")
        val serverShutdown = if (shutdownVal == null || shutdownVal.isNull) {
            null
        } else {
            val message = if (shutdownVal.has("message") && !shutdownVal.get("message").isNull) {
                shutdownVal.getString("message")
            } else {
                null
            }
            PresenceServerShutdown(
                phase = shutdownVal.getString("phase", "warning"),
                remainingMs = shutdownVal.getLong("remaining_ms", 0L),
                message = message,
            )
        }
        return PresenceSnapshot(
            online = root.getInt("online"),
            playing = root.getInt("playing"),
            waiting = root.getInt("waiting"),
            self = self,
            needsStartingLevel = root.getBoolean("needs_starting_level", false),
            serverShutdown = serverShutdown,
        )
    }

    fun parseActivity(raw: String): PresenceActivity =
        when (raw) {
            "idle" -> PresenceActivity.Idle
            "waiting" -> PresenceActivity.Waiting
            "playing" -> PresenceActivity.Playing
            else -> throw IllegalArgumentException("unknown activity $raw")
        }

    fun wireActivity(activity: PresenceActivity): String =
        when (activity) {
            PresenceActivity.Idle -> "idle"
            PresenceActivity.Waiting -> "waiting"
            PresenceActivity.Playing -> "playing"
        }

    fun parseRounds(raw: String): QueueRounds =
        when (raw) {
            "one" -> QueueRounds.One
            "two" -> QueueRounds.Two
            "either" -> QueueRounds.Either
            else -> throw IllegalArgumentException("unknown rounds $raw")
        }

    fun wireRounds(rounds: QueueRounds): String =
        when (rounds) {
            QueueRounds.One -> "one"
            QueueRounds.Two -> "two"
            QueueRounds.Either -> "either"
        }

    fun parseChicken(raw: String): QueueChicken =
        when (raw) {
            "allowed" -> QueueChicken.Allowed
            "disallowed", "not_allowed" -> QueueChicken.Disallowed
            "either" -> QueueChicken.Either
            else -> throw IllegalArgumentException("unknown chicken $raw")
        }

    fun wireChicken(chicken: QueueChicken): String =
        when (chicken) {
            QueueChicken.Allowed -> "allowed"
            QueueChicken.Disallowed -> "disallowed"
            QueueChicken.Either -> "either"
        }

    private fun parseSelf(value: JsonValue): PresenceSelf {
        val userId = value.getString("user_id")
            ?: throw IllegalArgumentException("missing self.user_id")
        val displayName = value.getString("display_name")
            ?: throw IllegalArgumentException("missing self.display_name")
        val activityRaw = value.getString("activity")
            ?: throw IllegalArgumentException("missing self.activity")
        val queueVal = value.get("queue")
        val queue = if (queueVal == null || queueVal.isNull) null else parseQueue(queueVal)
        val tableVal = value.get("table")
        val table = if (tableVal == null || tableVal.isNull) null else parseTable(tableVal)
        val lastMatchVal = value.get("last_match")
        val lastMatch = if (lastMatchVal == null || lastMatchVal.isNull) {
            null
        } else {
            MatchHistoryJson.parseSummary(lastMatchVal)
        }
        val cooldownVal = value.get("matchmaking_cooldown")
        val cooldown = if (cooldownVal == null || cooldownVal.isNull) {
            null
        } else {
            // Lenient about the reason: it is an operator-facing label, and a cooldown's
            // seconds are what the control acts on.
            MatchmakingCooldown(
                secondsRemaining = cooldownVal.getInt("seconds_remaining", 0),
                reason = cooldownVal.getString("reason") ?: "",
            )
        }
        return PresenceSelf(
            userId = userId,
            displayName = displayName,
            activity = parseActivity(activityRaw),
            queue = queue,
            table = table,
            lastMatch = lastMatch,
            matchmakingCooldown = cooldown,
        )
    }

    private fun parseQueue(value: JsonValue): PresenceQueue {
        val minOtherHumans = value.getInt("min_other_humans")
        val roundsRaw = value.getString("rounds")
            ?: throw IllegalArgumentException("missing queue.rounds")
        val chickenRaw = value.getString("chicken")
        val chicken = if (chickenRaw != null) parseChicken(chickenRaw) else QueueChicken.Either
        return PresenceQueue(
            minOtherHumans = minOtherHumans,
            rounds = parseRounds(roundsRaw),
            chicken = chicken,
        )
    }

    private fun parseTable(value: JsonValue): PresenceTable {
        val id = value.getString("id")
            ?: throw IllegalArgumentException("missing table.id")
        val roundsRaw = value.getString("rounds")
            ?: throw IllegalArgumentException("missing table.rounds")
        val allowChicken = if (value.has("allow_chicken")) {
            value.getBoolean("allow_chicken")
        } else if (value.has("chicken")) {
            value.getString("chicken") == "allowed"
        } else {
            false
        }
        val humans = value.getInt("humans")
        val bots = value.getInt("bots")
        return PresenceTable(
            id = id,
            rounds = parseRounds(roundsRaw),
            allowChicken = allowChicken,
            humans = humans,
            bots = bots,
        )
    }
}
