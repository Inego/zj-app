package inego.zungjunggame.lwjgl3

import inego.zungjunggame.state.FileStateBackend
import inego.zungjunggame.state.StateBackend
import java.io.File

/**
 * [StateBackend] over the desktop's keyed state folder, using plain Java I/O.
 *
 * The core [FileStateBackend] resolves its root through `Gdx.files`, which does
 * not exist when the launcher restores the window geometry — that read happens
 * **before** libGDX boots. This backend writes the same folder through [File]
 * (`<DesktopDataDir.root>/zj-files/<key name>`), so a desktop key is read the
 * same way whether or not libGDX is up.
 *
 * Writes are immediate, so [flush] has nothing to do, and every failure is
 * best-effort: losing this state only means the defaults.
 */
class DesktopFileStateBackend(
    private val directory: File = stateDirectory(),
) : StateBackend {

    override fun isAvailable(): Boolean = directory.isDirectory || directory.mkdirs()

    override fun get(name: String): ByteArray? = try {
        val file = File(directory, name)
        if (file.isFile) file.readBytes() else null
    } catch (_: Exception) {
        null
    }

    override fun put(name: String, value: ByteArray) {
        try {
            if (!directory.isDirectory) directory.mkdirs()
            File(directory, name).writeBytes(value)
        } catch (_: Exception) {
            // Best-effort; never block a quit on state I/O.
        }
    }

    override fun remove(name: String) {
        try {
            val file = File(directory, name)
            if (file.isFile) file.delete()
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    override fun names(): Set<String> = try {
        directory.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    override fun flush() = Unit

    companion object {
        /** The desktop data root (`~/.zj-app`, or the `--data-dir` override). */
        fun desktopRoot(): File = DesktopDataDir.root

        /** `~/.zj-app/zj-files`: the folder the core file store uses on desktop. */
        fun stateDirectory(): File = File(desktopRoot(), FileStateBackend.STATE_DIR)
    }
}
