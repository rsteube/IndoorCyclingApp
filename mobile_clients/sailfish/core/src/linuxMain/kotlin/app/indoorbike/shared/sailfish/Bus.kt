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

import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The core's side of the bus to the Qt app: the commands it answers and the
 * topics it publishes. Each feature registers its own, in its own file, on the
 * one bus [SailfishCore] holds.
 *
 * Three shapes, all JSON:
 *  - **A command** (`{"cmd": "trainer.scan"}`) runs on the core thread and is
 *    answered at once: `{"ok": true, "value": ...}` or `{"ok": false,
 *    "message": ...}`. It must not wait on anything slow.
 *  - **A request** is a command whose work takes time: a save, a fetch, an
 *    export. The caller gives it a `requestId`, gets `{"ok": true}` at once, and the
 *    answer arrives later as a `reply` event carrying it.
 *  - **A topic** without a dot is state, sent whole whenever it changes; with
 *    a dot it is a one-off event. See IndoorBikeCore on the Qt side.
 */
internal class Bus(
    val scope: CoroutineScope,
    private val send: (topic: String, payload: JsonElement) -> Unit,
) {
    private val commands = HashMap<String, (JsonObject) -> JsonElement>()
    private val requests = HashMap<String, suspend (JsonObject) -> JsonElement>()

    /** A quick command. [handler] returns the reply's value, or throws with a message for the user. */
    fun command(name: String, handler: (JsonObject) -> JsonElement) {
        check(name !in commands && name !in requests) { "$name registered twice" }
        commands[name] = handler
    }

    /** A command whose answer comes later, as a `reply` event. */
    fun request(name: String, handler: suspend (JsonObject) -> JsonElement) {
        check(name !in commands && name !in requests) { "$name registered twice" }
        requests[name] = handler
    }

    /**
     * Send [flow] on [topic] whenever what it encodes to changes. With
     * [minIntervalMs], at most that often: the newest value wins, so a fast
     * flow cannot flood the Qt thread.
     */
    fun <T> publish(topic: String, flow: Flow<T>, minIntervalMs: Long = 0, encode: (T) -> JsonElement) {
        scope.launch {
            var encoded = flow.map(encode).distinctUntilChanged()
            if (minIntervalMs > 0) encoded = encoded.conflate()
            encoded.collect {
                send(topic, it)
                if (minIntervalMs > 0) delay(minIntervalMs)
            }
        }
    }

    /** A one-off event. */
    fun event(topic: String, payload: JsonElement = JsonNull) = send(topic, payload)

    /** Run one command or start one request. Called on the core thread. */
    fun handle(name: String, args: JsonObject): JsonObject {
        commands[name]?.let { handler ->
            return try {
                ok(handler(args))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
                failure(e.message ?: e.toString())
            }
        }
        val request = requests[name] ?: return failure("Unknown command $name")
        val id = args.string("requestId") ?: return failure("$name needs a requestId")
        scope.launch {
            val reply = try {
                ok(request(args))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
                failure(e.message ?: e.toString())
            }
            send("reply", buildJsonObject {
                put("requestId", id)
                reply.forEach { (key, value) -> put(key, value) }
            })
        }
        return ok(JsonNull)
    }

    companion object {
        private const val TAG = "Bus"

        fun ok(value: JsonElement) = buildJsonObject {
            put("ok", true)
            put("value", value)
        }

        fun failure(message: String) = buildJsonObject {
            put("ok", false)
            put("message", message)
        }
    }
}

/** A failure the user should read: thrown from a handler, its message is the reply's. */
internal class UserFacing(message: String) : Exception(message)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.requireString(key: String): String = string(key) ?: throw UserFacing("Missing $key")
internal fun JsonObject.requireInt(key: String): Int = int(key) ?: throw UserFacing("Missing $key")
internal fun JsonObject.requireLong(key: String): Long = long(key) ?: throw UserFacing("Missing $key")

internal val JsonElement.stringOrNull: String? get() = (this as? JsonPrimitive)?.contentOrNull
internal fun jsonOf(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
internal fun jsonOf(value: Number?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
