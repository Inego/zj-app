package inego.zungjunggame.lwjgl3

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

/**
 * Ensures the running JVM will write HotSpot fatal dumps under `~/.zj-app/`.
 *
 * Gradle `:lwjgl3:run` passes these flags. IDE run configs often do not, and a
 * native abort then leaves no `hs_err` and no `last-crash.txt`. If the flags
 * are missing, this spawns a child JVM with them and exits with that code.
 */
object DesktopCrashJvm {
    const val CHILD_PROPERTY = "zj.crashLogging"

    fun extraJvmArgs(dir: File): List<String> {
        val dirPath = dir.absolutePath.replace('\\', '/')
        val errorFile = "$dirPath/hs_err_pid%p.log"
        val heap = "$dirPath/heapdump.hprof"
        val fatal = "$dirPath/jvm-fatal.txt"
        // HotSpot wraps OnError with `cmd /C` on Windows.
        val onError = "echo pid=%p> \"$fatal\""
        return listOf(
            "-XX:ErrorFile=$errorFile",
            "-XX:+ErrorFileToStderr",
            "-XX:+CreateCoredumpOnCrash",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=$heap",
            "-XX:OnError=$onError",
        )
    }

    fun needsRelaunch(
        inputArguments: List<String>,
        alreadyChild: Boolean = isChild(),
    ): Boolean {
        if (alreadyChild) return false
        val hasErrorFile = inputArguments.any { it.startsWith("-XX:ErrorFile=") }
        val hasOnError = inputArguments.any { it.startsWith("-XX:OnError=") }
        return !(hasErrorFile && hasOnError)
    }

    fun mergeArgs(existing: List<String>, extra: List<String>): List<String> {
        val keys = extra.map { flagKey(it) }.toSet()
        val kept = existing.filter { arg -> flagKey(arg) !in keys }
        return kept + extra
    }

    /**
     * @return true if the caller should return from `main` (child already ran,
     * or this process exited).
     */
    fun relaunchIfNeeded(dir: File = DesktopDataDir.root): Boolean {
        val existing = ManagementFactory.getRuntimeMXBean().inputArguments
        if (!needsRelaunch(existing)) return false
        val javaHome = System.getProperty("java.home") ?: return false
        val javaExec = File(javaHome, "bin/java")
        val javaExe = File(javaHome, "bin/java.exe")
        val java = when {
            javaExe.exists() -> javaExe
            javaExec.exists() -> javaExec
            else -> return false
        }
        val mainClass = resolveMainClass() ?: return false
        val command = mutableListOf<String>()
        command += java.absolutePath
        command += mergeArgs(existing, extraJvmArgs(dir))
        if (DesktopDataDir.isOverridden) {
            command += "-D${DesktopDataDir.PROPERTY}=${DesktopDataDir.root.path}"
        }
        command += "-D$CHILD_PROPERTY=1"
        command += "-cp"
        command += System.getProperty("java.class.path")
        command += mainClass
        command += appArgs()
        return try {
            val code = ProcessBuilder(command).inheritIO().start().waitFor()
            exitProcess(code)
        } catch (e: Exception) {
            System.err.println("DesktopCrashJvm: failed to relaunch with crash flags")
            e.printStackTrace()
            false
        }
    }

    internal fun flagKey(arg: String): String = arg.substringBefore('=')

    internal fun isChild(): Boolean =
        System.getProperty(CHILD_PROPERTY) == "1"

    private fun resolveMainClass(): String? {
        val command = System.getProperty("sun.java.command") ?: return null
        val first = command.substringBefore(' ')
        if (first.endsWith(".jar", ignoreCase = true)) return null
        return first.ifBlank { null }
    }

    private fun appArgs(): List<String> {
        val command = System.getProperty("sun.java.command") ?: return emptyList()
        val space = command.indexOf(' ')
        if (space < 0) return emptyList()
        return command.substring(space + 1).split(' ').filter { it.isNotEmpty() }
    }
}
