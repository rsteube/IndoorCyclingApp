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
        val tmpName = "$name.tmp"

        // Strategy: write full contents to a sibling .tmp, then delete the
        // existing target and rename the .tmp on top. The previous
        // delete-then-create pattern left a "no slice exists" window the
        // duration of an HTTPS upload (seconds for cloud-backed providers);
        // this shrinks it to the duration of a SAF rename (microseconds).
        //
        // Crash recovery — at every failure point either the original slice
        // is still present (because we haven't deleted it yet) OR the new
        // .tmp is present (next sync re-creates from current local state
        // and clears the orphan).

        // Clean up any orphaned .tmp from a previous interrupted write so
        // createFile doesn't get a " (1)" suffix from providers that reject
        // duplicate names.
        tree.findFile(tmpName)?.delete()

        // 1. Write to .tmp. If this throws, the existing slice is untouched.
        val tmpDoc = tree.createFile(MIME_JSON, tmpName)
            ?: throw IllegalStateException("Couldn't create $tmpName in sync folder")
        try {
            context.contentResolver.openOutputStream(tmpDoc.uri, "w")?.use { stream ->
                stream.write(json.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            } ?: throw IllegalStateException("Couldn't open $tmpName for writing")
        } catch (t: Throwable) {
            runCatching { tmpDoc.delete() }
            throw t
        }

        // 2. Promote .tmp → final. First preserve the existing slice as a
        //    one-deep `.prev` backup so an unexpected or empty overwrite is
        //    always recoverable (readAllSlices skips `.prev` since it doesn't
        //    end in `.json`). If the provider rejects the rename-to-prev, fall
        //    back to deleting the old slice (prior behaviour) so a write still
        //    completes.
        val prevName = "$name$PREV_SUFFIX"
        val existing = tree.findFile(name)
        if (existing != null) {
            tree.findFile(prevName)?.delete()
            if (!existing.renameTo(prevName)) {
                Log.w(TAG, "Couldn't keep .prev backup for $name; replacing in place")
                existing.delete()
            }
        }
        if (tmpDoc.renameTo(name)) return@withContext

        // Rename rejected by this provider. Fall back to copy-into-fresh-doc.
        Log.w(TAG, "SAF rename rejected; falling back to copy for $name")
        val finalDoc = tree.createFile(MIME_JSON, name)
            ?: throw IllegalStateException("Couldn't create $name (rename fallback)")
        try {
            context.contentResolver.openInputStream(tmpDoc.uri)?.use { src ->
                context.contentResolver.openOutputStream(finalDoc.uri, "w")?.use { dst ->
                    src.copyTo(dst)
                    dst.flush()
                }
            } ?: throw IllegalStateException("Couldn't copy $tmpName to $name")
        } finally {
            runCatching { tmpDoc.delete() }
        }
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
        private const val PREV_SUFFIX = ".prev"
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
