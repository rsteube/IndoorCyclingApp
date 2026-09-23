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

import platform.Foundation.NSLog

// Every line also goes into [DiagnosticsLog], which a bug report attaches and
// which never leaves the device on its own.
actual object Log {
    actual fun d(tag: String, message: String) {
        DiagnosticsLog.record('D', tag, message, null)
        NSLog("[D] $tag: $message")
    }
    actual fun i(tag: String, message: String) {
        DiagnosticsLog.record('I', tag, message, null)
        NSLog("[I] $tag: $message")
    }
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        DiagnosticsLog.record('W', tag, message, throwable)
        NSLog("[W] $tag: $message ${throwable?.message ?: ""}")
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        DiagnosticsLog.record('E', tag, message, throwable)
        NSLog("[E] $tag: $message ${throwable?.message ?: ""}")
    }
}
