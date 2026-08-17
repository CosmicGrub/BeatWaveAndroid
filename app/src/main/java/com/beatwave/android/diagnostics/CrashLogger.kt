package com.beatwave.android.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-v1 audits/upgrades backlog item A2 (crash resilience & diagnostics):
 * writes a timestamped, human-readable report to app-private storage for
 * any uncaught exception, so a crash a user hits in the field leaves
 * behind evidence a developer can actually retrieve (via the "Crash Logs"
 * diagnostics sheet -- see ArrangementScreen/ArrangementViewModel) instead
 * of vanishing the moment the process dies.
 *
 * Context-free core + [forContext] factory, mirroring [com.beatwave.android.data.storage.AppPreferences]/
 * [com.beatwave.android.data.storage.ProjectRepository]: the constructor takes a plain
 * [File] directory, not a [Context], so [logCrash]/[listLogs]/[pruneOldLogs]
 * are directly unit-testable against a JVM temp directory with no
 * Robolectric/instrumentation needed. [Thread.setDefaultUncaughtExceptionHandler]
 * (installed by [install]) is itself a plain `java.lang.Thread` API, not
 * Android-specific, which is what makes this whole class possible without
 * any Android framework dependency.
 */
class CrashLogger(private val logsDir: File) {

    /**
     * Installs this logger as the process's default uncaught-exception
     * handler. Chains to whatever handler was previously installed (Android
     * always has one -- it's what shows the "App has stopped" dialog and
     * records the crash for Play/adb logcat) AFTER logging, so this never
     * suppresses or changes the OS's own crash handling, only adds to it.
     * If logging itself throws (e.g. disk full), that failure is swallowed
     * rather than allowed to replace the real crash with a secondary one.
     */
    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(thread, throwable)
            } catch (e: Throwable) {
                // Never let a failure in crash-logging itself prevent the
                // real (previous) handler from running below.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Writes a crash report for [throwable] (thrown on [thread]) to a new
     *  timestamped file under [logsDir], then prunes old logs down to
     *  [MAX_RETAINED_LOGS]. Returns the file written. Pure I/O -- safe to
     *  call directly from a test without going through [install]. */
    fun logCrash(thread: Thread, throwable: Throwable, atEpochMs: Long = System.currentTimeMillis()): File {
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        val file = File(logsDir, "$LOG_FILE_PREFIX$atEpochMs.txt")
        file.writeText(formatCrashReport(thread, throwable, atEpochMs))
        pruneOldLogs()
        return file
    }

    /** Every retained crash log, most recent first. */
    fun listLogs(): List<File> =
        (logsDir.listFiles { f -> f.isFile && f.name.startsWith(LOG_FILE_PREFIX) } ?: emptyArray())
            .sortedByDescending { it.name }

    /** Deletes the oldest logs beyond [MAX_RETAINED_LOGS] so a device that
     *  crashes repeatedly doesn't grow this directory unbounded. Called
     *  automatically after every [logCrash], but exposed so a diagnostics
     *  UI ("clear old logs") could call it too if ever needed. */
    fun pruneOldLogs() {
        val logs = listLogs()
        if (logs.size > MAX_RETAINED_LOGS) {
            logs.drop(MAX_RETAINED_LOGS).forEach { it.delete() }
        }
    }

    companion object {
        private const val LOG_FILE_PREFIX = "crash_"

        /** Bounds how many crash reports accumulate on a device that keeps
         *  crashing -- old ones are pruned first, per [pruneOldLogs]. */
        const val MAX_RETAINED_LOGS = 20

        /** Sibling of [com.beatwave.android.data.library.AudioImporter.IMPORTED_SAMPLES_DIR_NAME]'s
         *  storage location -- both live under filesDir, matching res/xml/file_paths.xml's
         *  files-path declaration for sharing crash logs via FileProvider. */
        const val CRASH_LOGS_DIR_NAME = "crash_logs"

        /** Factory for real Android use, mirroring [com.beatwave.android.data.storage.AppPreferences.forContext]. */
        fun forContext(context: Context): CrashLogger =
            CrashLogger(File(context.filesDir, CRASH_LOGS_DIR_NAME))
    }
}

/** Human-readable crash report body: timestamp, thread name, exception
 *  class + message, then the full stack trace (including any cause chain,
 *  via [Throwable.printStackTrace]'s own "Caused by:" formatting). A
 *  top-level function (not a CrashLogger member) so it's trivially
 *  unit-testable as pure string formatting, independent of file I/O. A
 *  fresh [SimpleDateFormat] is created per call rather than shared, since
 *  it isn't thread-safe and a crash can happen on any thread. */
internal fun formatCrashReport(thread: Thread, throwable: Throwable, atEpochMs: Long): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(atEpochMs))
    val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
        appendLine("BeatWave crash report")
        appendLine("Timestamp: $timestamp")
        appendLine("Thread: ${thread.name}")
        appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
        appendLine()
        append(stackTrace)
    }
}
