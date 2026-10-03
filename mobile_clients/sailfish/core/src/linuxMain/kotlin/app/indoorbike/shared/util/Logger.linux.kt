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

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

// Every line also goes into [DiagnosticsLog], which a bug report attaches and
// which never leaves the device on its own. stderr reaches the journal when
// the app runs from its launcher, and the terminal when it runs by hand.
@OptIn(ExperimentalForeignApi::class)
actual object Log {
    actual fun d(tag: String, message: String) = write('D', tag, message, null)

    actual fun i(tag: String, message: String) = write('I', tag, message, null)

    actual fun w(tag: String, message: String, throwable: Throwable?) = write('W', tag, message, throwable)

    actual fun e(tag: String, message: String, throwable: Throwable?) = write('E', tag, message, throwable)

    private fun write(level: Char, tag: String, message: String, throwable: Throwable?) {
        DiagnosticsLog.record(level, tag, message, throwable)
        val suffix = throwable?.message?.let { " $it" } ?: ""
        fputs("[$level] $tag: $message$suffix\n", stderr)
        fflush(stderr)
    }
}
