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

import app.indoorbike.shared.ble.BleAdvertisement
import app.indoorbike.shared.ble.BleAvailability
import app.indoorbike.shared.ble.BleCentral
import app.indoorbike.shared.ble.BleCharacteristic
import app.indoorbike.shared.ble.BleLinkState
import app.indoorbike.shared.ble.BlePeripheral
import app.indoorbike.shared.ble.BleRadioOffException
import app.indoorbike.shared.ble.BleService
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.ReentrantLock
import app.indoorbike.shared.util.redactDeviceId
import app.indoorbike.shared.util.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The BLE radio on Sailfish OS: [BleCentral] carried over the bus to the Qt
 * side, which drives BlueZ over D-Bus (src/bluez*.cpp). There is no Kable on
 * Linux, and Qt Bluetooth is not in the Sailfish target; BlueZ's own API is
 * what the `Bluetooth` Sailjail permission opens.
 *
 * What goes to Qt (events): `ble.discovery` with the union of what every
 * running scan wants, `ble.connect`, `ble.disconnect`, `ble.subscribe`,
 * `ble.write` and `ble.read`. What comes back (commands, see [install]):
 * `ble.adapter`, `ble.advert`, `ble.link`, `ble.subscribed`, `ble.value`,
 * `ble.subscribeFailed` and `ble.done`, the last answering a write or a read
 * by its token.
 *
 * The managers call in from their own threads, so everything kept here is
 * under one lock. Devices are named by their Bluetooth address, as on Android.
 */
@OptIn(ExperimentalEncodingApi::class)
internal class BridgedCentral(private val bus: Bus) : BleCentral {
    private val lock = ReentrantLock()

    private val adapter = MutableStateFlow<BleAvailability>(BleAvailability.Unavailable("starting"))
    override val availability: Flow<BleAvailability> = adapter

    private val adverts = MutableSharedFlow<BleAdvertisement>(extraBufferCapacity = 64)

    /** The last few devices heard, newest last, for the developer page: name and address. */
    private val heard = ArrayDeque<Pair<String, String>>()
    private val scans = HashMap<Int, List<String>>()
    private var nextScan = 0
    private var discoveryWanted: Pair<Boolean, List<String>>? = null

    private val peripherals = HashMap<String, BridgedPeripheral>()
    private val subscriptions = HashMap<String, MutableList<Subscription>>()
    private val pending = HashMap<String, CompletableDeferred<ByteArray>>()
    private var nextToken = 0

    override fun scan(services: List<String>): Flow<BleAdvertisement> = channelFlow {
        if (adapter.value !is BleAvailability.Available) throw BleRadioOffException("Bluetooth is off")
        val id = lock.withLock { nextScan++.also { scans[it] = services } }
        updateDiscovery()
        try {
            launch {
                adapter.first { it !is BleAvailability.Available }
                close(BleRadioOffException("Bluetooth turned off"))
            }
            adverts.collect { ad ->
                if (services.isEmpty() || services.any { ad.offers(it) }) send(ad)
            }
        } finally {
            lock.withLock { scans.remove(id) }
            updateDiscovery()
        }
    }

    /** Ask Qt for discovery when any scan runs, filtered to the union of their services. */
    private fun updateDiscovery() {
        val wanted = lock.withLock {
            val on = scans.isNotEmpty()
            // One unfiltered scan (the heart rate monitor's) means no filter at all.
            val uuids = if (scans.values.any { it.isEmpty() }) emptyList() else scans.values.flatten().distinct()
            val next = on to uuids
            if (next == discoveryWanted) return
            discoveryWanted = next
            next
        }
        bus.event("ble.discovery", buildJsonObject {
            put("on", wanted.first)
            put("uuids", buildJsonArray { wanted.second.forEach { add(JsonPrimitive(it)) } })
        })
    }

    override fun peripheral(
        scope: CoroutineScope,
        advertisement: BleAdvertisement,
        logTag: String,
        label: String,
    ): BlePeripheral {
        val address = advertisement.platform as? String ?: advertisement.deviceId
        val created = BridgedPeripheral(address, logTag, label)
        lock.withLock { peripherals[address] = created }
        // The link's scope ending is the link ending: forget it, and its subscriptions with it.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            lock.withLock { if (peripherals[address] === created) peripherals.remove(address) }
        }
        return created
    }

    // --- Reports from Qt -------------------------------------------------------------------

    /** The bus commands Qt reports through, and the `bluetooth` topic. */
    fun install() {
        bus.publish("bluetooth", adapter) { state ->
            buildJsonObject {
                put("available", state is BleAvailability.Available)
                put("reason", (state as? BleAvailability.Unavailable)?.reason)
            }
        }
        bus.command("ble.heard") {
            buildJsonArray {
                lock.withLock { heard.toList() }.forEach { (name, address) ->
                    add(buildJsonObject {
                        put("name", name)
                        put("address", address)
                    })
                }
            }
        }
        bus.command("ble.adapter") { args ->
            adapter.value = if (args.bool("available") == true) BleAvailability.Available
            else BleAvailability.Unavailable(args.string("reason") ?: "off")
            JsonNull
        }
        bus.command("ble.advert") { args ->
            val address = args.requireString("address")
            lock.withLock {
                heard.removeAll { it.second == address }
                heard.addLast((args.string("name") ?: args.string("alias") ?: "") to address)
                while (heard.size > HEARD_KEPT) heard.removeFirst()
            }
            adverts.tryEmit(
                BleAdvertisement(
                    deviceId = address,
                    name = args.string("name"),
                    peripheralName = args.string("alias"),
                    rssi = args.int("rssi") ?: 0,
                    serviceUuids = (args["uuids"] as? JsonArray)?.mapNotNull { it.stringOrNull } ?: emptyList(),
                    platform = address,
                ),
            )
            JsonNull
        }
        bus.command("ble.link") { args ->
            val address = args.requireString("address")
            val peripheral = lock.withLock { peripherals[address] } ?: return@command JsonNull
            peripheral.onLink(args)
            JsonNull
        }
        bus.command("ble.subscribed") { args ->
            subscribersOf(args).forEach { it.onSubscribed() }
            JsonNull
        }
        bus.command("ble.subscribeFailed") { args ->
            val message = args.string("message") ?: "BlueZ refused"
            subscribersOf(args).forEach { it.onFailed(message) }
            JsonNull
        }
        bus.command("ble.value") { args ->
            val bytes = Base64.decode(args.requireString("b64"))
            subscribersOf(args).forEach { it.onValue(bytes) }
            JsonNull
        }
        bus.command("ble.done") { args ->
            val waiting = lock.withLock { pending.remove(args.requireString("token")) } ?: return@command JsonNull
            if (args.bool("ok") == true) {
                waiting.complete(args.string("b64")?.let { Base64.decode(it) } ?: ByteArray(0))
            } else {
                waiting.completeExceptionally(Exception(args.string("message") ?: "BlueZ refused"))
            }
            JsonNull
        }
    }

    private fun subscribersOf(args: JsonObject): List<Subscription> {
        val key = subscriptionKey(args.requireString("address"), args.requireString("char"))
        return lock.withLock { subscriptions[key]?.toList() } ?: emptyList()
    }

    private fun subscriptionKey(address: String, characteristic: String) = "$address|${characteristic.lowercase()}"

    private class Subscription(
        val onValue: (ByteArray) -> Unit,
        val onSubscribed: () -> Unit,
        val onFailed: (String) -> Unit,
    )

    /** One write or read, answered by `ble.done` with its token. */
    private suspend fun call(topic: String, payload: JsonObject, what: String): ByteArray {
        val answer = CompletableDeferred<ByteArray>()
        val token = lock.withLock { "t${nextToken++}".also { pending[it] = answer } }
        bus.event(topic, buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
            put("token", token)
        })
        try {
            return withTimeoutOrNull(GATT_TIMEOUT_MS) { answer.await() }
                ?: throw Exception("$what timed out")
        } finally {
            lock.withLock { pending.remove(token) }
        }
    }

    private inner class BridgedPeripheral(
        private val address: String,
        private val logTag: String,
        private val label: String,
    ) : BlePeripheral {
        private val linkState = MutableStateFlow(BleLinkState.Disconnected)
        override val state: StateFlow<BleLinkState> = linkState

        private var serviceList: List<BleService>? = null
        override val services: List<BleService>? get() = lock.withLock { serviceList }

        private var connecting: CompletableDeferred<Unit>? = null

        fun onLink(args: JsonObject) {
            val next = when (args.string("state")) {
                "Connecting" -> BleLinkState.Connecting
                "Connected" -> BleLinkState.Connected
                "Disconnecting" -> BleLinkState.Disconnecting
                else -> BleLinkState.Disconnected
            }
            val error = args.string("error")
            val waiting = lock.withLock {
                // The table first: a manager reads it the moment it sees Connected.
                if (next == BleLinkState.Connected) serviceList = parseServices(args["services"] as? JsonArray)
                if (next == BleLinkState.Disconnected) serviceList = null
                connecting.also { if (next == BleLinkState.Connected || next == BleLinkState.Disconnected) connecting = null }
            }
            linkState.value = next
            when {
                waiting == null -> Unit
                next == BleLinkState.Connected -> waiting.complete(Unit)
                next == BleLinkState.Disconnected -> waiting.completeExceptionally(Exception(error ?: "The connection failed"))
            }
        }

        private fun parseServices(array: JsonArray?): List<BleService> = array.orEmpty().map { element ->
            val service = element.jsonObject
            val serviceUuid = service.requireString("uuid")
            BleService(
                uuid = serviceUuid,
                characteristics = (service["chars"] as? JsonArray).orEmpty().map { c ->
                    val char = c.jsonObject
                    BleCharacteristic(serviceUuid, char.requireString("uuid"), char.int("props") ?: 0)
                },
            )
        }

        override suspend fun connect() {
            val result = CompletableDeferred<Unit>()
            lock.withLock { connecting = result }
            linkState.value = BleLinkState.Connecting
            bus.event("ble.connect", buildJsonObject { put("address", address) })
            result.await()
        }

        override suspend fun disconnect() {
            if (linkState.value == BleLinkState.Disconnected) return
            bus.event("ble.disconnect", buildJsonObject { put("address", address) })
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { linkState.first { it == BleLinkState.Disconnected } }
        }

        override fun observe(characteristic: BleCharacteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> =
            callbackFlow {
                val key = subscriptionKey(address, characteristic.uuid)
                val subscription = Subscription(
                    onValue = { trySend(it) },
                    onSubscribed = { launch { onSubscription() } },
                    onFailed = { message ->
                        val cause = Exception(message)
                        Log.w(logTag, "$label observation failed on ${redactDeviceId(address)}", cause)
                        close(cause)
                    },
                )
                val first = lock.withLock {
                    val list = subscriptions.getOrPut(key) { mutableListOf() }
                    list += subscription
                    list.size == 1
                }
                if (first) bus.event("ble.subscribe", target(characteristic) { put("on", true) })
                awaitClose {
                    val last = lock.withLock {
                        val list = subscriptions[key]
                        list?.remove(subscription)
                        if (list.isNullOrEmpty()) subscriptions.remove(key)
                        list.isNullOrEmpty()
                    }
                    if (last) bus.event("ble.subscribe", target(characteristic) { put("on", false) })
                }
            }

        override suspend fun write(characteristic: BleCharacteristic, data: ByteArray, withResponse: Boolean) {
            call("ble.write", target(characteristic) {
                put("b64", Base64.encode(data))
                put("response", withResponse)
            }, "Write")
        }

        override suspend fun read(characteristic: BleCharacteristic): ByteArray =
            call("ble.read", target(characteristic) { }, "Read")

        private fun target(c: BleCharacteristic, extra: JsonObjectBuilder.() -> Unit) =
            buildJsonObject {
                put("address", address)
                put("service", c.serviceUuid)
                put("char", c.uuid)
                extra()
            }
    }

    private companion object {
        /** A write or a read that BlueZ has not answered in this long is not going to be. */
        const val GATT_TIMEOUT_MS = 10_000L
        const val DISCONNECT_TIMEOUT_MS = 3_000L
        const val HEARD_KEPT = 20
    }
}
