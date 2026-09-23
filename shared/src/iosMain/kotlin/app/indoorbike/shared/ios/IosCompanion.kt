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

import app.indoorbike.shared.companion.BrandSink
import app.indoorbike.shared.companion.CompanionAdvertiser
import app.indoorbike.shared.companion.CompanionProtocol
import app.indoorbike.shared.companion.ScreenshotImage
import app.indoorbike.shared.companion.ScreenshotSink
import app.indoorbike.shared.companion.TextFile
import app.indoorbike.shared.util.Log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject

/**
 * Bonjour for the companion socket: `_indoorbike._tcp`, named "Indoor Bike"
 * (the system makes it unique), with the Android advertisement's TXT records
 * so a companion finds either phone the same way.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class NetServiceAdvertiser : CompanionAdvertiser {
    private var service: NSNetService? = null
    private var delegate: Delegate? = null

    private class Delegate(val onResult: (Boolean) -> Unit) : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidPublish(sender: NSNetService) = onResult(true)
        override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
            Log.w("NetServiceAdvertiser", "Bonjour publish failed: $didNotPublish")
            onResult(false)
        }
    }

    override fun start(port: Int, sessionId: String, appVersion: String, onResult: (Boolean) -> Unit) {
        stop()
        val service = NSNetService(domain = "local.", type = CompanionProtocol.SERVICE_TYPE + ".", name = "Indoor Bike", port = port)
        val records = mapOf(
            "proto" to CompanionProtocol.SUPPORTED.toString(),
            "path" to CompanionProtocol.PATH,
            "app" to appVersion,
            "session" to sessionId,
            "mode" to "companion",
        ).mapValues { (_, value) -> NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) }
        service.setTXTRecordData(NSNetService.dataFromTXTRecordDictionary(records as Map<Any?, *>))
        val delegate = Delegate(onResult)
        service.delegate = delegate
        service.publish()
        this.service = service
        this.delegate = delegate
    }

    override fun stop() {
        service?.stop()
        service = null
        delegate = null
    }
}

/** A single file in Application Support, for [app.indoorbike.shared.companion.JsonPairedCompanionStore]. */
internal class IosTextFile(name: String) : TextFile {
    private val path = IosFiles.path(name)
    override fun read(): String? = IosFiles.readText(path)
    override fun write(text: String) = IosFiles.writeText(path, text)
    override fun delete() = IosFiles.delete(path)
}

/**
 * Screenshots a companion sent, kept with the ride they were taken on, laid
 * out as on Android: `ride_screenshots/<ride start>/<taken at>-<ride seconds>.png`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosScreenshotStore : ScreenshotSink {
    private val root = IosFiles.path("ride_screenshots")

    override fun keep(rideStartEpochMs: Long, image: ScreenshotImage, takenAtEpochMs: Long, rideSeconds: Int) {
        val dir = "$root/$rideStartEpochMs"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val extension = if (image.format == ScreenshotImage.PNG) "png" else "jpg"
        var path = "$dir/$takenAtEpochMs-$rideSeconds.$extension"
        var n = 2
        while (NSFileManager.defaultManager.fileExistsAtPath(path)) path = "$dir/$takenAtEpochMs-$rideSeconds-${n++}.$extension"
        IosFiles.writeBytes(path, image.bytes)
    }

    /** The ride's pictures, oldest first, as file paths. */
    fun list(rideStartEpochMs: Long): List<String> {
        val dir = "$root/$rideStartEpochMs"
        @Suppress("UNCHECKED_CAST")
        val names = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<String>).orEmpty()
        return names.filter { it.endsWith(".png") || it.endsWith(".jpg") }.sorted().map { "$dir/$it" }
    }

    fun deleteRide(rideStartEpochMs: Long) = IosFiles.delete("$root/$rideStartEpochMs")
}

/** The companion a ride was ridden with: `ride_brands/<ride start>/name.txt` and `icon.png`, as on Android. */
@OptIn(ExperimentalForeignApi::class)
class IosBrandStore : BrandSink {
    private val root = IosFiles.path("ride_brands")

    override fun keep(rideStartEpochMs: Long, name: String, iconPng: ByteArray?) {
        val dir = "$root/$rideStartEpochMs"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        IosFiles.writeText("$dir/name.txt", name)
        if (iconPng != null) IosFiles.writeBytes("$dir/icon.png", iconPng)
    }

    fun name(rideStartEpochMs: Long): String? = IosFiles.readText("$root/$rideStartEpochMs/name.txt")
    fun iconPath(rideStartEpochMs: Long): String? =
        "$root/$rideStartEpochMs/icon.png".takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    fun deleteRide(rideStartEpochMs: Long) = IosFiles.delete("$root/$rideStartEpochMs")
}
