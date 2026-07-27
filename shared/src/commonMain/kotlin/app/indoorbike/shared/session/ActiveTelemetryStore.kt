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
package app.indoorbike.shared.session

import app.indoorbike.shared.db.TelemetryPoint

/**
 * Durable, append-only log of the per-second samples captured during an
 * in-flight ride.
 *
 * [ActiveSessionStore] persists only the ride's *position* (elapsed time,
 * segment index). That is enough to put the rider back on the right interval
 * after a process death, but on its own it silently discards every sample
 * recorded before the crash: the resumed session would be saved with the full
 * duration and a power curve covering only the post-resume minutes.
 *
 * This store closes that gap. Samples are appended as the ride progresses and
 * replayed into the session on resume, so a crash costs at most the handful of
 * seconds since the last flush.
 *
 * Implementations must be append-only: a rewrite-the-whole-file strategy would
 * grow to O(ride length) work per flush and leaves a window where a crash
 * truncates an hour of data.
 */
interface ActiveTelemetryStore {
    /** Append [points] to the end of the log. No-op for an empty list. */
    suspend fun append(points: List<TelemetryPoint>)

    /**
     * Every sample logged for the unfinished ride, in write order. Must
     * tolerate a torn tail: a crash mid-append can leave a partial final
     * record, which is dropped rather than failing the whole read.
     */
    suspend fun readAll(): List<TelemetryPoint>

    /** Drop the log entirely. Called when a ride is saved, abandoned or replaced. */
    suspend fun clear()
}

/**
 * Line format for the telemetry log, kept in common code so it can be unit
 * tested without a filesystem and stays identical across platforms.
 *
 * One record per line, `timestamp,power,cadence,heartRate`. Plain text costs a
 * little space over a packed binary encoding (roughly 100 KB for a 90 minute
 * ride) but is trivially append-safe and recoverable by hand if we ever need
 * to debug a user's log.
 */
object ActiveTelemetryCodec {

    fun encode(point: TelemetryPoint): String =
        "${point.timestampEpoch},${point.power},${point.cadence},${point.heartRate}"

    /**
     * Parse one line, or null if it isn't a complete well-formed record.
     *
     * Returning null rather than throwing is the whole point: the last line of
     * a crashed ride's log is frequently a half-written record, and one torn
     * line must not cost the rider the hour that precedes it.
     */
    fun decode(line: String): TelemetryPoint? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(',')
        if (parts.size != 4) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val power = parts[1].toLongOrNull() ?: return null
        val cadence = parts[2].toLongOrNull() ?: return null
        val heartRate = parts[3].toLongOrNull() ?: return null
        return TelemetryPoint(
            timestampEpoch = timestamp,
            power = power,
            cadence = cadence,
            heartRate = heartRate,
        )
    }

    /** Encode [points] as a newline-terminated block ready to append. */
    fun encodeBlock(points: List<TelemetryPoint>): String {
        val sb = StringBuilder(points.size * 32)
        for (point in points) {
            sb.append(encode(point)).append('\n')
        }
        return sb.toString()
    }

    /** Decode a whole log, skipping blank and torn lines. */
    fun decodeBlock(text: String): List<TelemetryPoint> =
        text.lineSequence().mapNotNull { decode(it) }.toList()
}
