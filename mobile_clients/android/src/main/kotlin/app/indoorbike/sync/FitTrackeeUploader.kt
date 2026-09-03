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

import app.indoorbike.library.HttpFetcher
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log

/**
 * Uploads a finished ride to the rider's own **FitTrackee** server.
 *
 *   POST {instance}/api/auth/login        {"email":…,"password":…}  → auth_token
 *   GET  {instance}/api/sports            Authorization: Bearer …   → sport list
 *   POST {instance}/api/workouts          multipart: file=…  data={"sport_id":N}
 *
 * The interesting difference from [IntervalsIcuUploader] is that there is no
 * service to talk to until the rider names one. FitTrackee is self-hosted, so
 * the instance URL is a setting, sport ids are per-instance rather than global,
 * and the whole integration is inert until someone points it at a server they
 * run. That suits this app better than a hosted service does: the ride goes to
 * a machine the rider controls.
 *
 * **Credentials.** Sign-in trades an email and password for a token, and only
 * the token is stored. The password is used for exactly one request and then
 * dropped; it is never written to DataStore, never logged, and never leaves the
 * device except in that one call to the rider's own server. FitTrackee tokens
 * expire server-side, so a 401 on upload means "sign in again" rather than
 * "something is broken", and [Outcome.Unauthorized] exists to let the UI say so.
 *
 * The FIT file is the same one the exporter and the Intervals uploader build,
 * so a ride arrives with power, cadence, heart rate and the virtual distance,
 * and its laps carry the interval targets.
 */
class FitTrackeeUploader(
    private val historyRepository: HistoryRepository,
    private val userSettings: UserSettings,
) {

    /**
     * Exchange an email and password for a token. The password is not retained
     * by this class, its caller, or DataStore.
     */
    suspend fun signIn(instanceUrl: String, email: String, password: String): SignIn {
        val base = instanceUrl.trim().trimEnd('/')
        if (base.isEmpty()) return SignIn.Failure("Enter your FitTrackee address first.")
        if (!base.startsWith("https://")) {
            // Deliberately strict rather than helpful: silently upgrading a
            // typed http:// would send a password in the clear.
            return SignIn.Failure("The address must start with https://")
        }

        val payload = """{"email":${jsonString(email)},"password":${jsonString(password)}}"""
        val result = try {
            HttpFetcher.postJson("$base/api/auth/login", payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Sign-in network error", t)
            return SignIn.Failure(t.message ?: "Could not reach that address.")
        }

        if (result.code == 401) return SignIn.Failure("Wrong email or password.")
        if (!result.isSuccess) {
            return SignIn.Failure(errorMessage(result) ?: "Sign-in failed (HTTP ${result.code}).")
        }
        val token = firstMatch(result.body, "auth_token")
            ?: return SignIn.Failure("The server didn't return a token.")
        return SignIn.Success(token)
    }

    /**
     * The sports this instance offers, for the rider to file rides under.
     * Ids differ between instances, so they cannot be hardcoded.
     */
    suspend fun sports(): List<Sport> {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/') ?: return emptyList()
        val token = userSettings.fitTrackeeToken.value ?: return emptyList()
        val body = try {
            HttpFetcher.getString("$base/api/sports", headers = mapOf("Authorization" to "Bearer $token"))
        } catch (t: Throwable) {
            Log.w(TAG, "Sport list fetch failed", t)
            return emptyList()
        }
        // Deliberately not pulling in a JSON parser for two fields, the same
        // call the Intervals uploader makes. Sport objects look like
        // {"id": 1, "label": "Cycling (Sport)", ...}
        return Regex("\\{[^{}]*?\"id\"\\s*:\\s*(\\d+)[^{}]*?\"label\"\\s*:\\s*\"([^\"]*)\"[^{}]*?\\}")
            .findAll(body)
            .mapNotNull { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                Sport(id = id, label = m.groupValues[2])
            }
            .distinctBy { it.id }
            .toList()
    }

    /**
     * Build the ride's FIT bytes and POST them to the configured instance.
     *
     * [Outcome.NotConfigured] when there is no server, token or sport yet;
     * [Outcome.Unauthorized] when the token has expired.
     */
    suspend fun upload(session: WorkoutSession, workout: Workout?, ftp: Int): Outcome {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/').orEmpty()
        val token = userSettings.fitTrackeeToken.value.orEmpty()
        val sportId = userSettings.fitTrackeeSportId.value
        if (base.isEmpty() || token.isEmpty() || sportId <= 0) return Outcome.NotConfigured

        val telemetry = historyRepository.getTelemetryForSession(session.id)
        if (telemetry.isEmpty()) return Outcome.Failure("No telemetry recorded for this ride.")

        val fitBytes = try {
            RideFitBuilder.build(session, telemetry, workout, ftp, userSettings.weightKg.value)
        } catch (t: Throwable) {
            Log.e(TAG, "FIT encode failed", t)
            return Outcome.Failure("Couldn't encode ride: ${t.message}")
        }

        val safeName = session.workoutName.ifBlank { "ride" }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "ride" }

        val result = try {
            HttpFetcher.postMultipartFile(
                url = "$base/api/workouts",
                fileFieldName = "file",
                fileName = "indoorbike-$safeName-${session.id}.fit",
                fileBytes = fitBytes,
                fileMimeType = "application/octet-stream",
                // FitTrackee takes its non-file parameters as a JSON blob in a
                // form field called `data`, not as separate fields.
                extraFormFields = mapOf(
                    "data" to """{"sport_id":$sportId,"title":${jsonString(session.workoutName.ifBlank { "Indoor ride" })}}"""
                ),
                headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Upload network error", t)
            return Outcome.Failure(t.message ?: "Network error")
        }

        if (result.code == 401) return Outcome.Unauthorized
        if (!result.isSuccess) {
            val msg = errorMessage(result) ?: "HTTP ${result.code}"
            Log.w(TAG, "Upload rejected (HTTP ${result.code}): $msg")
            return Outcome.Failure("FitTrackee: $msg")
        }
        return Outcome.Success(firstMatch(result.body, "id"))
    }

    /** FitTrackee reports problems as {"message": "...", "status": "error"}. */
    private fun errorMessage(result: HttpFetcher.PostResult): String? =
        firstMatch(result.body, "message")?.take(160)

    private fun firstMatch(body: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(body)?.groupValues?.getOrNull(1)?.trim()

    /** Minimal JSON string escaping. Passwords and titles can contain anything. */
    private fun jsonString(raw: String): String {
        val escaped = buildString {
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    data class Sport(val id: Int, val label: String)

    sealed class SignIn {
        data class Success(val token: String) : SignIn()
        data class Failure(val message: String) : SignIn()
    }

    sealed class Outcome {
        data class Success(val workoutId: String?) : Outcome()
        data object NotConfigured : Outcome()
        data object Unauthorized : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    companion object {
        private const val TAG = "FitTrackeeUploader"
    }
}
