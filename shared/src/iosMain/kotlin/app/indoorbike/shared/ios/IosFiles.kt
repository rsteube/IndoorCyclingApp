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

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.synchronizeFile
import platform.Foundation.writeData
import platform.Foundation.writeToURL

/**
 * The iOS side of the Android app's `AtomicFiles`: small files that must
 * survive the app being killed at any instant, with either the old contents or
 * the new but never neither.
 *
 * Foundation already writes that way - [NSDataWritingAtomic] writes a
 * temporary file and renames it over the target - so there is no staging file
 * of our own to find again after a crash.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosFiles {

    /** Application Support: kept, backed up, and never shown to the rider in Files. */
    val directory: String by lazy {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        requireNotNull(url?.path) { "No Application Support directory" }
    }

    fun path(name: String): String = "$directory/$name"

    fun readText(path: String): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }

    /** Replace the file's contents with [text], atomically. Throws if the write fails. */
    fun writeText(path: String, text: String) {
        val ok = utf8(text).writeToURL(NSURL.fileURLWithPath(path), NSDataWritingAtomic, null)
        check(ok) { "Couldn't write $path" }
    }

    /** Add [text] to the end of the file, creating it if needed, and sync it to disk. */
    fun appendText(path: String, text: String) {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) {
            check(manager.createFileAtPath(path, contents = null, attributes = null)) { "Couldn't create $path" }
        }
        val handle = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path)) { "Couldn't open $path" }
        try {
            handle.seekToEndOfFile()
            handle.writeData(utf8(text))
            handle.synchronizeFile()
        } finally {
            handle.closeFile()
        }
    }

    /**
     * Move an unreadable file out of the way rather than deleting it, so the
     * next write can proceed and the bytes are still there to look at.
     */
    fun setAside(path: String) {
        val manager = NSFileManager.defaultManager
        val aside = "$path.unreadable"
        manager.removeItemAtPath(aside, null)
        if (!manager.moveItemAtPath(path, aside, null)) manager.removeItemAtPath(path, null)
    }

    fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    private fun utf8(text: String): NSData =
        checkNotNull(NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding))
}
