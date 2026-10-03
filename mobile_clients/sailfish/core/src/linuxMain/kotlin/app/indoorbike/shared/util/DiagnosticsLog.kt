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

import app.indoorbike.shared.sailfish.SailfishPaths
import kotlin.experimental.ExperimentalNativeApi

/**
 * The last few hundred log lines, kept on the phone for a bug report and never
 * sent anywhere on their own: the Sailfish counterpart of the Android and iOS
 * `DiagnosticsLog`, with the same limits and the same two files.
 *
 * Every line goes into an in-memory ring buffer and, once [attachStorage] has
 * run, is appended to `diagnostics.log` in the app's data folder. At start the
 * file from the run before is rotated to `diagnostics-previous.log`, so a
 * report written after a crash still has the lines that led up to it.
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 300
    private const val MAX_PREVIOUS_LINES = 400
    private const val MAX_FILE_BYTES = 128L * 1024L
    private const val CURRENT_LOG_NAME = "diagnostics.log"
    private const val PREVIOUS_LOG_NAME = "diagnostics-previous.log"

    private val lock = ReentrantLock()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private var currentPath: String? = null
    private var previousPath: String? = null
    private var written = 0L

    /** Start writing to disk, rotating the last run's file aside first. Idempotent. */
    fun attachStorage() = lock.withLock {
        if (currentPath != null) return@withLock
        val current = SailfishPaths.data(CURRENT_LOG_NAME)
        val previous = SailfishPaths.data(PREVIOUS_LOG_NAME)
        if (PosixFiles.exists(current)) {
            PosixFiles.delete(previous)
            if (!PosixFiles.move(current, previous)) PosixFiles.delete(current)
        }
        currentPath = current
        previousPath = previous
        written = 0
    }

    internal fun record(level: Char, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(formatLocalClock(getCurrentEpochMillis()))
            append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (throwable != null) append('\n').append(throwable.stackTraceToString().trimEnd())
        }
        lock.withLock {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(line)
            val path = currentPath ?: return@withLock
            try {
                PosixFiles.appendText(path, line + "\n")
                written += line.length + 1
                if (written >= MAX_FILE_BYTES) {
                    // Full: rotate rather than truncate, so what is kept is a
                    // whole file behind rather than an arbitrary byte offset.
                    previousPath?.let {
                        PosixFiles.delete(it)
                        PosixFiles.move(path, it)
                    }
                    written = 0
                }
            } catch (_: Throwable) {
                // Out of space: losing the mirror must not take the ride with it.
            }
        }
    }

    fun snapshot(): String = lock.withLock { entries.joinToString("\n") }

    fun previousRunLog(): String {
        val path = lock.withLock { previousPath } ?: return ""
        val text = PosixFiles.readText(path) ?: return ""
        return text.trimEnd().lines().takeLast(MAX_PREVIOUS_LINES).joinToString("\n")
    }
}

/**
 * Put an exception nothing caught into the log before the app goes down, so
 * the next run's report shows what killed this one. As on iOS.
 */
@OptIn(ExperimentalNativeApi::class)
fun installCrashLogging() {
    val previous = setUnhandledExceptionHook { throwable ->
        DiagnosticsLog.record('E', "Crash", "Uncaught exception", throwable)
    }
    if (previous != null) setUnhandledExceptionHook(previous)
}
