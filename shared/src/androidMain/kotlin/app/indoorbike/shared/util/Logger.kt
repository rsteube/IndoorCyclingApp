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

import android.util.Log as AndroidLog
import app.indoorbike.shared.BuildConfig

// Logcat output is silenced in release builds (no remote logger ships, so
// release users get clean logcat by default; install the debug variant to read
// diagnostics over `adb logcat`). Regardless of build type, every line is also
// mirrored into [DiagnosticsLog] — an in-memory ring buffer the user can attach
// to a bug report. That buffer never leaves the device on its own.
actual object Log {
    actual fun d(tag: String, message: String) {
        DiagnosticsLog.record('D', tag, message, null)
        if (BuildConfig.DEBUG) AndroidLog.d(tag, message)
    }
    actual fun i(tag: String, message: String) {
        DiagnosticsLog.record('I', tag, message, null)
        if (BuildConfig.DEBUG) AndroidLog.i(tag, message)
    }
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        DiagnosticsLog.record('W', tag, message, throwable)
        if (!BuildConfig.DEBUG) return
        if (throwable == null) AndroidLog.w(tag, message) else AndroidLog.w(tag, message, throwable)
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        DiagnosticsLog.record('E', tag, message, throwable)
        if (!BuildConfig.DEBUG) return
        if (throwable == null) AndroidLog.e(tag, message) else AndroidLog.e(tag, message, throwable)
    }
}
