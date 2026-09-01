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

import android.util.Base64
import app.indoorbike.library.HttpFetcher
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log

/**
 * Uploads a completed ride to Intervals.icu as a FIT activity.
 *
 *   POST https://intervals.icu/api/v1/athlete/{id}/activities
 *   Authorization: Basic base64("API_KEY:<api_key>")
 *   multipart/form-data with `file` part containing the .fit bytes
 *
 * The athlete id and API key come from UserSettings — both must be set for
 * upload to be attempted. The username for HTTP-Basic is the literal string
 * "API_KEY", per Intervals.icu's documented scheme; the API key the user
 * pastes into Settings is the password.
 *
 * Strict opt-in posture: the only place that actually fires this is when the
 * user taps Upload (or after a ride completes when auto-upload is on). The
 * uploader itself never reads settings except to compose the request.
 *
 * **F-Droid posture (NonFreeNet anti-feature).** Intervals.icu is a non-free
 * SaaS, so the F-Droid metadata declares this integration under the
 * `NonFreeNet` anti-feature. The flag is *for the integration's existence*,
 * not for its day-to-day behaviour: with default settings the app makes
 * zero contact with Intervals.icu. The athlete id and API key are blank on
 * a fresh install; the upload code path is unreachable until the rider
 * fills both in, and even then only fires on a manual Upload tap or when
 * auto-upload is explicitly toggled on. The API key is also excluded from
 * Android Auto Backup (see `res/xml/data_extraction_rules.xml`) so it
 * doesn't leak via that channel either.
 */
class IntervalsIcuUploader(
    private val historyRepository: HistoryRepository,
    private val userSettings: UserSettings,
) {
    /**
     * Builds the FIT bytes from the ride and POSTs them.
     *
     * Returns [Outcome.Success] with the parsed activity id when Intervals
     * accepts the file, [Outcome.NotConfigured] when credentials are missing,
     * or [Outcome.Failure] with a user-readable message for everything else.
     */
    suspend fun upload(
        session: WorkoutSession,
        workout: Workout?,
        ftp: Int,
    ): Outcome {
        val athleteId = userSettings.intervalsAthleteId.value?.trim().orEmpty()
        val apiKey = userSettings.intervalsApiKey.value?.trim().orEmpty()
        if (athleteId.isEmpty() || apiKey.isEmpty()) return Outcome.NotConfigured

        val telemetry = historyRepository.getTelemetryForSession(session.id)
        if (telemetry.isEmpty()) {
            return Outcome.Failure("No telemetry recorded for this ride.")
        }

        val fitBytes = try {
            RideFitBuilder.build(session, telemetry, workout, ftp, userSettings.weightKg.value)
        } catch (t: Throwable) {
            Log.e(TAG, "FIT encode failed", t)
            return Outcome.Failure("Couldn't encode ride: ${t.message}")
        }

        // Intervals.icu accepts the athlete id with or without the leading "i".
        // Normalise so we never double it up or drop it.
        val normAthlete = athleteId.removePrefix("i").removePrefix("I").let { "i$it" }
        val url = "https://intervals.icu/api/v1/athlete/$normAthlete/activities"
        val basic = "API_KEY:$apiKey".toByteArray(Charsets.UTF_8)
        val auth = "Basic " + Base64.encodeToString(basic, Base64.NO_WRAP)

        val safeName = (session.workoutName.ifBlank { "ride" })
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "ride" }
        val filename = "indoorbike-$safeName-${session.id}.fit"

        val result = try {
            HttpFetcher.postMultipartFile(
                url = url,
                fileFieldName = "file",
                fileName = filename,
                fileBytes = fitBytes,
                fileMimeType = "application/octet-stream",
                headers = mapOf("Authorization" to auth, "Accept" to "application/json"),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Upload network error", t)
            return Outcome.Failure(t.message ?: "Network error")
        }

        if (!result.isSuccess) {
            // Intervals returns plain text on error (auth failures = 401, etc.)
            val msg = result.body.take(160).ifBlank { "HTTP ${result.code}" }
            Log.w(TAG, "Upload rejected (HTTP ${result.code}): $msg")
            return Outcome.Failure("Intervals.icu: $msg")
        }

        // Success body is JSON like { "id": 123456789, ... }. We don't pull
        // in a JSON parser just for this — the id is nice to have but not
        // load-bearing. Cheap regex sniff:
        val activityId = Regex("\"id\"\\s*:\\s*\"?([\\w-]+)\"?")
            .find(result.body)?.groupValues?.getOrNull(1)
        return Outcome.Success(activityId)
    }

    sealed class Outcome {
        data class Success(val activityId: String?) : Outcome()
        data object NotConfigured : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    companion object {
        private const val TAG = "IntervalsIcuUploader"
    }
}
