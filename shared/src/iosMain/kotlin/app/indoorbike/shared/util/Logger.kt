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

import platform.Foundation.NSLog

actual object Log {
    actual fun d(tag: String, message: String) { NSLog("[D] $tag: $message") }
    actual fun i(tag: String, message: String) { NSLog("[I] $tag: $message") }
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        NSLog("[W] $tag: $message ${throwable?.message ?: ""}")
    }
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        NSLog("[E] $tag: $message ${throwable?.message ?: ""}")
    }
}
