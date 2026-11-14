package inego.zungjunggame.lwjgl3

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.ApplicationLogger
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationLogger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop session log + last-crash dump.
 *
 * libGDX / LWJGL3 only print to stdout/stderr. A Java exception is written to
 * [crashFile]. A native abort never runs Java handlers, so this also:
 *  - force-flushes the session log from a daemon pinger
 *  - writes [aliveFile] so the last Java-side moment survives
 *  - dumps all threads from a shutdown hook if the process exits uncleanly
 *
 * Lives in the desktop data folder ([DesktopDataDir.root], `~/.zj-app/` by
 * default), same folder as session save and window state. Installed with plain
 * Java I/O before [com.badlogic.gdx.Gdx] exists.
 */
class DesktopLog private constructor(
    val dir: File,
) {
    val logFile: File = File(dir, LOG_FILE)
    val crashFile: File = File(dir, CRASH_FILE)
    val aliveFile: File = File(dir, ALIVE_FILE)
    val alivePrevFile: File = File(dir, ALIVE_PREV_FILE)

    private val lock = Any()
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var fileStream: FileOutputStream? = null
    private val pingerStop = AtomicBoolean(false)
    private var pinger: Thread? = null

    fun begin() {
        if (!dir.exists()) dir.mkdirs()
        val previousAlive = rotateAlive()
        synchronized(lock) {
            FileOutputStream(logFile, false).use { out ->
                out.write(header(previousAlive).toByteArray(UTF8))
                try {
                    out.fd.sync()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Tee System.out / err into [logFile] and install an uncaught handler. */
    fun captureProcessStreams() {
        synchronized(lock) {
            if (originalOut != null) return
            originalOut = System.out
            originalErr = System.err
            val file = FileOutputStream(logFile, true)
            fileStream = file
            val stamped = LinePrefixOutputStream(file, lock) { timestampPrefix() }
            System.setOut(PrintStream(TeeOutputStream(originalOut!!, stamped), true, UTF8))
            System.setErr(PrintStream(TeeOutputStream(originalErr!!, stamped), true, UTF8))
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            crash(thread, error, "uncaught")
            previous?.uncaughtException(thread, error)
        }
        Runtime.getRuntime().addShutdownHook(
            Thread({
                pingerStop.set(true)
                if (!crashWritten.get() && !cleanExit.get()) {
                    writeUncleanExit()
                } else if (cleanExit.get()) {
                    writeAlive("clean")
                }
                flushAndSync()
            }, "zj-desktop-log-shutdown"),
        )
        startPinger()
    }

    fun crash(thread: Thread, error: Throwable, where: String = "uncaught") {
        crashWritten.set(true)
        note("$where ${error::class.java.name}: ${error.message ?: ""}")
        val body = formatCrash(thread, error, where)
        if (!dir.exists()) dir.mkdirs()
        synchronized(lock) {
            crashFile.writeText(body, UTF8)
            try {
                FileOutputStream(crashFile, true).use { it.fd.sync() }
            } catch (_: Exception) {
            }
            if (fileStream == null) {
                FileOutputStream(logFile, true).use { out ->
                    out.write(body.toByteArray(UTF8))
                    out.flush()
                    try {
                        out.fd.sync()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        System.err.print(body)
        System.err.flush()
        writeAlive("crash")
        flushAndSync()
    }

    fun flushAndSync() {
        synchronized(lock) {
            fileStream?.flush()
            try {
                fileStream?.fd?.sync()
            } catch (_: Exception) {
            }
        }
    }

    fun markCleanExit() {
        if (crashWritten.get()) return
        cleanExit.set(true)
        note("clean-exit")
    }

    fun note(message: String) {
        lastBreadcrumb.set(message.take(400))
    }

    private fun startPinger() {
        if (pinger != null) return
        pingerStop.set(false)
        val thread = Thread({
            while (!pingerStop.get()) {
                writeAlive("running")
                flushAndSync()
                try {
                    Thread.sleep(PING_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "zj-desktop-log-ping")
        thread.isDaemon = true
        pinger = thread
        thread.start()
    }

    private fun writeAlive(status: String) {
        val body = buildString {
            append(CLOCK.format(Instant.now()))
            append(" status=").append(status)
            append(" pid=").append(ProcessHandle.current().pid())
            append(" breadcrumb=").append(lastBreadcrumb.get() ?: "-")
            append('\n')
        }
        try {
            if (!dir.exists()) dir.mkdirs()
            RandomAccessAlive.write(aliveFile, body)
        } catch (_: Exception) {
        }
    }

    private fun writeUncleanExit() {
        val body = formatUncleanExit(lastBreadcrumb.get())
        try {
            if (!dir.exists()) dir.mkdirs()
            crashFile.writeText(body, UTF8)
            FileOutputStream(crashFile, true).use { it.fd.sync() }
        } catch (_: Exception) {
        }
        try {
            System.err.print(body)
            System.err.flush()
        } catch (_: Exception) {
        }
        writeAlive("unclean-exit")
    }

    private fun rotateAlive(): String? {
        if (!aliveFile.exists()) return null
        return try {
            val text = aliveFile.readText(UTF8)
            Files.copy(aliveFile.toPath(), alivePrevFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            text
        } catch (_: Exception) {
            null
        }
    }

    private fun header(previousAlive: String?): String = buildString {
        append("=== zj-game desktop session ").append(CLOCK.format(Instant.now())).append(" ===\n")
        append("java=").append(System.getProperty("java.version"))
        append(" ").append(System.getProperty("java.vendor")).append('\n')
        append("os=").append(System.getProperty("os.name"))
        append(" ").append(System.getProperty("os.version"))
        append(" ").append(System.getProperty("os.arch")).append('\n')
        append("pid=").append(ProcessHandle.current().pid()).append('\n')
        append("cwd=").append(File(".").canonicalPath).append('\n')
        append("vmArgs=").append(ManagementFactory.getRuntimeMXBean().inputArguments).append('\n')
        append("errorFile=").append(File(dir, "hs_err_pid%p.log").absolutePath).append('\n')
        if (!previousAlive.isNullOrBlank()) {
            append("previous-alive=").append(previousAlive.trim().replace("\n", " | ")).append('\n')
        }
    }

    companion object {
        const val LOG_FILE = "zj-desktop.log"
        const val CRASH_FILE = "last-crash.txt"
        const val ALIVE_FILE = "zj-desktop-alive.txt"
        const val ALIVE_PREV_FILE = "zj-desktop-alive.prev.txt"
        const val PING_MS = 250L

        private val UTF8 = StandardCharsets.UTF_8
        private val CLOCK: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        private val installed = AtomicBoolean(false)
        private val crashWritten = AtomicBoolean(false)
        private val cleanExit = AtomicBoolean(false)
        private val lastBreadcrumb = AtomicReference<String?>(null)

        @Volatile
        private var instance: DesktopLog? = null

        /**
         * Truncate the session log, tee stdout/stderr, and catch uncaught
         * exceptions. Safe to call once; later calls are no-ops.
         */
        fun install(dir: File = DesktopDataDir.root): DesktopLog {
            val existing = instance
            if (existing != null && installed.get()) return existing
            val log = DesktopLog(dir)
            log.begin()
            log.captureProcessStreams()
            instance = log
            installed.set(true)
            println("Desktop log: ${log.logFile.absolutePath}")
            println("Desktop crash: ${log.crashFile.absolutePath}")
            println("Desktop alive: ${log.aliveFile.absolutePath}")
            return log
        }

        fun crash(thread: Thread, error: Throwable, where: String = "uncaught") {
            val log = instance ?: DesktopLog(DesktopDataDir.root)
            log.crash(thread, error, where)
        }

        fun note(message: String) {
            instance?.note(message)
        }

        /** Log lifecycle throws (create/render/...) then rethrow so LWJGL still exits. */
        fun guard(listener: ApplicationListener): ApplicationListener =
            object : ApplicationListener {
                override fun create() = guarded("create") {
                    attachApplicationLogger()
                    listener.create()
                    logGlInfo()
                }

                override fun resize(width: Int, height: Int) =
                    guarded("resize") { listener.resize(width, height) }

                override fun render() = guarded("render") { listener.render() }

                override fun pause() = guarded("pause") { listener.pause() }

                override fun resume() = guarded("resume") { listener.resume() }

                override fun dispose() = guarded("dispose") {
                    listener.dispose()
                    instance?.markCleanExit()
                }
            }

        internal fun formatCrash(thread: Thread, error: Throwable, where: String): String {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            return buildString {
                append("=== CRASH ").append(CLOCK.format(Instant.now()))
                append(" where=").append(where)
                append(" thread=").append(thread.name)
                append(" ===\n")
                append(error::class.java.name)
                val message = error.message
                if (!message.isNullOrEmpty()) {
                    append(": ").append(message)
                }
                append('\n')
                append(sw.toString())
                if (!endsWith('\n')) append('\n')
            }
        }

        internal fun formatUncleanExit(breadcrumb: String?): String {
            val traces = Thread.getAllStackTraces()
            return buildString {
                append("=== UNCLEAN EXIT ").append(CLOCK.format(Instant.now()))
                append(" pid=").append(ProcessHandle.current().pid())
                append(" ===\n")
                append("breadcrumb=").append(breadcrumb ?: "-").append('\n')
                append("threads=").append(traces.size).append('\n')
                for ((thread, stack) in traces) {
                    append('"').append(thread.name).append('"')
                    append(" id=").append(thread.threadId())
                    append(" state=").append(thread.state)
                    if (thread.isDaemon) append(" daemon")
                    append('\n')
                    for (el in stack) {
                        append("    at ").append(el).append('\n')
                    }
                    append('\n')
                }
            }
        }

        internal fun forTests(dir: File): DesktopLog = DesktopLog(dir)

        private fun guarded(phase: String, block: () -> Unit) {
            if (phase != "render") note("ApplicationListener.$phase")
            try {
                block()
            } catch (t: Throwable) {
                crash(Thread.currentThread(), t, "ApplicationListener.$phase")
                throw t
            }
        }

        private fun attachApplicationLogger() {
            try {
                val app = Gdx.app ?: return
                app.applicationLogger = object : ApplicationLogger {
                    private val default = Lwjgl3ApplicationLogger()

                    override fun log(tag: String, message: String) {
                        note("$tag: $message")
                        default.log(tag, message)
                    }

                    override fun log(tag: String, message: String, exception: Throwable) {
                        note("$tag: $message")
                        default.log(tag, message, exception)
                        instance?.flushAndSync()
                    }

                    override fun error(tag: String, message: String) {
                        note("$tag ERROR: $message")
                        default.error(tag, message)
                        instance?.flushAndSync()
                    }

                    override fun error(tag: String, message: String, exception: Throwable) {
                        note("$tag ERROR: $message")
                        default.error(tag, message, exception)
                        instance?.flushAndSync()
                    }

                    override fun debug(tag: String, message: String) {
                        note("$tag: $message")
                        default.debug(tag, message)
                    }

                    override fun debug(tag: String, message: String, exception: Throwable) {
                        note("$tag: $message")
                        default.debug(tag, message, exception)
                        instance?.flushAndSync()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun logGlInfo() {
            try {
                val graphics = Gdx.graphics ?: return
                val version = graphics.glVersion ?: return
                println(
                    "GL vendor=${version.vendorString} renderer=${version.rendererString} " +
                        "version=${version.debugVersionString}",
                )
            } catch (_: Throwable) {
            }
        }

        private fun timestampPrefix(): ByteArray =
            "${CLOCK.format(Instant.now())} ".toByteArray(UTF8)
    }
}

private object RandomAccessAlive {
    fun write(file: File, body: String) {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(body.toByteArray(StandardCharsets.UTF_8))
            try {
                raf.fd.sync()
            } catch (_: Exception) {
            }
        }
    }
}

private class TeeOutputStream(
    private val a: OutputStream,
    private val b: OutputStream,
) : OutputStream() {
    override fun write(b: Int) {
        a.write(b)
        this.b.write(b)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        a.write(buf, off, len)
        this.b.write(buf, off, len)
    }

    override fun flush() {
        a.flush()
        b.flush()
    }

    override fun close() {
        flush()
    }
}

private class LinePrefixOutputStream(
    private val out: OutputStream,
    private val lock: Any,
    private val prefix: () -> ByteArray,
) : OutputStream() {
    private var atLineStart = true

    override fun write(b: Int) {
        synchronized(lock) {
            if (atLineStart) {
                out.write(prefix())
                atLineStart = false
            }
            out.write(b)
            if (b == '\n'.code) atLineStart = true
        }
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            var i = off
            val end = off + len
            while (i < end) {
                if (atLineStart) {
                    out.write(prefix())
                    atLineStart = false
                }
                var nl = -1
                for (j in i until end) {
                    if (buf[j] == '\n'.code.toByte()) {
                        nl = j
                        break
                    }
                }
                if (nl < 0) {
                    out.write(buf, i, end - i)
                    break
                }
                out.write(buf, i, nl - i + 1)
                atLineStart = true
                i = nl + 1
            }
        }
    }

    override fun flush() {
        synchronized(lock) {
            out.flush()
        }
    }

    override fun close() = flush()
}
