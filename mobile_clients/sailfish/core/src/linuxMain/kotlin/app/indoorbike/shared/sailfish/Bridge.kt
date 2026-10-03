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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * The whole surface between the shared Kotlin and the Qt app: four functions,
 * the generated libindoorbikecore_api.h's `indoorbikeStart`, `indoorbikeSubscribe`,
 * `indoorbikeSend` and `indoorbikeStop`.
 *
 * Commands go in as JSON and are answered at once; everything that takes time,
 * and every change in state, comes back as an event through the callback:
 * {"topic": ..., "payload": ...}. One bus rather than a C function per method,
 * because the shared managers are many and their flows keep changing, and
 * marshalling each across C by hand would be redone at every change. The shape
 * both of Snapp's earlier Sailfish ports use. See [Bus] for the message shapes.
 *
 * Threading: a command runs on the core's own thread and the calling (Qt)
 * thread waits for its answer, which is quick because nothing slow runs in a
 * command. Events come from the core's threads; the Qt side only copies the
 * string and queues it to its main thread, or the two could wait on each other.
 */

/** The C function the app registers for events: void (*)(const char *json). */
@OptIn(ExperimentalForeignApi::class)
typealias EventSink = CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>>

/**
 * The one thread the shared objects are driven from, where Android drives them
 * from its main thread. Kotlin/Native cannot dispatch onto a Qt event loop, so
 * the core has its own. The managers still move their own work to their own
 * threads, as on Android.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal val coreDispatcher: CloseableCoroutineDispatcher by lazy { newSingleThreadContext("indoorbike-core") }

private val json = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalForeignApi::class)
private var sink: EventSink? = null

private var core: SailfishCore? = null

/**
 * Start the core. The three folders are the app's own under Sailjail, which
 * only Qt knows; [packageDir] is where the package's read-only files are (the
 * bundled workouts and routes). Calling it twice is harmless.
 */
fun indoorbikeStart(
    dataDir: String,
    configDir: String,
    cacheDir: String,
    packageDir: String,
    appVersion: String,
    debug: Boolean,
): Boolean = runCatching {
    if (core != null) return@runCatching true
    SailfishPaths.configure(dataDir, configDir, cacheDir)
    val started = runBlocking(coreDispatcher) {
        SailfishCore(packageDir, appVersion, debug, ::emit).also { it.start() }
    }
    core = started
    true
}.getOrElse {
    Log.e("Bridge", "Start failed", it)
    false
}

/** Register the event callback, before [indoorbikeStart]. The pointer must stay valid until [indoorbikeStop]. */
@OptIn(ExperimentalForeignApi::class)
fun indoorbikeSubscribe(callback: EventSink) {
    sink = callback
}

/** Run one command and return its answer. Never waits on the trainer or the network. */
fun indoorbikeSend(command: String): String {
    val running = core ?: return Bus.failure("The core is not running.").toString()
    return runCatching {
        val request = json.parseToJsonElement(command).jsonObject
        val name = request.string("cmd") ?: return@runCatching Bus.failure("No cmd")
        runBlocking(coreDispatcher) { running.bus.handle(name, request) }
    }.getOrElse { Bus.failure(it.message ?: it.toString()) }.toString()
}

/** Stop everything the core started. */
@OptIn(ExperimentalForeignApi::class)
fun indoorbikeStop() {
    core?.let { running -> runBlocking(coreDispatcher) { running.stop() } }
    core = null
    sink = null
}

/** One event to the app, from any thread. */
@OptIn(ExperimentalForeignApi::class)
private fun emit(topic: String, payload: JsonElement) {
    val target = sink ?: return
    val text = buildJsonObject {
        put("topic", topic)
        put("payload", payload)
    }.toString()
    // The app copies the string before it returns, so a scoped one is enough.
    memScoped { target.invoke(text.cstr.ptr) }
}
