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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads Android's own record of why this app's processes died, for inclusion in
 * a bug report.
 *
 * This answers the question our own logs structurally cannot. A crash report
 * assembled inside the app can describe everything except the moment it stopped
 * running; the system, watching from outside, knows whether the process threw,
 * was killed for memory, hit an ANR, or was stopped by the user. Getting that
 * one line turns "the app crashed and the report was empty" into a diagnosis.
 *
 * Requires API 30. Below that the system keeps no such record and callers get
 * null, which the report renders as a short explanatory line rather than a gap.
 */
object ProcessExitReasons {

    /** How many past deaths to report. More than one makes a crash *loop* visible. */
    private const val MAX_EXITS = 3

    /** Cap on the system-captured trace, which for an ANR can run to megabytes. */
    private const val MAX_TRACE_LINES = 120

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * A human-readable block describing the most recent process deaths, or null
     * when the platform is too old or has nothing recorded (a first run, or an
     * app that has never died).
     */
    fun describeRecentExits(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return null
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            if (exits.isEmpty()) return null
            exits.joinToString("\n\n") { describe(it) }
        } catch (_: Throwable) {
            // Never let diagnostics collection be the thing that breaks the
            // report the user is trying to send.
            null
        }
    }

    private fun describe(info: ApplicationExitInfo): String = buildString {
        append(timeFormat.format(Date(info.timestamp)))
        append("  ").append(reasonName(info.reason))
        append(" (status ").append(info.status).append(')')
        appendLine()
        // Importance at the moment of death is the tell that separates "Android
        // reclaimed a backgrounded app" from "the ride's foreground service was
        // running and it still died".
        append("  importance: ").append(importanceName(info.importance)).appendLine()
        if (info.pss > 0) append("  pss: ").append(info.pss / 1024).append(" MB").appendLine()
        if (info.rss > 0) append("  rss: ").append(info.rss / 1024).append(" MB").appendLine()
        info.description?.takeIf { it.isNotBlank() }?.let {
            append("  description: ").append(it).appendLine()
        }
        trace(info)?.let {
            appendLine("  trace:")
            append(it)
        }
    }.trimEnd()

    /** The system-captured stack trace, present only for crashes and ANRs. */
    private fun trace(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { stream ->
            val lines = stream.bufferedReader().lineSequence().take(MAX_TRACE_LINES).toList()
            if (lines.isEmpty()) null
            else lines.joinToString("\n") { "    $it" } +
                if (lines.size == MAX_TRACE_LINES) "\n    … trace truncated" else ""
        }
    } catch (_: Throwable) {
        null
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (the app stopped itself)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (system reclaimed it)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (unhandled Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (swiped away / force stopped)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN ($reason)"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached (backgrounded)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "importance $importance"
    }
}
