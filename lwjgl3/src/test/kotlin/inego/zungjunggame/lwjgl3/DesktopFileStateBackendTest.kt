package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.StateSlot
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's Gdx-free file backend: the window key is read before libGDX
 * exists, so this is the one store that cannot resolve its root through
 * `Gdx.files`. It must write the same folder and file-per-key layout.
 */
class DesktopFileStateBackendTest {
    private val dir: File = Files.createTempDirectory("zj-desktop-state").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aKeyNameIsOneFileInTheDirectory() {
        val store = DefaultStateStore(DesktopFileStateBackend(dir))

        store.write(WindowStateKey, WindowState(width = 1600, height = 900))

        assertTrue(File(dir, WindowStateKey.fullName).isFile, "one file per key name")
    }

    @Test
    fun theGeometrySurvivesANewStoreWithoutAFlush() {
        val store = DefaultStateStore(DesktopFileStateBackend(dir))
        store.write(WindowStateKey, WindowState(width = 1280, height = 800, x = 12, y = 34))

        // A restart: nothing was flushed, because file writes are already durable.
        val restored = StateSlot(DefaultStateStore(DesktopFileStateBackend(dir)), WindowStateKey)
            .requireValue()

        assertEquals(WindowState(width = 1280, height = 800, x = 12, y = 34), restored)
    }

    @Test
    fun namesListsTheKeysOnDisk() {
        val backend = DesktopFileStateBackend(dir)
        backend.put(WindowStateKey.fullName, byteArrayOf(1))
        backend.put("session.freeplay", byteArrayOf(2))

        assertEquals(setOf(WindowStateKey.fullName, "session.freeplay"), backend.names())
    }

    @Test
    fun aMissingKeyReadsNullAndRemoveIsSafe() {
        val backend = DesktopFileStateBackend(dir)

        assertNull(backend.get(WindowStateKey.fullName))
        backend.remove(WindowStateKey.fullName)
        assertFalse(File(dir, WindowStateKey.fullName).exists())
        assertNull(backend.get(WindowStateKey.fullName))
    }

    @Test
    fun aWriteReplacesThePreviousBytes() {
        val backend = DesktopFileStateBackend(dir)
        backend.put(WindowStateKey.fullName, ByteArray(64) { 7 })
        backend.put(WindowStateKey.fullName, byteArrayOf(1, 2, 3))

        assertEquals(listOf<Byte>(1, 2, 3), backend.get(WindowStateKey.fullName)?.toList())
    }
}
