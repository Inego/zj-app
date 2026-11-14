package inego.zungjunggame.persist

import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.RecordWriter
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `auth.session` key: the profile round-trips whole, and the key is marked
 * secret so the store seals it.
 */
class AuthStateTest {

    private val user = AuthUser(
        id = "u1",
        displayName = "Alice",
        statuses = listOf("active", "beta"),
        eloRating = 1516.5,
        aliasCustomizedAt = "2026-09-01T10:00:00Z",
        startingLevel = "amateur",
        startingLevelSetAt = "2026-09-02T10:00:00Z",
    )

    @Test
    fun theStoredShapeHasNoAccessToken() {
        // A compile-time fact, pinned so the key can never grow one back:
        // AuthPersistedSession has exactly the refresh token and the profile.
        val fields = AuthPersistedSession::class.java.declaredFields.map { it.name }
        assertEquals(listOf("refreshToken", "user"), fields)
    }

    @Test
    fun theKeyIsMarkedSecret() {
        assertTrue(AuthPersistedSessionKey.secret, "the store must seal the auth session")
    }

    @Test
    fun blankTokenOrIdentityIsCorrupt() {
        val good = AuthPersistedSessionSchema.encode(
            AuthPersistedSession("refresh-opaque", user),
        )
        assertNull(AuthPersistedSessionSchema.decode(ByteArray(0)))
        assertNull(AuthPersistedSessionSchema.decode(good.copyOfRange(0, good.size - 1)))
        assertNull(AuthPersistedSessionSchema.decode(good + "1:x".toByteArray()))

        val noToken = RecordWriter().field("").field("u1").field("Alice")
            .int(0).field("").field("").field("").field("").build()
        assertNull(AuthPersistedSessionSchema.decode(noToken))
    }

    @Test
    fun forwardVersionFallsBackToTheKeyDefault() {
        val backend = InMemoryStateBackend()
        val store = DefaultStateStore(backend)
        val payload = AuthPersistedSessionSchema.encode(AuthPersistedSession("tok", user))
        backend.put(
            AuthPersistedSessionKey.fullName,
            java.nio.ByteBuffer.allocate(4 + payload.size)
                .putInt(AuthPersistedSessionSchema.version + 1)
                .put(payload)
                .array(),
        )
        assertNull(store.read(AuthPersistedSessionKey))
    }

    @Test
    fun dismissedFirstLoginPromptsRoundTrip() {
        val slot = StateSlot(DefaultStateStore(InMemoryStateBackend()), DismissedFirstLoginPromptsKey)
        assertEquals(emptySet(), slot.requireValue())

        slot.set(setOf("u1", "u2"))
        assertEquals(setOf("u1", "u2"), slot.requireValue())

        assertNull(DismissedFirstLoginPromptsSchema.decode(ByteArray(0)))
    }
}
