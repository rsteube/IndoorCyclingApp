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

import app.indoorbike.shared.net.HttpClient
import app.indoorbike.shared.net.PostResult
import app.indoorbike.shared.net.buildMultipartBody
import app.indoorbike.shared.net.formBody
import app.indoorbike.shared.sync.OwnSlice
import app.indoorbike.shared.sync.SyncStorage
import app.indoorbike.shared.util.KeyValueStore
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.secureRandomBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL

/** [IosHttp], under its https-only, no-redirect rules, as the shared uploaders ask for HTTPS. */
object IosHttpClient : HttpClient {
    private const val REPLY_CHARS = 4096

    override suspend fun getText(url: String, headers: Map<String, String>): String = IosHttp.getText(url, headers)

    override suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>): PostResult =
        send(url, "POST", headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
        ), formBody(fields).encodeToByteArray())

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): PostResult =
        send(url, "POST", headers + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ), json.encodeToByteArray())

    override suspend fun postMultipartFile(
        url: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String,
        extraFormFields: Map<String, String>,
        headers: Map<String, String>,
    ): PostResult {
        val boundary = "----IndoorBike" + secureRandomBytes(8).joinToString("") { (it.toInt() and 0xFF).toString(16) }
        val body = buildMultipartBody(boundary, fileFieldName, fileName, fileBytes, fileMimeType, extraFormFields)
        return send(url, "POST", mapOf("Content-Type" to "multipart/form-data; boundary=$boundary") + headers, body, 48.0)
    }

    override suspend fun delete(url: String, headers: Map<String, String>): PostResult =
        send(url, "DELETE", mapOf("Accept" to "application/json") + headers, null)

    private suspend fun send(url: String, method: String, headers: Map<String, String>, body: ByteArray?, timeout: Double = 12.0): PostResult {
        val response = IosHttp.request(url, method, headers, body, maxBytes = 16L * 1024, timeoutSeconds = timeout)
        return PostResult(response.code, response.body.decodeToString().take(REPLY_CHARS))
    }
}

/** A few strings in NSUserDefaults under one prefix: a sign-in in progress. */
class DefaultsStore(private val prefix: String) : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey("$prefix.$key")
    override fun put(key: String, value: String) = defaults.setObject(value, "$prefix.$key")
    override fun clear() {
        for (key in listOf("state", "pkce_verifier")) defaults.removeObjectForKey("$prefix.$key")
    }
}

/**
 * The rider's sync folder on iOS: a folder they picked in Files (iCloud
 * Drive, a file provider for Nextcloud or Dropbox, or on the device), kept as
 * a security-scoped bookmark in `syncFolderUri`. The same files and names as
 * Android's `SafSyncStorage`: `indoorbike-device-<id>.json`, with the copy it
 * replaced beside it as `.prev`. Every read and write is coordinated, so a
 * file provider syncing the folder never sees half a file.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSyncStorage(private val bookmarkBase64: String) : SyncStorage {

    private fun <T> inFolder(block: (NSURL) -> T): T {
        val data = NSData.create(base64EncodedString = bookmarkBase64, options = 0u)
            ?: throw IllegalStateException("Sync folder unreachable")
        val folder = NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null)
            ?: throw IllegalStateException("Sync folder unreachable")
        val accessed = folder.startAccessingSecurityScopedResource()
        try {
            return block(folder)
        } finally {
            if (accessed) folder.stopAccessingSecurityScopedResource()
        }
    }

    private fun read(url: NSURL): String? {
        var text: String? = null
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(url, NSFileCoordinatorReadingWithoutChanges, null) { actual ->
            text = actual?.let { NSString.stringWithContentsOfURL(it, NSUTF8StringEncoding, null) }
        }
        return text
    }

    override suspend fun readAllSlices(): List<String> = withContext(Dispatchers.IO) {
        try {
            inFolder { folder ->
                @Suppress("UNCHECKED_CAST")
                val names = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder.path ?: "", null) as? List<String>).orEmpty()
                names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }
                    .mapNotNull { name -> runCatching { read(folder.URLByAppendingPathComponent(name)!!) }.getOrNull() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't list the sync folder: ${t.message}")
            emptyList()
        }
    }

    override suspend fun readOwnSlice(deviceId: String): OwnSlice = withContext(Dispatchers.IO) {
        try {
            inFolder { folder ->
                val name = sliceFileName(deviceId)
                val manager = NSFileManager.defaultManager
                val paths = listOf(name, "$name.prev").map { folder.URLByAppendingPathComponent(it)!! }
                val present = paths.filter { manager.fileExistsAtPath(it.path ?: "") }
                if (present.isEmpty()) return@inFolder OwnSlice.Missing
                val texts = present.mapNotNull { read(it) }
                if (texts.isEmpty()) OwnSlice.Unreadable("$name couldn't be read") else OwnSlice.Found(texts)
            }
        } catch (t: Throwable) {
            OwnSlice.Unreadable("the sync folder can't be opened")
        }
    }

    override suspend fun writeOwnSlice(deviceId: String, json: String): Unit = withContext(Dispatchers.IO) {
        inFolder { folder ->
            val name = sliceFileName(deviceId)
            val target = folder.URLByAppendingPathComponent(name)!!
            val previous = folder.URLByAppendingPathComponent("$name.prev")!!
            val manager = NSFileManager.defaultManager
            var failure: String? = null
            NSFileCoordinator(filePresenter = null).coordinateWritingItemAtURL(target, NSFileCoordinatorWritingForReplacing, null) { actual ->
                val url = actual ?: target
                // Keep the copy being replaced, so a slice written half-way
                // still has a whole one behind it.
                if (manager.fileExistsAtPath(url.path ?: "")) {
                    manager.removeItemAtURL(previous, null)
                    if (!manager.copyItemAtURL(url, previous, null)) Log.w(TAG, "Couldn't keep $name.prev")
                }
                val data = NSString.create(string = json).dataUsingEncoding(NSUTF8StringEncoding)
                if (data == null || !data.writeToURL(url, atomically = true)) failure = "Couldn't write $name in the sync folder"
            }
            failure?.let { throw IllegalStateException(it) }
        }
    }

    companion object {
        private const val TAG = "IosSyncStorage"
        private const val PREFIX = "indoorbike-device-"

        /** Android's name for a device's slice, so both platforms share one folder. */
        fun sliceFileName(deviceId: String): String =
            PREFIX + deviceId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "device" } + ".json"
    }
}
