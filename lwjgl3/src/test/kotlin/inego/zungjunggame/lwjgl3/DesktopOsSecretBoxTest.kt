package inego.zungjunggame.lwjgl3

import inego.zungjunggame.net.AuthLogin
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.persist.AuthPersistedSessionKey
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import org.junit.Assume
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop secret box and its wiring into the state store.
 *
 * The platform-specific guarantee is "the refresh token is never plaintext on
 * disk, and a box that cannot be created leaves the session memory-only".
 */
class DesktopOsSecretBoxTest {
    private val user = AuthUser(
        id = "u1",
        displayName = "Alice",
        eloRating = 1516.0,
    )

    private val plaintext = "refresh-opaque".toByteArray(Charsets.UTF_8)

    @Test
    fun sealOpenRoundTrip() {
        val box = DesktopOsBox.create(MemoryOsSecretStore())!!
        val blob = box.seal(plaintext)
        assertNotNull(blob)
        assertFalse(blob.toString(Charsets.ISO_8859_1).contains("refresh-opaque"))
        assertEquals("refresh-opaque", box.open(blob)?.toString(Charsets.UTF_8))
    }

    @Test
    fun distinctIvsForSamePlaintext() {
        val box = DesktopOsBox.create(MemoryOsSecretStore())!!
        val a = box.seal(plaintext)
        val b = box.seal(plaintext)
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(a.toList(), b.toList())
        assertEquals("refresh-opaque", box.open(a)?.toString(Charsets.UTF_8))
        assertEquals("refresh-opaque", box.open(b)?.toString(Charsets.UTF_8))
    }

    @Test
    fun wrappingKeyIsReusedAcrossBoxes() {
        val secrets = MemoryOsSecretStore()
        val first = DesktopOsBox.create(secrets)!!
        val blob = first.seal(plaintext)!!
        val second = DesktopOsBox.create(secrets)!!
        assertEquals("refresh-opaque", second.open(blob)?.toString(Charsets.UTF_8))
    }

    @Test
    fun tamperedBlobFailsOpen() {
        val box = DesktopOsBox.create(MemoryOsSecretStore())!!
        val raw = box.seal(plaintext)!!
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        assertNull(box.open(raw))
    }

    @Test
    fun wrongVersionFailsOpen() {
        val box = DesktopOsBox.create(MemoryOsSecretStore())!!
        val raw = box.seal(plaintext)!!
        raw[0] = 99
        assertNull(box.open(raw))
    }

    @Test
    fun truncatedBlobFailsOpen() {
        val box = DesktopOsBox.create(MemoryOsSecretStore())!!
        assertNull(box.open(ByteArray(0)))
        assertNull(box.open("AAAA".toByteArray()))
    }

    @Test
    fun createReturnsNullWhenWrappingKeyCannotBeStored() {
        assertNull(DesktopOsBox.create(FailingOsSecretStore()))
        assertNull(DesktopOsBox.create(null))
    }

    @Test
    fun theSessionIsSealedInTheStoreAndReloads() {
        val backend = InMemoryStateBackend()
        val secrets = MemoryOsSecretStore()
        val slot = StateSlot(
            DefaultStateStore(backend, secretBox = DesktopOsSecretBox.create(DesktopOsBox.create(secrets))),
            AuthPersistedSessionKey,
        )
        val session = AuthSession(slot)
        assertTrue(session.signIn(AuthLogin("access-jwt", user, "refresh-opaque")))

        val stored = assertNotNull(backend.get(AuthPersistedSessionKey.fullName))
        val text = stored.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("refresh-opaque"), "the refresh token must not be readable on disk")
        assertFalse(text.contains("access-jwt"))

        val reloaded = AuthSession(slot)
        reloaded.load(slot.get())
        assertNull(reloaded.token, "the access JWT is memory-only")
        assertEquals("refresh-opaque", reloaded.refreshToken)
        assertEquals("Alice", reloaded.user?.displayName)
    }

    /**
     * A desktop with no OS credential store must not fall back to plaintext: the
     * session stays in memory and the store is left empty.
     */
    @Test
    fun aMissingOsStoreKeepsTheSessionMemoryOnly() {
        val backend = InMemoryStateBackend()
        val slot = StateSlot(
            DefaultStateStore(backend, secretBox = DesktopOsSecretBox.create(null)),
            AuthPersistedSessionKey,
        )
        val session = AuthSession(slot)

        val landed = session.signIn(AuthLogin("access-jwt", user, "refresh-opaque"))

        assertFalse(landed)
        assertNull(backend.get(AuthPersistedSessionKey.fullName))
        assertEquals("refresh-opaque", session.refreshToken, "the session stays usable in memory")
    }

    @Test
    fun openReturnsWindowsStoreOnWindows() {
        Assume.assumeTrue(isWindows())
        assertIs<WindowsCredentialStore>(OsSecretStore.open())
    }

    @Test
    fun windowsCredentialManagerRoundTrip() {
        Assume.assumeTrue(isWindows())
        val store = WindowsCredentialStore(
            "inego.zungjunggame/auth.refresh-key.test.${UUID.randomUUID()}",
        )
        try {
            assertTrue(store.write("wrap-key-one"))
            assertEquals("wrap-key-one", store.read())
            assertTrue(store.write("wrap-key-two"))
            assertEquals("wrap-key-two", store.read())
        } finally {
            assertTrue(store.delete())
            assertNull(store.read())
        }
    }

    @Test
    fun windowsWrappingKeyRoundTripDecryptsAcrossBoxes() {
        Assume.assumeTrue(isWindows())
        val secrets = WindowsCredentialStore(
            "inego.zungjunggame/auth.refresh-key.test.${UUID.randomUUID()}",
        )
        try {
            val first = DesktopOsBox.create(secrets)
            assertNotNull(first)
            val blob = first.seal(plaintext)
            assertNotNull(blob)
            val second = DesktopOsBox.create(secrets)
            assertNotNull(second)
            assertEquals("refresh-opaque", second.open(blob)?.toString(Charsets.UTF_8))
        } finally {
            secrets.delete()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
}

private class MemoryOsSecretStore : OsSecretStore {
    private var value: String? = null
    override fun write(value: String): Boolean {
        this.value = value
        return true
    }
    override fun read(): String? = value
    override fun delete(): Boolean {
        value = null
        return true
    }
}

private class FailingOsSecretStore : OsSecretStore {
    override fun write(value: String): Boolean = false
    override fun read(): String? = null
    override fun delete(): Boolean = false
}
