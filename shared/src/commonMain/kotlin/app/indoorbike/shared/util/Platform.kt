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

/** SHA-256 of [bytes]. */
expect fun sha256(bytes: ByteArray): ByteArray

/** [epochMs] as local wall-clock time to the minute: "2026-09-23 17:45". */
expect fun formatLocalMinute(epochMs: Long): String

/**
 * A few named strings kept between app runs, for state that must survive the
 * app being killed while a browser is in front of it: a sign-in in progress.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun clear()
}
