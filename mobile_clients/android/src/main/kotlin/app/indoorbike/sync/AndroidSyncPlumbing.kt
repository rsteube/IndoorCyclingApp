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
package app.indoorbike.sync

import android.content.Context
import android.net.Uri
import app.indoorbike.library.HttpFetcher
import app.indoorbike.library.WorkoutImporter
import app.indoorbike.library.WorkoutLibrary
import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.ImportMeta
import app.indoorbike.shared.net.HttpClient
import app.indoorbike.shared.net.PostResult
import app.indoorbike.shared.sync.FitTrackeeUploader
import app.indoorbike.shared.sync.IntervalsIcuUploader
import app.indoorbike.shared.sync.SyncWorkoutLibrary
import app.indoorbike.shared.util.KeyValueStore

// The Android side of the shared uploaders and sync engine: HTTPS through
// HttpFetcher, a sign-in in progress in SharedPreferences, the library through
// the importer, and redirects as Android Uris.

/** [HttpFetcher], under its https-only, no-redirect rules, as the shared code asks for HTTPS. */
object AndroidHttpClient : HttpClient {
    override suspend fun getText(url: String, headers: Map<String, String>): String =
        HttpFetcher.getString(url, headers)

    override suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>): PostResult =
        HttpFetcher.postForm(url, fields, headers).shared()

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): PostResult =
        HttpFetcher.postJson(url, json, headers).shared()

    override suspend fun postMultipartFile(
        url: String,
        fileFieldName: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String,
        extraFormFields: Map<String, String>,
        headers: Map<String, String>,
    ): PostResult = HttpFetcher.postMultipartFile(
        url, fileFieldName, fileName, fileBytes, fileMimeType, extraFormFields, headers,
    ).shared()

    override suspend fun delete(url: String, headers: Map<String, String>): PostResult =
        HttpFetcher.delete(url, headers).shared()

    private fun HttpFetcher.PostResult.shared() = PostResult(code, body)
}

/** A SharedPreferences file, for a sign-in in progress. */
class SharedPreferencesStore(context: Context, name: String) : KeyValueStore {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE) }
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun clear() = prefs.edit().clear().apply()
}

/** The importer and library, as a sync round uses them. */
class AndroidSyncLibrary(
    private val importer: WorkoutImporter,
    private val library: WorkoutLibrary,
) : SyncWorkoutLibrary {
    override fun loadUserWorkouts(): List<Workout> = importer.loadUserWorkouts()
    override fun loadImportMeta(workoutId: String): ImportMeta? = importer.loadImportMeta(workoutId)
    override suspend fun saveWorkout(workout: Workout, sourceRepoId: String?, sourceRepoName: String?, addedEpoch: Long) =
        importer.saveWorkout(workout, sourceRepoId, sourceRepoName, addedEpoch)
    override suspend fun deleteUserWorkout(workoutId: String): Boolean = importer.deleteUserWorkout(workoutId)
    override suspend fun refresh() = library.refreshImportsAwait()
}

private fun Uri.parameters(): Map<String, String?> = queryParameterNames.associateWith { getQueryParameter(it) }

/** Handle the browser's redirect back into the app. */
suspend fun IntervalsIcuUploader.completeAuthorization(redirect: Uri) = completeAuthorization(redirect.parameters())

/** Handle the browser's redirect back into the app. */
suspend fun FitTrackeeUploader.completeAuthorization(redirect: Uri) = completeAuthorization(redirect.parameters())
