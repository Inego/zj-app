package inego.zungjunggame.state

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle

/**
 * Platform storage root for file-backed state, or null without libGDX.
 *
 * - **Android / WebGL:** `Gdx.files.local` (private app storage / IndexedDB).
 *   TeaVM has no `Gdx.files.external`.
 * - **Desktop:** [DESKTOP_ROOT] under the user home, unless the launcher
 *   installed [FileStateBackend.rootOverride]. Desktop `run` uses `assets/` as
 *   the working directory, so `Gdx.files.local` would write into packaged
 *   assets; external keeps saves out of the project tree.
 */
private fun platformRoot(): FileHandle? {
    val files = Gdx.files ?: return null
    return when (Gdx.app?.type) {
        Application.ApplicationType.Android,
        Application.ApplicationType.WebGL,
        -> files.local("")

        else -> {
            val override = FileStateBackend.rootOverride
            if (override != null) files.absolute(override) else files.external(FileStateBackend.DESKTOP_ROOT)
        }
    }
}

/**
 * [StateBackend] over one directory of byte-blob files, one file per key name.
 *
 * For state too large for `Preferences` (a whole board snapshot). Writes are
 * immediate, so [flush] has nothing to do. A missing [Gdx.files] (plain JVM unit
 * tests) makes every operation a no-op rather than an NPE.
 */
class FileStateBackend(
    private val directoryName: String,
) : StateBackend {

    private var blockedLogged = false

    private fun directory(): FileHandle? {
        val dir = platformRoot()?.child(directoryName) ?: return null
        // A file at this path (a preferences container of the same name) can
        // never be a directory. Say so once instead of failing every read and
        // write in silence, which looks exactly like "nothing is saved yet".
        if (dir.exists() && !dir.isDirectory) {
            if (!blockedLogged) {
                blockedLogged = true
                Gdx.app?.error(TAG, "State path ${dir.path()} is a file, not a directory: nothing persists here")
            }
            return null
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** The file holding [name], or null when no storage is available. */
    fun file(name: String): FileHandle? = directory()?.child(name)

    override fun isAvailable(): Boolean = directory() != null

    override fun get(name: String): ByteArray? {
        val file = file(name) ?: return null
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "Failed to read $name: ${e.message}")
            null
        }
    }

    override fun put(name: String, value: ByteArray) {
        try {
            file(name)?.writeBytes(value, false)
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "Failed to write $name: ${e.message}")
        }
    }

    override fun remove(name: String) {
        try {
            val file = file(name) ?: return
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "Failed to remove $name: ${e.message}")
        }
    }

    override fun names(): Set<String> = directory()?.list()?.map { it.name() }?.toSet() ?: emptySet()

    override fun flush() = Unit

    companion object {
        const val TAG = "FileStateBackend"
        const val DESKTOP_ROOT = ".zj-app/"

        /**
         * Absolute folder the desktop launcher redirects file-backed state to,
         * or null for the platform default. Set before the graph is built (the
         * desktop `--data-dir` override); ignored on Android / WebGL, whose
         * private storage has no user-chosen location.
         */
        @Volatile
        var rootOverride: String? = null

        /**
         * Folder under the platform root that holds the keyed file state.
         *
         * Shared with desktop code that opens the store **before libGDX exists**
         * (the window key), which cannot use this backend: its root comes from
         * `Gdx.files`. Both resolve `<platform root>/<STATE_DIR>/<key name>`.
         *
         * Must stay different from the preferences container name
         * ([PreferencesStateBackend.STATE_NAME]): on desktop that container is a
         * file at the same depth, and one path cannot be both.
         */
        const val STATE_DIR = "zj-files"
    }
}
