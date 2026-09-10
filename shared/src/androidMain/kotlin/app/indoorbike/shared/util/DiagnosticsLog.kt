/*
 * Copyright (C) 2026 Snapp Technology Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.shared.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Ring buffer of the most recent diagnostic log lines, fed by [Log], plus a
 * durable mirror on disk so the lines survive the process that wrote them.
 *
 * Why this exists: [Log] only writes to Logcat, and only in debug builds, so a
 * release-build user hitting a bug has nothing to hand over. This buffer keeps
 * the last [MAX_ENTRIES] lines in memory (in every build type) so the in-app
 * "Report a problem" feature can attach them to an email the user composes.
 *
 * Why it is also on disk: a RAM-only buffer dies with the process, so a report
 * drafted after a crash could only ever contain lines from the process that
 * started *after* it. Users dutifully sent reports whose "Recent logs" section
 * held nothing but the launch that followed the crash. Lines are mirrored to
 * [CURRENT_LOG_NAME] a few seconds at a time; on the next launch that file is
 * rotated to [PREVIOUS_LOG_NAME] and offered to the report as the crashed run's
 * log. A hard kill therefore costs at most [FLUSH_INTERVAL_SECONDS] of lines.
 *
 * Privacy: both files live in app-private storage, are capped at
 * [MAX_FILE_BYTES], are readable by no other app, and are never uploaded. They
 * leave the device only when the user drafts a report and presses send. The app
 * deliberately logs no personal data (no name/email, body metrics, or location
 * - see the logging audit), only technical state: BLE connection events, FTMS
 * protocol bytes, parse failures, and counts. Keep it that way: never log
 * anything that could identify a person, since these lines can leave the device
 * via a report, and now also outlive the session that produced them.
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 300

    /** How long a hard kill can cost us. Short enough to be useful, long enough not to churn the disk. */
    private const val FLUSH_INTERVAL_SECONDS = 5L

    /** Per-file cap. Two files can exist at once, so this is half the worst-case footprint. */
    private const val MAX_FILE_BYTES = 128L * 1024L

    /** Lines of the crashed run's log offered to a report. Bounds the email, not the file. */
    private const val MAX_PREVIOUS_LINES = 400

    internal const val CURRENT_LOG_NAME = "diagnostics.log"
    internal const val PREVIOUS_LOG_NAME = "diagnostics-previous.log"

    // Single formatter reused under the lock (SimpleDateFormat isn't thread-safe;
    // every access here is inside a @Synchronized method, so this is fine).
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    /** Lines recorded but not yet on disk. Guarded by the monitor, like [entries]. */
    private val pending = StringBuilder()

    private var currentFile: File? = null
    private var previousFile: File? = null
    private var flusher: ScheduledExecutorService? = null

    /**
     * Point the durable mirror at [dir] (app-private storage) and rotate the
     * previous run's file aside. Call once, as early in `Application.onCreate`
     * as possible: lines recorded before this land in memory only.
     *
     * Failures are swallowed. A device that can't give us a log file should
     * still run rides.
     */
    @Synchronized
    fun attachStorage(dir: File) {
        if (currentFile != null) return          // idempotent
        try {
            if (!dir.exists()) dir.mkdirs()
            val current = File(dir, CURRENT_LOG_NAME)
            val previous = File(dir, PREVIOUS_LOG_NAME)
            // Rotate before writing a single line, so `previous` is exactly the
            // run that just ended rather than a mix of two runs.
            if (current.exists()) {
                previous.delete()
                if (!current.renameTo(previous)) current.delete()
            }
            currentFile = current
            previousFile = previous
        } catch (_: Throwable) {
            return
        }
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diagnostics-log").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            ::flushToDisk,
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        flusher = executor
    }

    @Synchronized
    internal fun record(level: Char, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                append('\n').append(throwable.stackTraceToString().trimEnd())
            }
        }
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
        if (currentFile != null) pending.append(line).append('\n')
    }

    /** The buffered lines, oldest first, joined for inclusion in a bug report. */
    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")

    /**
     * The tail of the previous run's log, or empty when there is none (first
     * launch, or storage was never attached). This is the part a crash report
     * actually needs: everything the app said before it died.
     */
    fun previousRunLog(): String {
        val file = synchronized(this) { previousFile } ?: return ""
        return try {
            if (!file.exists()) return ""
            val lines = file.readLines()
            val tail = if (lines.size > MAX_PREVIOUS_LINES) lines.takeLast(MAX_PREVIOUS_LINES) else lines
            tail.joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Write pending lines out now rather than waiting for the next tick. Called
     * from the uncaught-exception handler, where "in a few seconds" is too late.
     */
    fun flushToDisk() {
        val text = synchronized(this) {
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        val current = synchronized(this) { currentFile } ?: return
        try {
            current.appendText(text)
            if (current.length() < MAX_FILE_BYTES) return
            // Full. Rotate rather than truncate, so the oldest lines we keep are
            // a whole run behind rather than an arbitrary byte offset.
            val previous = synchronized(this) { previousFile }
            if (previous == null) {
                current.delete()
                return
            }
            previous.delete()
            if (!current.renameTo(previous)) current.delete()
        } catch (_: Throwable) {
            // Out of space, or storage yanked. Losing the mirror must not take
            // the ride down with it; the in-memory buffer is unaffected.
        }
    }

    @Synchronized
    internal fun clear() = entries.clear()

    /**
     * Drop every trace of the current attachment so a following [attachStorage]
     * takes effect. Exists because this is a process-wide singleton and a test
     * needs to act out several app launches in one JVM; production attaches
     * once and never detaches.
     */
    @Synchronized
    internal fun resetForTest() {
        flusher?.shutdownNow()
        flusher = null
        currentFile = null
        previousFile = null
        pending.setLength(0)
        entries.clear()
    }
}
