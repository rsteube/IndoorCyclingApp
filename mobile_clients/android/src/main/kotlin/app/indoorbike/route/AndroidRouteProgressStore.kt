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
package app.indoorbike.route

import android.content.Context
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
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

    override suspend fun read(): Map<String, RouteProgress> = withContext(Dispatchers.IO) {
        readBlocking()
    }

    override suspend fun save(progress: RouteProgress) {
        withContext(Dispatchers.IO) {
            write(readBlocking() + (progress.routeId to progress))
        }
    }

    override suspend fun clear(routeId: String) {
        withContext(Dispatchers.IO) {
            val current = readBlocking()
            // Nothing to forget is the common case - most rides finish, or end
            // before the first quarter kilometre - so it costs no write.
            if (routeId !in current) return@withContext
            write(current - routeId)
        }
    }

    private fun readBlocking(): Map<String, RouteProgress> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // A corrupt file costs the rider their place on a route, which is
            // annoying; refusing to start any route would be worse.
            Log.w(TAG, "Route progress file corrupt, ignoring", e)
            file.delete()
            emptyMap()
        }
    }

    /** Atomic, the same way the session snapshot is written: a torn write must not eat the lot. */
    private fun write(all: Map<String, RouteProgress>) {
        try {
            if (all.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, all))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist route progress", e)
        }
    }

    private companion object {
        const val TAG = "RouteProgressStore"
    }
}
