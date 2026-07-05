/*
 * Copyright (C) 2026 Juhani Lehtimäki
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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of the most recent diagnostic log lines, fed by [Log].
 *
 * Why this exists: [Log] only writes to Logcat, and only in debug builds, so a
 * release-build user hitting a bug has nothing to hand over. This buffer keeps
 * the last [MAX_ENTRIES] lines in memory (in every build type) so the in-app
 * "Report a problem" feature can attach them to an email the user composes.
 *
 * Privacy: this is RAM-only — never written to disk, never sent anywhere, and
 * only surfaced when the user explicitly drafts a report. The app deliberately
 * logs no personal data (no name/email, body metrics, or location — see the
 * logging audit), only technical state: BLE connection events, FTMS protocol
 * bytes, parse failures, and counts. Keep it that way: never log anything that
 * could identify a person, since these lines can leave the device via a report.
 */
object DiagnosticsLog {
    private const val MAX_ENTRIES = 300

    // Single formatter reused under the lock (SimpleDateFormat isn't thread-safe;
    // every access here is inside a @Synchronized method, so this is fine).
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

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
    }

    /** The buffered lines, oldest first, joined for inclusion in a bug report. */
    @Synchronized
    fun snapshot(): String = entries.joinToString("\n")

    @Synchronized
    internal fun clear() = entries.clear()
}
