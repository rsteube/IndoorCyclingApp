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
package app.indoorbike.shared.ble

/**
 * Standard BLE Heart Rate Service (0x180D) → Heart Rate Measurement (0x2A37) parser.
 *
 * The first byte is a flags field; bit 0 indicates whether the BPM value is
 * uint8 (bit 0 == 0) or uint16 little-endian (bit 0 == 1). Subsequent bytes
 * (energy expended, RR intervals) are ignored — we only need instantaneous
 * BPM for the ride HUD.
 *
 * Returns null if the byte stream is malformed.
 */
fun parseHeartRateMeasurement(bytes: ByteArray): Int? {
    if (bytes.isEmpty()) return null
    val flags = bytes[0].toInt() and 0xFF
    val isUInt16 = (flags and 0x01) != 0
    return if (isUInt16) {
        if (bytes.size < 3) null
        else (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
    } else {
        if (bytes.size < 2) null
        else bytes[1].toInt() and 0xFF
    }
}

/** Heart Rate Service / Heart Rate Measurement / Body Sensor Location UUIDs. */
const val HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
const val HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
