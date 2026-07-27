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
package app.indoorbike.session

import android.content.Context
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.ActiveTelemetryCodec
import app.indoorbike.shared.session.ActiveTelemetryStore
import app.indoorbike.shared.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Append-only telemetry log backed by a plain text file in the app's private
 * storage.
 *
 * Deliberately *not* the atomic temp-file-plus-rename dance
 * [AndroidActiveSessionStore] uses: that pattern rewrites the whole payload
 * each time, which for an hour-long ride would mean re-serialising thousands of
 * samples every five seconds and would leave a window where a badly-timed crash
 * costs the entire ride. Appending a few hundred bytes is O(1) and can only
 * ever damage the record currently being written, which the reader discards.
 */
class AndroidActiveTelemetryStore(appContext: Context) : ActiveTelemetryStore {

    private val file = File(appContext.filesDir, "active_telemetry.csv")

    override suspend fun append(points: List<TelemetryPoint>) {
        if (points.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                FileOutputStream(file, /* append = */ true).use { out ->
                    out.write(ActiveTelemetryCodec.encodeBlock(points).toByteArray())
                    out.flush()
                    // An app crash leaves the page cache intact, but a battery
                    // pull or kernel-level kill does not. Syncing ~200 bytes
                    // every five seconds is cheap next to losing the ride.
                    out.fd.sync()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append active telemetry", e)
            }
        }
    }

    override suspend fun readAll(): List<TelemetryPoint> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            // Decoding is per-line and null-tolerant, so a half-written final
            // record from the crash we're recovering from is simply dropped.
            ActiveTelemetryCodec.decodeBlock(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Active telemetry log unreadable, ignoring", e)
            emptyList()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear active telemetry", e)
            }
        }
    }

    companion object {
        private const val TAG = "ActiveTelemetryStore"
    }
}
