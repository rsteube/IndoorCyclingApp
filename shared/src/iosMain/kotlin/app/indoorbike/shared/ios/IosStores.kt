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
package app.indoorbike.shared.ios

import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.ActiveSessionSnapshot
import app.indoorbike.shared.session.ActiveSessionStore
import app.indoorbike.shared.session.ActiveTelemetryCodec
import app.indoorbike.shared.session.ActiveTelemetryStore
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// The three files a ride in progress keeps so it survives the app being
// killed, written the way the Android app writes them and to the same names.
// See the Android stores for why each behaves as it does; the behaviour is
// meant to be identical.

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Where the rider was: one small JSON snapshot, replaced atomically. */
class IosActiveSessionStore : ActiveSessionStore {
    private val path = IosFiles.path("active_session.json")

    override suspend fun read(): ActiveSessionSnapshot? = withContext(Dispatchers.IO) {
        val text = try {
            IosFiles.readText(path)
        } catch (e: Exception) {
            Log.w(TAG, "Active session file unreadable", e)
            null
        } ?: return@withContext null
        try {
            json.decodeFromString<ActiveSessionSnapshot>(text)
        } catch (e: Exception) {
            // Set aside rather than deleted: the session manager then offers the
            // ride back from its telemetry log instead.
            Log.w(TAG, "Active session file corrupt, setting it aside", e)
            IosFiles.setAside(path)
            null
        }
    }

    override suspend fun write(snapshot: ActiveSessionSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                IosFiles.writeText(path, json.encodeToString(ActiveSessionSnapshot.serializer(), snapshot))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist active session", e)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { IosFiles.delete(path) }
    }

    private companion object {
        const val TAG = "ActiveSessionStore"
    }
}

/** What the rider did: the ride's samples, appended and synced block by block. */
class IosActiveTelemetryStore : ActiveTelemetryStore {
    private val path = IosFiles.path("active_telemetry.csv")

    override suspend fun append(points: List<TelemetryPoint>) {
        if (points.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                IosFiles.appendText(path, ActiveTelemetryCodec.encodeBlock(points))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append active telemetry", e)
            }
        }
    }

    override suspend fun readAll(): List<TelemetryPoint> = withContext(Dispatchers.IO) {
        try {
            IosFiles.readText(path)?.let { ActiveTelemetryCodec.decodeBlock(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Active telemetry log unreadable, ignoring", e)
            emptyList()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { IosFiles.delete(path) }
    }

    private companion object {
        const val TAG = "ActiveTelemetryStore"
    }
}

/** How far along each route a rider got, so a long one can be finished another day. */
class IosRouteProgressStore : RouteProgressStore {
    private val path = IosFiles.path("route_progress.json")
    private val serializer = MapSerializer(String.serializer(), RouteProgress.serializer())

    /** Save and clear each read, change and write back; two at once could lose one's change. */
    private val mutex = Mutex()

    override suspend fun read(): Map<String, RouteProgress> = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    override suspend fun save(progress: RouteProgress) {
        withContext(Dispatchers.IO) {
            mutex.withLock { writeLocked(readLocked() + (progress.routeId to progress)) }
        }
    }

    override suspend fun clear(routeId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readLocked()
                if (routeId in current) writeLocked(current - routeId)
            }
        }
    }

    private fun readLocked(): Map<String, RouteProgress> {
        val text = try {
            IosFiles.readText(path)
        } catch (e: Exception) {
            Log.w(TAG, "Route progress file unreadable", e)
            null
        } ?: return emptyMap()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            Log.w(TAG, "Route progress file corrupt, setting it aside", e)
            IosFiles.setAside(path)
            emptyMap()
        }
    }

    private fun writeLocked(all: Map<String, RouteProgress>) {
        try {
            if (all.isEmpty()) IosFiles.delete(path) else IosFiles.writeText(path, json.encodeToString(serializer, all))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist route progress", e)
        }
    }

    private companion object {
        const val TAG = "RouteProgressStore"
    }
}
