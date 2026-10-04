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

import app.indoorbike.shared.companion.BrandSink
import app.indoorbike.shared.companion.CompanionAnnouncement
import app.indoorbike.shared.companion.CompanionBeacon
import app.indoorbike.shared.companion.CompanionDiscovery
import app.indoorbike.shared.companion.CompanionHost
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.companion.ScreenshotImage
import app.indoorbike.shared.companion.ScreenshotSink
import app.indoorbike.shared.route.formatGradientPercent
import app.indoorbike.shared.route.gradientBand
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import app.indoorbike.shared.util.sha256
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.close
import platform.posix.errno
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

/**
 * Screenshots a companion sent, kept with the ride they were taken on, laid
 * out as on Android and iOS: `ride_screenshots/<ride start>/<taken at>-<ride seconds>.png`.
 */
internal class LinuxScreenshotStore : ScreenshotSink {
    private val root = SailfishPaths.data("ride_screenshots")

    override fun keep(rideStartEpochMs: Long, image: ScreenshotImage, takenAtEpochMs: Long, rideSeconds: Int) {
        val dir = "$root/$rideStartEpochMs"
        PosixFiles.mkdirs(dir)
        val extension = if (image.format == ScreenshotImage.PNG) "png" else "jpg"
        var path = "$dir/$takenAtEpochMs-$rideSeconds.$extension"
        var n = 2
        while (PosixFiles.exists(path)) path = "$dir/$takenAtEpochMs-$rideSeconds-${n++}.$extension"
        PosixFiles.writeAtomically(path, image.bytes)
    }

    /** The ride's pictures, oldest first, as file paths. */
    fun list(rideStartEpochMs: Long): List<String> {
        val dir = "$root/$rideStartEpochMs"
        return PosixFiles.list(dir).filter { it.endsWith(".png") || it.endsWith(".jpg") }.map { "$dir/$it" }
    }

    fun deleteRide(rideStartEpochMs: Long) = PosixFiles.deleteTree("$root/$rideStartEpochMs")
}

/** The companion a ride was ridden with: `ride_brands/<ride start>/name.txt` and `icon.png`, as on Android and iOS. */
internal class LinuxBrandStore : BrandSink {
    private val root = SailfishPaths.data("ride_brands")

    override fun keep(rideStartEpochMs: Long, name: String, iconPng: ByteArray?) {
        val dir = "$root/$rideStartEpochMs"
        PosixFiles.mkdirs(dir)
        PosixFiles.writeTextAtomically("$dir/name.txt", name)
        if (iconPng != null) PosixFiles.writeAtomically("$dir/icon.png", iconPng)
    }

    fun name(rideStartEpochMs: Long): String? = PosixFiles.readText("$root/$rideStartEpochMs/name.txt")

    fun iconPath(rideStartEpochMs: Long): String? = "$root/$rideStartEpochMs/icon.png".takeIf(PosixFiles::exists)

    fun deleteRide(rideStartEpochMs: Long) = PosixFiles.deleteTree("$root/$rideStartEpochMs")
}

// <net/if.h> values, the same on every Linux ABI.
private const val IFF_UP = 0x1
private const val IFF_BROADCAST = 0x2
private const val IFF_LOOPBACK = 0x8

/**
 * The UDP discovery beacon, as Android's `UdpCompanionBeacon` sends it: the
 * same datagram to every interface's broadcast address, so a companion with no
 * DNS-SD can still find the phone. Sailfish has no Avahi to advertise with, so
 * this and the typed-in address are the two ways in. iOS's beacon, on Linux.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LinuxCompanionBeacon(private val scope: CoroutineScope) : CompanionBeacon {

    private var job: Job? = null

    override fun start(payload: () -> CompanionAnnouncement?) {
        stop()
        job = scope.launch {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            if (fd < 0) {
                Log.w(TAG, "Discovery beacon has no socket: " + strerror(errno)?.toKString())
                return@launch
            }
            try {
                memScoped {
                    val on = alloc<IntVar>()
                    on.value = 1
                    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, on.ptr, sizeOf<IntVar>().convert())
                }
                // The first outcome is logged, and a change after it.
                var lastOutcome: String? = null
                while (isActive) {
                    val announcement = payload()
                    if (announcement != null) {
                        val bytes = CompanionDiscovery.encode(announcement).encodeToByteArray()
                        // Read every tick: joining another Wi-Fi changes them.
                        for (address in broadcastAddresses()) {
                            val error = send(fd, bytes, address)
                            val outcome = if (error == null) "sending to $address" else "refused to $address: $error"
                            if (outcome != lastOutcome) {
                                Log.i(TAG, "Discovery beacon $outcome")
                                lastOutcome = outcome
                            }
                        }
                    }
                    delay(
                        if (announcement?.paired == true) CompanionProtocol.DISCOVERY_INTERVAL_PAIRED_MS
                        else CompanionProtocol.DISCOVERY_INTERVAL_MS
                    )
                }
            } finally {
                close(fd)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends one datagram; null when it went, else why not. */
    private fun send(fd: Int, bytes: ByteArray, address: BroadcastAddress): String? = memScoped {
        val target = alloc<sockaddr_in>()
        target.sin_family = AF_INET.convert()
        // Network byte order; every Sailfish phone is little-endian.
        val port = CompanionProtocol.DISCOVERY_PORT
        target.sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).convert()
        target.sin_addr.s_addr = address.networkOrder
        val sent = bytes.usePinned { pinned ->
            sendto(fd, pinned.addressOf(0), bytes.size.convert(), 0, target.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }
        if (sent < 0) strerror(errno)?.toKString() ?: "error $errno" else null
    }

    private class BroadcastAddress(val networkOrder: UInt, val text: String) {
        override fun toString() = text
    }

    /** Every up, non-loopback IPv4 interface's broadcast address, once each. */
    private fun broadcastAddresses(): List<BroadcastAddress> = memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
        val out = LinkedHashMap<UInt, BroadcastAddress>()
        try {
            var node = head.value
            while (node != null) {
                val entry = node.pointed
                val flags = entry.ifa_flags.toInt()
                val address = entry.ifa_addr
                // On a broadcast interface the union holds its broadcast address.
                val broadcast = entry.ifa_ifu.ifu_broadaddr
                if (address != null && broadcast != null && address.pointed.sa_family.toInt() == AF_INET &&
                    flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0 && flags and IFF_BROADCAST != 0
                ) {
                    val value = broadcast.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                    val bytes = (0 until 4).map { (value shr (8 * it)) and 0xFFu }
                    // 169.254/16 is a cable to a computer, not a network a game is on.
                    if (!(bytes[0] == 169u && bytes[1] == 254u)) {
                        out.getOrPut(value) { BroadcastAddress(value, bytes.joinToString(".")) }
                    }
                }
                node = entry.ifa_next
            }
        } finally {
            freeifaddrs(head.value)
        }
        out.values.toList()
    }

    private companion object {
        const val TAG = "CompanionBeacon"
    }
}

/**
 * A companion's icon, for QML to draw: written to the cache under a name
 * made from its bytes, so the same icon is written once and a changed one is a
 * new file that Image does not take from its own cache.
 */
internal fun companionIconFile(png: ByteArray): String? = try {
    val name = sha256(png).take(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    val path = SailfishPaths.cache("companion-icon-$name.png")
    if (!PosixFiles.exists(path)) PosixFiles.writeAtomically(path, png)
    path
} catch (e: Exception) {
    Log.w("Companion", "Couldn't keep the companion's icon", e)
    null
}

/**
 * Companion mode as topics and commands:
 *
 *  - `companion`: the socket (address, port, code), who is connected,
 *    pairing, the session and the companion's look.
 *  - `companionRoad`: the road the game describes, a few times a second.
 *
 * The socket is open only while the Companion page or a companion ride is on
 * screen: the page sends `companion.start` and `companion.stop`.
 */
internal fun SailfishCore.installCompanion() {
    val host = companionHost
    val e = companionEngine

    val flows: List<Flow<Any?>> = listOf(
        host.status, host.pendingPairing, host.pairedCompanions, host.sessionPhase, host.sessionRides,
        host.sessionCompanionName, host.look, host.screenshotStatus, host.screenshotsOffered, host.hosting,
        host.remoteControls, userSettings.companionShowAllData,
    )
    bus.publish("companion", combine(flows) { it.toList() }) { v ->
        val status = v[0] as app.indoorbike.shared.companion.CompanionStatus
        val pending = v[1] as CompanionHost.PendingPairing?
        @Suppress("UNCHECKED_CAST")
        val paired = v[2] as List<app.indoorbike.shared.companion.PairedCompanion>
        val phase = v[3] as CompanionHost.SessionPhase
        @Suppress("UNCHECKED_CAST")
        val rides = v[4] as List<app.indoorbike.shared.companion.SavedCompanionRide>
        val look = v[6] as CompanionHost.Look?
        val shot = v[7] as CompanionHost.ScreenshotStatus?
        val controls = v[10] as app.indoorbike.shared.companion.RemoteControls?
        buildJsonObject {
            put("running", status.running)
            put("port", status.port)
            put("token", status.token)
            put("addresses", buildJsonArray { status.addresses.forEach { add(JsonPrimitive(it)) } })
            put("controllerName", status.controllerName)
            put("controllerAddress", status.controllerAddress)
            put("observers", status.observers)
            put("receivingRoad", status.receivingRoad)
            put("label", status.label)
            put("world", status.world)
            put("error", status.error)
            put("pendingPairing", pending?.let { p ->
                buildJsonObject {
                    put("deviceId", p.deviceId)
                    put("name", p.name)
                }
            } ?: JsonNull)
            put("paired", buildJsonArray {
                paired.forEach { p ->
                    add(buildJsonObject {
                        put("deviceId", p.deviceId)
                        put("name", p.name)
                        put("lastSeen", p.lastSeenEpochMs)
                    })
                }
            })
            put("phase", phase.name)
            put("sessionRides", buildJsonArray {
                rides.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.sessionId.toString())
                        put("name", r.name)
                        put("durationSeconds", r.durationSeconds)
                        put("averagePower", r.averagePowerWatts)
                    })
                }
            })
            put("companionName", v[5] as String?)
            put("lookName", look?.name)
            put("icon", look?.icon as? String)
            put("accent", look?.palette?.let { argb(it.accent.toLong() and 0xFFFFFFFFL) } ?: JsonNull)
            put("screenshotMessage", shot?.message)
            put("screenshotWaiting", shot?.kind == CompanionHost.ScreenshotStatus.Kind.WAITING)
            put("screenshotsOffered", v[8] as Boolean)
            put("hosting", v[9] as Boolean)
            put("controls", controls?.let { c -> encodeControls(c) } ?: JsonNull)
            put("showAllData", v[11] as Boolean)
            put("debug", debug)
        }
    }

    val road: List<Flow<Any?>> = listOf(
        e.gradient, e.speedMps, e.distanceMeters, e.elevationGainMeters, e.gearIndex, e.drivetrain, e.roadStale,
        e.powerTarget,
    )
    bus.publish("companionRoad", combine(road) { it.toList() }, minIntervalMs = 250) { v ->
        val drivetrain = v[5] as app.indoorbike.shared.simulation.Drivetrain
        val gear = v[4] as Int
        buildJsonObject {
            val gradient = v[0] as Double
            put("gradient", gradient)
            put("gradientText", formatGradientPercent(gradient))
            put("band", gradientBand(gradient).name)
            put("speedKmh", (v[1] as Double) * 3.6)
            put("distanceMeters", v[2] as Double)
            put("climbedMeters", v[3] as Double)
            put("gear", drivetrain.label(gear))
            put("roadStale", v[6] as Boolean)
            put("powerTarget", v[7] as Int?)
        }
    }

    bus.command("companion.start") { host.start(); JsonNull }
    bus.command("companion.stop") { host.stop(); JsonNull }
    bus.command("companion.refreshAddresses") { host.refreshAddresses(); JsonNull }
    bus.command("companion.acceptPairing") { host.acceptPairing(); JsonNull }
    bus.command("companion.declinePairing") { host.declinePairing(); JsonNull }
    bus.command("companion.forget") { args -> host.forgetPairing(args.requireString("deviceId")); JsonNull }
    bus.command("companion.revoke") { host.revokeControl(); JsonNull }
    bus.command("companion.press") { args -> host.pressControl(args.requireString("control")); JsonNull }
    bus.command("companion.screenshot") { host.requestScreenshot(); JsonNull }
    bus.command("companion.endSession") { host.endSession(); JsonNull }
    /** {"steps": 1} one gear harder, -1 easier. */
    bus.command("companion.shift") { args -> e.shiftBy(args.requireInt("steps"), source = "screen"); JsonNull }
    bus.command("companion.showAllData") { args ->
        userSettings.setCompanionShowAllData(args.bool("value") ?: false)
        JsonNull
    }
    /** A debug build's way to the companion ride screen with no game connected. */
    bus.command("companion.withoutCompanion") {
        if (!debug) throw UserFacing("Only in a debug build")
        host.prepareRideWithoutCompanion()
        JsonNull
    }
}

private fun encodeControls(controls: app.indoorbike.shared.companion.RemoteControls): JsonObject = buildJsonObject {
    put("title", controls.title)
    put("hint", controls.hint)
    put("enabled", buildJsonArray { controls.enabled.forEach { add(JsonPrimitive(it)) } })
}
