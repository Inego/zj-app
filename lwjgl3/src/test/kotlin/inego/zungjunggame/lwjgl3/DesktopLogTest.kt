package inego.zungjunggame.lwjgl3

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DesktopLogTest {
    @Test
    fun `begin writes a fresh session header`() {
        val dir = Files.createTempDirectory("zj-desk-log").toFile()
        try {
            val log = DesktopLog.forTests(dir)
            log.begin()
            val text = log.logFile.readText()
            assertContains(text, "zj-game desktop session")
            assertContains(text, "java=")
            assertContains(text, "pid=")
            assertContains(text, "cwd=")
            assertContains(text, "vmArgs=")
            assertContains(text, "errorFile=")
            assertTrue(log.crashFile.exists().not())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `begin keeps previous alive line in the new session header`() {
        val dir = Files.createTempDirectory("zj-desk-alive").toFile()
        try {
            val alive = dir.resolve(DesktopLog.ALIVE_FILE)
            alive.writeText("2026-09-06 15:42:07.000 status=running pid=42 breadcrumb=Online: enter table=abc\n")
            val log = DesktopLog.forTests(dir)
            log.begin()
            val text = log.logFile.readText()
            assertContains(text, "previous-alive=")
            assertContains(text, "breadcrumb=Online: enter table=abc")
            assertTrue(log.alivePrevFile.exists())
            assertContains(log.alivePrevFile.readText(), "pid=42")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `crash writes stack to last-crash and appends the session log`() {
        val dir = Files.createTempDirectory("zj-desk-log").toFile()
        try {
            val log = DesktopLog.forTests(dir)
            log.begin()
            val boom = IllegalStateException("enter-game boom")
            log.crash(Thread.currentThread(), boom, "ApplicationListener.render")

            val crash = log.crashFile.readText()
            assertContains(crash, "=== CRASH")
            assertContains(crash, "where=ApplicationListener.render")
            assertContains(crash, "IllegalStateException: enter-game boom")
            assertContains(crash, "DesktopLogTest")

            val session = log.logFile.readText()
            assertContains(session, "zj-game desktop session")
            assertContains(session, "IllegalStateException: enter-game boom")
            assertContains(session, crash.trim())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `formatCrash includes cause chain`() {
        val root = IllegalArgumentException("bad snapshot")
        val wrapped = RuntimeException("online join", root)
        val text = DesktopLog.formatCrash(Thread.currentThread(), wrapped, "uncaught")
        assertContains(text, "RuntimeException: online join")
        assertContains(text, "Caused by: java.lang.IllegalArgumentException: bad snapshot")
    }

    @Test
    fun `formatUncleanExit includes breadcrumb and this thread`() {
        val text = DesktopLog.formatUncleanExit("Online: enter table=abc")
        assertContains(text, "=== UNCLEAN EXIT")
        assertContains(text, "breadcrumb=Online: enter table=abc")
        assertContains(text, "threads=")
        assertContains(text, Thread.currentThread().name)
        assertContains(text, "DesktopLogTest")
    }
}
