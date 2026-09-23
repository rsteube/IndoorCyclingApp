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
package app.indoorbike.route

import android.content.Context
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.util.Log
import app.indoorbike.util.AtomicFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Partly-ridden routes, in one small file.
 *
 * One file rather than a file per route because the list is read whole every
 * time the route library is drawn, and a directory scan for what is usually
 * one or two records costs more than it saves.
 */
class AndroidRouteProgressStore(appContext: Context) : RouteProgressStore {

    private val file = File(appContext.filesDir, "route_progress.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = MapSerializer(String.serializer(), RouteProgress.serializer())

    /**
     * Save and clear each read the file, change it and write it back. Two of
     * them at once, a ride ending while another route's mark is cleared, could
     * each write back a copy missing the other's change.
     */
    private val mutex = Mutex()

    override suspend fun read(): Map<String, RouteProgress> = withContext(Dispatchers.IO) {
        mutex.withLock { readBlocking() }
    }

    override suspend fun save(progress: RouteProgress) {
        withContext(Dispatchers.IO) {
            mutex.withLock { write(readBlocking() + (progress.routeId to progress)) }
        }
    }

    override suspend fun clear(routeId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock { clearLocked(routeId) }
        }
    }

    private fun clearLocked(routeId: String) {
        val current = readBlocking()
        // Nothing to forget is the common case - most rides finish, or end
        // before the first quarter kilometre - so it costs no write.
        if (routeId !in current) return
        write(current - routeId)
    }

    private fun readBlocking(): Map<String, RouteProgress> {
        val text = try {
            AtomicFiles.readText(file)
        } catch (e: Exception) {
            Log.w(TAG, "Route progress file unreadable", e)
            null
        } ?: return emptyMap()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            // A corrupt file costs the rider their place on a route, which is
            // annoying; refusing to start any route would be worse.
            Log.w(TAG, "Route progress file corrupt, setting it aside", e)
            AtomicFiles.setAside(file)
            emptyMap()
        }
    }

    /** See [AtomicFiles]: a torn write must not eat the lot. */
    private fun write(all: Map<String, RouteProgress>) {
        try {
            if (all.isEmpty()) {
                AtomicFiles.delete(file)
                return
            }
            AtomicFiles.writeText(file, json.encodeToString(serializer, all))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist route progress", e)
        }
    }

    private companion object {
        const val TAG = "RouteProgressStore"
    }
}
