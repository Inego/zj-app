package inego.zungjunggame.net

import inego.zungjunggame.persist.AuthPersistedSession
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.SecretBox
import inego.zungjunggame.state.StateSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionTest {
    private val user = AuthUser(
        id = "u1",
        displayName = "Alice",
        eloRating = 1516.0,
    )

    private fun authSlot(
        backend: InMemoryStateBackend = InMemoryStateBackend(),
        box: SecretBox? = null,
    ): StateSlot<AuthPersistedSession> =
        StateSlot(DefaultStateStore(backend, secretBox = box), AuthPersistedSessionKey)

    private fun rawStored(backend: InMemoryStateBackend): String =
        assertNotNull(backend.get(AuthPersistedSessionKey.fullName)).toString(Charsets.ISO_8859_1)

    @Test
    fun signInStoresOnlyTheRefreshSession() {
        val backend = InMemoryStateBackend()
        val session = AuthSession(authSlot(backend))
        session.signIn(AuthLogin("access-jwt", user, "refresh-opaque"))

        assertEquals("access-jwt", session.token)
        assertEquals("refresh-opaque", session.refreshToken)
        assertTrue(session.isSignedIn)

        val stored = assertNotNull(authSlot(backend).get())
        assertEquals("refresh-opaque", stored.refreshToken)
        assertEquals("u1", stored.user.id)
        assertEquals("Alice", stored.user.displayName)
        assertEquals(1516.0, stored.user.eloRating)
        assertFalse(
            rawStored(backend).contains("access-jwt"),
            "the access JWT is memory-only on every platform",
        )
    }

    @Test
    fun reloadRestoresStoredSessionWithoutAccessToken() {
        val backend = InMemoryStateBackend()
        AuthSession(authSlot(backend)).signIn(AuthLogin("access-jwt", user, "refresh-opaque"))

        val reloaded = AuthSession(authSlot(backend))
        reloaded.load(authSlot(backend).get())

        assertNull(reloaded.token, "the access JWT is memory-only")
        assertEquals("refresh-opaque", reloaded.refreshToken)
        assertEquals("Alice", reloaded.user?.displayName)
        assertFalse(reloaded.isSignedIn, "no access token until the refresh rotation lands")
    }

    @Test
    fun signOutClearsTheStoredSession() {
        val backend = InMemoryStateBackend()
        val session = AuthSession(authSlot(backend))
        session.signIn(AuthLogin("access-jwt", user, "refresh-opaque"))
        session.signOut()

        assertNull(authSlot(backend).get(), "a reload after sign-out must stay signed out")

        val reloaded = AuthSession(authSlot(backend))
        reloaded.load(authSlot(backend).get())
        assertNull(reloaded.refreshToken)
        assertNull(reloaded.user)
        assertFalse(reloaded.isSignedIn)
    }

    @Test
    fun applyPersistedRestoresRefreshWithoutAccessToken() {
        val session = AuthSession(authSlot())
        session.applyPersisted("refresh-opaque", user)
        assertNull(session.token)
        assertEquals("refresh-opaque", session.refreshToken)
        assertEquals("Alice", session.user?.displayName)
        assertFalse(session.isSignedIn)
    }

    @Test
    fun anEmptyStoreLoadsNothing() {
        val slot = authSlot()
        val session = AuthSession(slot)
        session.load(slot.get())
        assertNull(session.user)
        assertNull(session.refreshToken)
    }

    /**
     * The secret box is a store capability: a marked key is sealed, so the bytes
     * on disk are not the session, and reading it back still returns the session.
     */
    @Test
    fun aSecretBoxKeepsTheSessionOutOfTheStoredBytes() {
        val backend = InMemoryStateBackend()
        val box = XorSecretBox
        val session = AuthSession(authSlot(backend, box))
        assertTrue(session.signIn(AuthLogin("access-jwt", user, "refresh-opaque")))

        val raw = rawStored(backend)
        assertFalse(raw.contains("refresh-opaque"), "a sealed key must not hold its plaintext")
        assertFalse(raw.contains("alice"))

        assertEquals("refresh-opaque", assertNotNull(authSlot(backend, box).get()).refreshToken)
    }

}

/** Reversible byte shuffle: enough to prove the store sealed the value. */
private object XorSecretBox : SecretBox {
    private const val KEY = 0x5A

    override fun seal(plain: ByteArray): ByteArray =
        ByteArray(plain.size) { (plain[it].toInt() xor KEY).toByte() }

    override fun open(sealed: ByteArray): ByteArray =
        ByteArray(sealed.size) { (sealed[it].toInt() xor KEY).toByte() }
}

