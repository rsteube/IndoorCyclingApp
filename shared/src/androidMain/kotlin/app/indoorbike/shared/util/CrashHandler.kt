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

/**
 * Records the stack trace of an uncaught exception into [DiagnosticsLog] and
 * forces it to disk before the process goes away.
 *
 * [ProcessExitReasons] already tells us *that* the app crashed, and on many
 * builds carries the trace too. This adds the app's own view: the crash lands in
 * the same log as the BLE and session lines that led up to it, so a report shows
 * the failure in context rather than as a stack trace with no story attached.
 *
 * Deliberately not a crash *reporter*: nothing is uploaded, and the platform
 * handler still runs, so the system dialog and Android's own record are
 * unchanged. We only make sure the evidence is written down first.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"

    @Volatile
    private var installed = false

    /** Call once, as early in `Application.onCreate` as possible. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread \"${thread.name}\"", throwable)
                // The periodic flush is seconds away and the process has
                // milliseconds, so write through synchronously.
                DiagnosticsLog.flushToDisk()
            } catch (_: Throwable) {
                // A failure in here must not replace the real crash with a
                // less useful one.
            }
            // Hand back to the platform so the process dies exactly as it
            // otherwise would. Skipping this leaves a half-dead app with a
            // running UI thread and no way out.
            platformHandler?.uncaughtException(thread, throwable)
        }
    }
}
