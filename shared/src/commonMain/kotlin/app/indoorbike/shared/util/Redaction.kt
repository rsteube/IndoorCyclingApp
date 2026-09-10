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
 * Mask a BLE device identifier (on Android, a hardware address) for logging.
 * Keeps only a short suffix — enough to correlate two log lines about the same
 * device while not exposing a stable hardware identifier. Use this for anything
 * that might end up in a shared bug report.
 */
fun redactDeviceId(id: String): String =
    if (id.length <= 4) "****" else "****${id.takeLast(4)}"
