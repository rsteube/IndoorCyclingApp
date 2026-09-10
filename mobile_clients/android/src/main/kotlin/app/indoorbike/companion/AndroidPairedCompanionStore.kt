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
package app.indoorbike.companion

import android.content.Context
import app.indoorbike.shared.companion.PairedCompanion
import app.indoorbike.shared.companion.PairedCompanionStore
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Companions the rider has decided to trust, and the keys they share.
 *
 * Built like [app.indoorbike.route.AndroidRouteProgressStore] and for the same
 * reasons: one small file, read whole, written atomically so a torn write
 * cannot eat the lot.
 *
 * ## Where this lives, and why that is the protection
 *
 * `filesDir` is app-private. Android gives the directory to this application's
 * uid alone, so another app cannot read it without root, and it is outside the
 * paths a backup or a share sheet can reach. That is the whole of the
 * at-rest story, and it is stated plainly here rather than implied: the keys
 * are not additionally encrypted, because a key held in the same app that holds
 * the ciphertext protects against nothing an attacker with the file could not
 * already do.
 *
 * What this file must never do is leak into somewhere less careful. It is not
 * in the ride record, not in the backup document, not in a diagnostics bundle,
 * and no key is ever written to a log line.
 */
class AndroidPairedCompanionStore(appContext: Context) : PairedCompanionStore {

    private val file = File(appContext.filesDir, "paired_companions.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = MapSerializer(String.serializer(), PairedCompanion.serializer())

    override suspend fun all(): List<PairedCompanion> = withContext(Dispatchers.IO) {
        readBlocking().values.sortedByDescending { it.lastSeenEpochMs }
    }

    override suspend fun find(deviceId: String): PairedCompanion? = withContext(Dispatchers.IO) {
        readBlocking()[deviceId]
    }

    override suspend fun save(companion: PairedCompanion) {
        withContext(Dispatchers.IO) {
            write(readBlocking() + (companion.deviceId to companion))
        }
    }

    override suspend fun forget(deviceId: String) {
        withContext(Dispatchers.IO) {
            val current = readBlocking()
            if (deviceId !in current) return@withContext
            write(current - deviceId)
        }
    }

    override suspend fun forgetAll() {
        withContext(Dispatchers.IO) { write(emptyMap()) }
    }

    private fun readBlocking(): Map<String, PairedCompanion> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // Deleted rather than kept. A half-readable list of secrets is not
            // something to guess at, and the cost of being wrong is that the
            // rider pairs again with a code, which takes seconds. The exception
            // is logged without its contents, because its contents are keys.
            Log.w(TAG, "Paired companions file unreadable, starting over")
            file.delete()
            emptyMap()
        }
    }

    private fun write(all: Map<String, PairedCompanion>) {
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
            Log.w(TAG, "Failed to persist paired companions")
        }
    }

    private companion object {
        const val TAG = "PairedCompanions"
    }
}
