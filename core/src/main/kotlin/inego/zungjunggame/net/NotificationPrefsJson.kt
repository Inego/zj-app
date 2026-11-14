package inego.zungjunggame.net

import com.badlogic.gdx.utils.JsonReader

/**
 * Request/response JSON for `GET`/`PUT /notification-prefs`. No Gdx app required
 * (unit-testable).
 */
object NotificationPrefsJson {
    /** Parse the server's notification settings. */
    fun parse(body: String): NotificationPrefs {
        val root = JsonReader().parse(body)
            ?: throw IllegalArgumentException("empty notification prefs response")
        return NotificationPrefs(
            emailOptIn = root.getBoolean("email_opt_in", false),
            digestIntervalHours = NotificationPrefsScale.coerce(
                root.getInt("digest_interval_hours", NotificationPrefs.DEFAULT.digestIntervalHours),
            ),
            // Absent or JSON null both read as "nothing stored".
            language = root.getString("language", null),
            timeZone = root.getString("timezone", null),
        )
    }

    /** The `PUT /notification-prefs` body: the whole settings row. */
    fun encode(prefs: NotificationPrefs): String =
        buildString {
            append("{\"email_opt_in\":").append(prefs.emailOptIn)
            append(",\"digest_interval_hours\":").append(prefs.digestIntervalHours)
            append(",\"language\":").append(jsonString(prefs.language))
            append(",\"timezone\":").append(jsonString(prefs.timeZone))
            append('}')
        }

    /** A JSON string, or `null`. Zone ids are a closed alphabet, but the body is still escaped. */
    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}
