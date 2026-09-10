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
package app.indoorbike.shared.ble

/**
 * Domain-level representation of a discovered trainer. Decouples the UI from
 * `com.juul.kable.Advertisement`, so swapping BLE libraries doesn't ripple
 * through every screen.
 */
data class TrainerCandidate(
    val id: String,            // stable identifier (BLE address on Android)
    val name: String?,         // user-visible name, null if not advertised
    val rssi: Int? = null,     // signal strength if known
)
