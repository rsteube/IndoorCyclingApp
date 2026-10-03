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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.util.KeyValueStore
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import app.indoorbike.shared.util.ReentrantLock
import app.indoorbike.shared.util.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A small preferences file: one JSON object, read once and written whole and
 * atomically on every change. What `NSUserDefaults` is to the iOS app and
 * DataStore to the Android one, under the same key names.
 *
 * [private] files are written readable by the owner only. Sailjail keeps other
 * sandboxed apps out of the app's folders anyway; this keeps out the rest of
 * what runs as the same user, as far as file modes can. It is not encryption:
 * Sailfish Secrets, which would be, is missing on the Jolla C2.
 */
internal class JsonPrefs(private val path: String, private val private: Boolean = false) : KeyValueStore {
    private val lock = ReentrantLock()
    private val values: MutableMap<String, JsonElement> = load()

    private fun load(): MutableMap<String, JsonElement> {
        val text = PosixFiles.readText(path) ?: return mutableMapOf()
        return try {
            Json.parseToJsonElement(text).jsonObject.toMutableMap()
        } catch (e: Exception) {
            Log.w(TAG, "Preferences unreadable, setting them aside", e)
            PosixFiles.setAside(path)
            mutableMapOf()
        }
    }

    fun has(key: String): Boolean = lock.withLock { key in values }

    fun int(key: String): Int? = lock.withLock { (values[key] as? JsonPrimitive)?.intOrNull }
    fun float(key: String): Float? = lock.withLock { (values[key] as? JsonPrimitive)?.doubleOrNull?.toFloat() }
    fun bool(key: String): Boolean? = lock.withLock { (values[key] as? JsonPrimitive)?.booleanOrNull }
    fun string(key: String): String? = lock.withLock { (values[key] as? JsonPrimitive)?.contentOrNull }
    fun strings(key: String): List<String>? =
        lock.withLock { (values[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }

    fun put(key: String, value: Int) = set(key, JsonPrimitive(value))
    fun put(key: String, value: Float) = set(key, JsonPrimitive(value))
    fun put(key: String, value: Boolean) = set(key, JsonPrimitive(value))
    fun putOrRemove(key: String, value: String?) = set(key, if (value == null) null else JsonPrimitive(value))
    fun put(key: String, value: List<String>) = set(key, JsonArray(value.map(::JsonPrimitive)))

    fun remove(key: String) = set(key, null)

    // KeyValueStore, which the shared uploaders keep their own small state in, a file each.
    override fun get(key: String): String? = string(key)
    override fun put(key: String, value: String) = set(key, JsonPrimitive(value))
    override fun clear() {
        lock.withLock {
            if (values.isEmpty()) return
            values.clear()
            PosixFiles.delete(path)
        }
    }

    private fun set(key: String, value: JsonElement?) {
        lock.withLock {
            if (value == null || value is JsonNull) {
                if (values.remove(key) == null) return
            } else {
                if (values[key] == value) return
                values[key] = value
            }
            val bytes = JsonObject(values).toString().encodeToByteArray()
            try {
                if (private) PosixFiles.writePrivately(path, bytes) else PosixFiles.writeAtomically(path, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save $key", e)
            }
        }
    }

    private companion object {
        const val TAG = "JsonPrefs"
    }
}
