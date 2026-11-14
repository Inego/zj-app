package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPrefsJsonTest {
    @Test
    fun parsesTheEmailOptInAndInterval() {
        assertEquals(
            NotificationPrefsJson.parse("""{"email_opt_in":true,"digest_interval_hours":6}"""),
            NotificationPrefs(emailOptIn = true, digestIntervalHours = 6),
        )
    }

    @Test
    fun missingFieldsFallBackToTheDefaults() {
        assertEquals(
            NotificationPrefsJson.parse("{}"),
            NotificationPrefs.DEFAULT,
        )
    }

    @Test
    fun encodesTheWholeSettingsRow() {
        assertEquals(
            """{"email_opt_in":true,"digest_interval_hours":24,"language":null,"timezone":null}""",
            NotificationPrefsJson.encode(NotificationPrefs(emailOptIn = true, digestIntervalHours = 24)),
        )
    }

    @Test
    fun roundTripsTheLanguageAndTheZone() {
        val prefs = NotificationPrefs(
            emailOptIn = true,
            digestIntervalHours = 6,
            language = "ru",
            timeZone = "Europe/Berlin",
        )
        assertEquals(
            """{"email_opt_in":true,"digest_interval_hours":6,"language":"ru","timezone":"Europe/Berlin"}""",
            NotificationPrefsJson.encode(prefs),
        )
        assertEquals(prefs, NotificationPrefsJson.parse(NotificationPrefsJson.encode(prefs)))
    }

    @Test
    fun aMissingZoneReadsAsUnset() {
        assertEquals(
            NotificationPrefs(emailOptIn = true, digestIntervalHours = 6, language = "ru"),
            NotificationPrefsJson.parse(
                """{"email_opt_in":true,"digest_interval_hours":6,"language":"ru","timezone":null}""",
            ),
        )
    }
}
