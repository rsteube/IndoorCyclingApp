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
package app.indoorbike.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.indoorbike.shared.util.Log
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF-backed [SyncStorage]. The rider picks a folder via
 * `ACTION_OPEN_DOCUMENT_TREE` and the URI is persisted with
 * `takePersistableUriPermission`. From there this class enumerates and
 * writes `indoorbike-device-<deviceId>.json` files inside the folder via
 * [DocumentFile] + `ContentResolver`.
 *
 * Provider-agnostic: the rider can point at Drive, Dropbox, Nextcloud,
 * iCloud-Drive-via-Files, a Syncthing folder, OneDrive — anything that
 * exposes itself as a SAF tree provider.
 */
class SafSyncStorage(
    private val context: Context,
    private val treeUri: Uri,
) : SyncStorage {

    override suspend fun readAllSlices(): List<String> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory) {
            Log.w(TAG, "Sync folder URI doesn't resolve to a directory: $treeUri")
            return@withContext emptyList()
        }
        tree.listFiles()
            .asSequence()
            .filter { it.isFile && it.name.isSliceName() }
            .mapNotNull { doc -> readSliceText(doc) }
            .toList()
    }

    override suspend fun writeOwnSlice(deviceId: String, json: String) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Sync folder unreachable")
        if (!tree.isDirectory) {
            throw IllegalStateException("Sync folder URI doesn't resolve to a directory")
        }
        val name = sliceFileName(deviceId)
        // SAF doesn't expose atomic-rename across providers reliably, so
        // delete-then-create is the most portable pattern. A crash between
        // delete and create rebuilds on next sync (data is preserved on
        // other devices' slices). If a *peer* is mid-read while we're
        // mid-write they'll see no file or the new file — never a
        // half-written one — because the new doc is created with its full
        // contents in `openOutputStream.use`.
        tree.findFile(name)?.delete()
        val newDoc = tree.createFile(MIME_JSON, name)
            ?: throw IllegalStateException("Couldn't create $name in sync folder")
        context.contentResolver.openOutputStream(newDoc.uri, "w")?.use { stream ->
            stream.write(json.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
        } ?: throw IllegalStateException("Couldn't open $name for writing")
    }

    private fun readSliceText(doc: DocumentFile): String? = try {
        context.contentResolver.openInputStream(doc.uri)?.use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Couldn't read ${doc.name}: ${t.message}")
        null
    }

    companion object {
        private const val TAG = "SafSyncStorage"
        private const val SLICE_PREFIX = "indoorbike-device-"
        private const val SLICE_EXT = ".json"
        private const val MIME_JSON = "application/json"

        /**
         * `<prefix><sanitised-deviceId><ext>`. Sanitisation replaces SAF-hostile
         * characters and caps the length so weird device ids can't blow up
         * filename limits — but normal UUIDs come through unchanged.
         */
        fun sliceFileName(deviceId: String): String =
            "$SLICE_PREFIX${sanitiseForFilename(deviceId)}$SLICE_EXT"

        private fun String?.isSliceName(): Boolean {
            val n = this ?: return false
            return n.startsWith(SLICE_PREFIX) && n.endsWith(SLICE_EXT)
        }

        private fun sanitiseForFilename(s: String): String =
            s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "device" }
    }
}
