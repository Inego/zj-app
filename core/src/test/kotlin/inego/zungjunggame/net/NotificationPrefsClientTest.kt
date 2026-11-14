package inego.zungjunggame.net

import inego.zungjunggame.persist.NotificationPrefsKey
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationPrefsClientTest {
    private fun newSlot(): StateSlot<NotificationPrefs> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), NotificationPrefsKey)

    private class FakeApi : NotificationPrefsReader, NotificationPrefsWriter {
        val reads = ArrayDeque<(Result<NotificationPrefs>) -> Unit>()
        val writes = ArrayDeque<(Result<NotificationPrefs>) -> Unit>()
        val sent = mutableListOf<NotificationPrefs>()

        override fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit) {
            reads += onDone
        }

        override fun put(
            token: String,
            prefs: NotificationPrefs,
            onDone: (Result<NotificationPrefs>) -> Unit,
        ) {
            sent += prefs
            writes += onDone
        }
    }

    @Test
    fun refreshStoresTheSettingsAndDropsAStaleResponse() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)

        client.refresh("tok")
        client.refresh("tok")
        api.reads.removeFirst()(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 6)))
        assertEquals(NotificationPrefs.DEFAULT, client.prefs, "the stale first response is dropped")

        api.reads.removeFirst()(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 6)))
        assertEquals(NotificationPrefs(true, 6), client.prefs)
    }

    @Test
    fun aWriteIsOptimisticAndRevertsWhenTheServerRefuses() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)
        client.refresh("tok")
        api.reads.removeFirst()(Result.success(NotificationPrefs(emailOptIn = false, digestIntervalHours = 3)))

        client.toggleEmailOptIn("tok")
        assertTrue(client.prefs.emailOptIn, "the card takes the new value at once")
        assertEquals(NotificationPrefs(true, 3), api.sent.single())

        api.writes.removeFirst()(Result.failure(NotificationPrefsException("refused")))
        assertEquals(NotificationPrefs(false, 3), client.prefs, "a refused write restores the last value")
        assertEquals("refused", client.error)
    }

    @Test
    fun aReadOlderThanAnEditCannotRollItBack() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)

        // Opening the card starts a read; the player edits before it lands.
        client.refresh("tok")
        client.toggleEmailOptIn("tok")
        assertTrue(client.prefs.emailOptIn, "the edit is taken at once")

        // The read's answer predates the edit. Applying it would flick the
        // checkbox back; the gate drops it instead.
        api.reads.removeFirst()(Result.success(NotificationPrefs(emailOptIn = false, digestIntervalHours = 3)))
        assertTrue(client.prefs.emailOptIn, "a read older than the edit is dropped")

        api.writes.removeFirst()(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 3)))
        assertTrue(client.prefs.emailOptIn)
    }

    @Test
    fun aConfirmedWriteTakesTheServersValue() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)
        client.refresh("tok")
        api.reads.removeFirst()(Result.success(NotificationPrefs.DEFAULT))

        client.setDigestIntervalHours("tok", 6)
        assertEquals(NotificationPrefs(false, 6), api.sent.single())

        api.writes.removeFirst()(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 12)))
        assertEquals(NotificationPrefs(true, 12), client.prefs)
        assertNull(client.error)
    }

    @Test
    fun refreshAmendsTheServerRowWithTheDeviceLanguageAndZone() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)

        client.refresh("tok", language = "en-US", timeZone = "Europe/Berlin")
        api.reads.removeFirst()(
            Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 6, language = "ru")),
        )

        // The opt-in and the cadence are the server's. Only the device context changes, and it
        // changes in one write so a zone update cannot clear the language or the other way round.
        assertEquals(
            NotificationPrefs(true, 6, "en-US", "Europe/Berlin"),
            api.sent.single(),
        )
    }

    @Test
    fun refreshDoesNotWriteWhenTheDeviceContextAlreadyMatches() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)
        val stored = NotificationPrefs(true, 6, "ru", "Europe/Berlin")

        client.refresh("tok", language = "ru", timeZone = "Europe/Berlin")
        api.reads.removeFirst()(Result.success(stored))

        assertEquals(emptyList(), api.sent)
        assertEquals(stored, client.prefs)
    }

    @Test
    fun refreshLeavesAStoredZoneWhenTheDeviceCannotNameOne() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)

        client.refresh("tok", language = "ru", timeZone = null)
        api.reads.removeFirst()(
            Result.success(NotificationPrefs(true, 6, "ru", "Europe/Berlin")),
        )

        assertEquals(emptyList(), api.sent, "a null zone is not a request to clear the stored one")
    }

    @Test
    fun setLanguageCarriesTheZoneAndKeepsTheRestOfTheRow() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)
        client.refresh("tok")
        api.reads.removeFirst()(
            Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 3, language = "en-US")),
        )

        client.setLanguage("tok", "ru", "Asia/Tokyo")

        assertEquals(NotificationPrefs(true, 3, "ru", "Asia/Tokyo"), api.sent.single())
    }

    @Test
    fun unauthorizedFailureInvokesTheCallbackAndClearResets() {
        val api = FakeApi()
        val client = NotificationPrefsClient(slot = newSlot(), reader = api, writer = api)
        var unauthorized = 0
        client.onUnauthorized = { unauthorized++ }

        client.refresh("tok")
        api.reads.removeFirst()(Result.failure(NotificationPrefsException("no", unauthorized = true)))
        assertEquals(1, unauthorized)

        client.toggleEmailOptIn("tok")
        client.clear()
        assertEquals(NotificationPrefs.DEFAULT, client.prefs)
        assertNull(client.error)
    }

    @Test
    fun onlyAServerConfirmedSettingIsRestoredOnTheNextStart() {
        val api = FakeApi()
        val slot = newSlot()
        val client = NotificationPrefsClient(slot = slot, reader = api, writer = api)

        // An edit the server has not answered is shown but not durable, so a crash
        // before the answer cannot resurrect a value the server might reject.
        client.toggleEmailOptIn("tok")
        assertEquals(
            NotificationPrefs.DEFAULT,
            NotificationPrefsClient(slot = slot, reader = api, writer = api).prefs,
            "an unconfirmed edit is not persisted",
        )

        api.writes.removeFirst()(Result.success(NotificationPrefs(emailOptIn = true, digestIntervalHours = 3)))
        assertEquals(
            NotificationPrefs(true, 3),
            NotificationPrefsClient(slot = slot, reader = api, writer = api).prefs,
            "a confirmed setting is rendered before the next start's read answers",
        )
    }
}
