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
import app.indoorbike.shared.session.ActiveSessionSnapshot
import app.indoorbike.shared.session.ActiveSessionStore
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AndroidActiveSessionStore(appContext: Context) : ActiveSessionStore {

    private val file = File(appContext.filesDir, "active_session.json")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun read(): ActiveSessionSnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<ActiveSessionSnapshot>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Active session file corrupt, ignoring", e)
            file.delete()
            null
        }
    }

    override suspend fun write(snapshot: ActiveSessionSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(ActiveSessionSnapshot.serializer(), snapshot))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist active session", e)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear active session", e)
            }
        }
    }

    companion object {
        private const val TAG = "ActiveSessionStore"
    }
}
