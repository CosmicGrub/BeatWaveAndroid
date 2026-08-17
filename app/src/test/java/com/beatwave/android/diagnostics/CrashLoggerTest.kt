package com.beatwave.android.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM unit tests for [CrashLogger] -- no device/Robolectric needed,
 * since the constructor takes a plain [File] directory rather than a
 * [android.content.Context] (mirrors [com.beatwave.android.data.storage.ProjectRepositoryTest]'s
 * temp-folder approach). [CrashLogger.install]'s actual
 * `Thread.setDefaultUncaughtExceptionHandler` wiring is exercised via
 * genuine on-device crash-and-restart verification instead (see the A2
 * commit notes/memory) -- that's an inherently process-lifecycle-level
 * behavior a JVM unit test can't meaningfully simulate, but every pure
 * piece of logic feeding into it ([logCrash]'s file I/O, pruning,
 * [formatCrashReport]'s content) is covered here directly.
 */
class CrashLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `logCrash writes a file into the logs directory and returns it`() {
        val logsDir = tempFolder.newFolder("crash_logs")
        val logger = CrashLogger(logsDir)

        val written = logger.logCrash(Thread.currentThread(), IllegalStateException("boom"), atEpochMs = 1_000L)

        assertTrue("expected the returned file to exist", written.exists())
        assertEquals(logsDir, written.parentFile)
    }

    @Test
    fun `logCrash creates the logs directory if it doesn't exist yet`() {
        val logsDir = File(tempFolder.root, "not_created_yet")
        val logger = CrashLogger(logsDir)
        assertFalse(logsDir.exists())

        logger.logCrash(Thread.currentThread(), RuntimeException("x"), atEpochMs = 1_000L)

        assertTrue(logsDir.exists())
    }

    @Test
    fun `logCrash writes report content with thread name, exception type, message, and stack trace`() {
        val logger = CrashLogger(tempFolder.newFolder("crash_logs"))
        val exception = IllegalArgumentException("bad argument")

        val written = logger.logCrash(Thread.currentThread(), exception, atEpochMs = 1_000L)
        val content = written.readText()

        assertTrue(content.contains(Thread.currentThread().name))
        assertTrue(content.contains("IllegalArgumentException"))
        assertTrue(content.contains("bad argument"))
        // The formatted report includes the real stack trace, not just the
        // exception's toString() -- confirmed by looking for a frame from
        // THIS test method, which only appears via printStackTrace.
        assertTrue(content.contains("CrashLoggerTest"))
    }

    @Test
    fun `logCrash preserves a cause chain's Caused by section`() {
        val logger = CrashLogger(tempFolder.newFolder("crash_logs"))
        val cause = IllegalStateException("root cause")
        val wrapper = RuntimeException("wrapper", cause)

        val written = logger.logCrash(Thread.currentThread(), wrapper, atEpochMs = 1_000L)
        val content = written.readText()

        assertTrue(content.contains("Caused by"))
        assertTrue(content.contains("root cause"))
    }

    @Test
    fun `listLogs returns every written log, most recent first`() {
        val logger = CrashLogger(tempFolder.newFolder("crash_logs"))
        logger.logCrash(Thread.currentThread(), RuntimeException("first"), atEpochMs = 1_000L)
        logger.logCrash(Thread.currentThread(), RuntimeException("second"), atEpochMs = 3_000L)
        logger.logCrash(Thread.currentThread(), RuntimeException("third"), atEpochMs = 2_000L)

        val logs = logger.listLogs()

        assertEquals(3, logs.size)
        // Sorted by timestamp descending regardless of write order.
        assertTrue(logs[0].name.contains("3000"))
        assertTrue(logs[1].name.contains("2000"))
        assertTrue(logs[2].name.contains("1000"))
    }

    @Test
    fun `listLogs on an empty or nonexistent directory returns an empty list`() {
        val logger = CrashLogger(File(tempFolder.root, "never_written_to"))
        assertEquals(emptyList<File>(), logger.listLogs())
    }

    @Test
    fun `listLogs ignores unrelated files in the same directory`() {
        val logsDir = tempFolder.newFolder("crash_logs")
        File(logsDir, "not_a_crash_log.txt").writeText("irrelevant")
        val logger = CrashLogger(logsDir)
        logger.logCrash(Thread.currentThread(), RuntimeException("real"), atEpochMs = 1_000L)

        val logs = logger.listLogs()

        assertEquals(1, logs.size)
        assertTrue(logs[0].name.startsWith("crash_"))
    }

    @Test
    fun `pruneOldLogs keeps only the most recent MAX_RETAINED_LOGS entries`() {
        val logsDir = tempFolder.newFolder("crash_logs")
        val logger = CrashLogger(logsDir)
        // Write one more than the retention cap directly (bypassing
        // logCrash's own auto-prune) so pruneOldLogs's own logic is what's
        // under test, not logCrash's incidental call to it.
        for (i in 1..(CrashLogger.MAX_RETAINED_LOGS + 1)) {
            File(logsDir, "crash_${i.toString().padStart(4, '0')}.txt").writeText("log $i")
        }
        assertEquals(CrashLogger.MAX_RETAINED_LOGS + 1, logger.listLogs().size)

        logger.pruneOldLogs()

        val remaining = logger.listLogs()
        assertEquals(CrashLogger.MAX_RETAINED_LOGS, remaining.size)
        // The oldest (lowest-numbered) file must be the one pruned away.
        assertFalse(remaining.any { it.name == "crash_0001.txt" })
        val newestName = "crash_${(CrashLogger.MAX_RETAINED_LOGS + 1).toString().padStart(4, '0')}.txt"
        assertTrue(remaining.any { it.name == newestName })
    }

    @Test
    fun `logCrash auto-prunes so repeated crashes never grow the directory unbounded`() {
        val logsDir = tempFolder.newFolder("crash_logs")
        val logger = CrashLogger(logsDir)

        for (i in 1..(CrashLogger.MAX_RETAINED_LOGS + 5)) {
            logger.logCrash(Thread.currentThread(), RuntimeException("crash $i"), atEpochMs = i.toLong())
        }

        assertEquals(CrashLogger.MAX_RETAINED_LOGS, logger.listLogs().size)
    }

    // --- formatCrashReport (top-level function) ---

    @Test
    fun `formatCrashReport includes a null message gracefully rather than crashing`() {
        val report = formatCrashReport(Thread.currentThread(), RuntimeException(), atEpochMs = 1_000L)
        assertTrue(report.contains("RuntimeException"))
    }
}
