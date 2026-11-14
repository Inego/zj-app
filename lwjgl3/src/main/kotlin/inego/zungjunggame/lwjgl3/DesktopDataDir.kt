package inego.zungjunggame.lwjgl3

import java.io.File

/**
 * The folder every desktop-local artifact lives in: preferences, file-backed
 * state, session save, window geometry, logs and crash dumps.
 *
 * Defaults to `~/.zj-app`. The `--data-dir <path>` launch argument (or the
 * equivalent [PROPERTY] system property, which [install] also sets so the
 * [StartupHelper] / [DesktopCrashJvm] child JVMs inherit it) redirects the
 * whole folder. This is a dev/test convenience: several clients can run side
 * by side with independent state and sign-ins.
 *
 * [install] must run before anything reads the folder — including the crash-JVM
 * relaunch and the window geometry the launcher reads before libGDX boots.
 */
object DesktopDataDir {
    const val ARG = "--data-dir"
    const val PROPERTY = "zj.dataDir"
    const val DEFAULT_DIR_NAME = ".zj-app"

    @Volatile
    private var override: File? = null

    /** The effective data root: the installed override, else `~/.zj-app`. */
    val root: File get() = override ?: defaultRoot()

    val isOverridden: Boolean get() = override != null

    /** `~/.zj-app`, the folder used when no override is installed. */
    fun defaultRoot(): File = File(System.getProperty("user.home"), DEFAULT_DIR_NAME)

    /**
     * Resolve the data folder from [args] (or from [PROPERTY], which a parent
     * JVM exported) and install it. Returns [args] with the consumed flag
     * removed, so the caller can keep the app-argument list clean.
     */
    fun install(args: Array<String>): Array<String> {
        val (remaining, fromArgs) = splitOverride(args)
        val resolved = fromArgs ?: System.getProperty(PROPERTY)
        if (!resolved.isNullOrBlank()) {
            val dir = File(resolved).absoluteFile
            override = dir
            System.setProperty(PROPERTY, dir.path)
        }
        return remaining.toTypedArray()
    }

    /**
     * Pure parse of `--data-dir <path>` / `--data-dir=<path>` out of [args].
     * Last occurrence wins; a flag with no value is dropped. Returns the value
     * (null when absent) and the args that are not part of the flag.
     */
    internal fun splitOverride(args: Array<String>): Pair<List<String>, String?> {
        val remaining = mutableListOf<String>()
        var value: String? = null
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == ARG -> {
                    val next = args.getOrNull(i + 1)
                    if (!next.isNullOrBlank()) {
                        value = next
                        i++
                    }
                }

                arg.startsWith("$ARG=") ->
                    arg.substring(ARG.length + 1).takeIf { it.isNotBlank() }?.let { value = it }

                else -> remaining += arg
            }
            i++
        }
        return remaining to value
    }
}
