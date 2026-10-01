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

import kotlinx.coroutines.CancellationException
import android.content.Context
import android.net.Uri
import android.util.Base64
import app.indoorbike.BuildConfig
import app.indoorbike.library.HttpFetcher
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.ride.RideBrandStore
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.fit.VirtualWorld
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.sync.IntervalsIcuOAuth
import app.indoorbike.shared.sync.IntervalsIcuUpload
import app.indoorbike.shared.util.Log
import java.security.SecureRandom

/**
 * Uploads a completed ride to Intervals.icu as a FIT activity, and signs the
 * rider in to do it.
 *
 * Two ways in. **Signed in** (the normal one, in a build that carries the OAuth
 * client): the rider approves Indoor Bike in their browser and the app holds an
 * access token scoped to uploading activities.
 *
 *   POST https://intervals.icu/api/v1/athlete/0/activities
 *   Authorization: Bearer <access token>
 *
 * **API key** (a build from source without `intervals.properties`, or a rider
 * who set one up before signing in existed): the athlete id and personal key
 * from Intervals.icu's settings page.
 *
 *   POST https://intervals.icu/api/v1/athlete/{id}/activities
 *   Authorization: Basic base64("API_KEY:<api_key>")
 *
 * Both send multipart/form-data with a `file` part holding the .fit bytes. The
 * token wins when both are present. See [IntervalsIcuOAuth] for why the client
 * secret is compiled in rather than kept in the repository.
 *
 * Strict opt-in posture: the only place that actually fires this is when the
 * user taps Upload (or after a ride completes when auto-upload is on). The
 * uploader itself never reads settings except to compose the request.
 *
 * **Posture.** Intervals.icu is a non-free SaaS, and this integration is the
 * app's only path to one. What matters is that it exists rather than that it
 * runs: with default settings the app makes
 * zero contact with Intervals.icu. The athlete id and API key are blank on
 * a fresh install; the upload code path is unreachable until the rider
 * fills both in, and even then only fires on a manual Upload tap or when
 * auto-upload is explicitly toggled on. The API key is also excluded from
 * Android Auto Backup (see `res/xml/data_extraction_rules.xml`) so it
 * doesn't leak via that channel either.
 */
class IntervalsIcuUploader(
    context: Context,
    private val historyRepository: HistoryRepository,
    private val userSettings: UserSettings,
    /** Which game drove a ride, so the upload can say where it was ridden. */
    private val rideBrands: RideBrandStore,
) {
    private val appContext = context.applicationContext

    /**
     * The CSRF state for a sign-in in progress. Persisted rather than held in
     * memory because the app can be killed while the browser is in front of it,
     * and cleared the moment it is spent.
     */
    private val pending by lazy {
        appContext.getSharedPreferences("intervals_oauth", Context.MODE_PRIVATE)
    }

    /** Whether this build can sign a rider in, rather than asking for their API key. */
    val signInAvailable: Boolean
        // Never in a debug build. The code comes back through a verified app
        // link, and indoorbike.app vouches for the release key alone: the debug
        // key is published with the source, so nothing may trust it. A debug
        // build offering sign-in would send the rider to a consent page whose
        // answer can never reach it.
        get() = !BuildConfig.DEBUG &&
            BuildConfig.INTERVALS_CLIENT_ID.isNotBlank() && BuildConfig.INTERVALS_CLIENT_SECRET.isNotBlank()

    /**
     * The consent page to open in a browser, or null in a build without the
     * OAuth client. A fresh state each time, so opening it twice invalidates the
     * first.
     */
    fun authorizeUrl(): String? {
        if (!signInAvailable) return null
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val state = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        pending.edit().putString(KEY_STATE, state).apply()
        return IntervalsIcuOAuth.authorizeUrl(BuildConfig.INTERVALS_CLIENT_ID, state)
    }

    /**
     * Handle `https://indoorbike.app/oauth/intervals?code=...&state=...`: check
     * the state, trade the code for a token, and keep it.
     *
     * Signing in replaces an API key the rider had set up: the token does the
     * same job with less reach, and a key left behind on the phone is a key that
     * can still do everything.
     */
    suspend fun completeAuthorization(redirect: Uri): SignIn {
        val expected = pending.getString(KEY_STATE, null)
        val parameters = redirect.queryParameterNames.associateWith { redirect.getQueryParameter(it) }
        val code = when (val callback = IntervalsIcuOAuth.readCallback(parameters, expected)) {
            is IntervalsIcuOAuth.Callback.Refused -> {
                // A refusal that is not about the state still spends it: the
                // browser round trip is over either way.
                if (expected != null && parameters["state"] == expected) clearPending()
                return SignIn.Failure(callback.message)
            }
            is IntervalsIcuOAuth.Callback.Code -> callback.code
        }
        clearPending()

        val result = try {
            HttpFetcher.postForm(
                IntervalsIcuOAuth.TOKEN_URL,
                mapOf(
                    "client_id" to BuildConfig.INTERVALS_CLIENT_ID,
                    "client_secret" to BuildConfig.INTERVALS_CLIENT_SECRET,
                    "code" to code,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Token exchange network error", t)
            return SignIn.Failure(t.message ?: "Could not reach Intervals.icu.")
        }
        if (!result.isSuccess) {
            val reason = IntervalsIcuOAuth.errorMessage(result.body) ?: "HTTP ${result.code}"
            Log.w(TAG, "Token exchange refused (HTTP ${result.code}): $reason")
            return SignIn.Failure("Intervals.icu sign-in failed: $reason")
        }
        val connection = IntervalsIcuOAuth.parseToken(result.body)
            ?: return SignIn.Failure("Intervals.icu didn't return a token.")

        userSettings.setIntervalsAccessToken(connection.accessToken)
        userSettings.setIntervalsAthleteName(connection.athleteName)
        connection.athleteId?.let { userSettings.setIntervalsAthleteId(it) }
        userSettings.setIntervalsApiKey(null)
        Log.i(TAG, "Signed in to Intervals.icu with scope ${connection.scope}")
        return SignIn.Success(connection.athleteName)
    }

    /**
     * Revoke the token on Intervals.icu and forget it here.
     *
     * Forgotten here whatever the server says: a rider who taps Disconnect with
     * no network still means it, and a token the app no longer holds can do
     * nothing from this phone. Returns false when the server could not be told,
     * so the rider can be pointed at their Intervals.icu settings.
     */
    suspend fun disconnect(): Boolean {
        val token = userSettings.intervalsAccessToken.value
        userSettings.setIntervalsAccessToken(null)
        userSettings.setIntervalsAthleteName(null)
        userSettings.setIntervalsAutoUpload(false)
        clearPending()
        if (token.isNullOrBlank()) return true
        return try {
            val result = HttpFetcher.delete(
                IntervalsIcuOAuth.DISCONNECT_URL,
                headers = mapOf("Authorization" to "Bearer $token"),
            )
            // Already revoked on the website is as good as revoked now.
            result.isSuccess || result.code == 401
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Disconnect could not reach Intervals.icu", t)
            false
        }
    }

    private fun clearPending() = pending.edit().clear().apply()

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
        val token = userSettings.intervalsAccessToken.value?.trim().orEmpty()
        val athleteId = userSettings.intervalsAthleteId.value?.trim().orEmpty()
        val apiKey = userSettings.intervalsApiKey.value?.trim().orEmpty()
        if (token.isEmpty() && (athleteId.isEmpty() || apiKey.isEmpty())) return Outcome.NotConfigured

        val telemetry = historyRepository.getTelemetryForSession(session.id)
        if (telemetry.isEmpty()) {
            return Outcome.Failure("No telemetry recorded for this ride.")
        }

        val world = VirtualWorld.of(session, rideBrands.find(session.startTimeEpoch)?.name)
        val fitBytes = try {
            RideFitBuilder.build(
                session, telemetry, workout, ftp, userSettings.weightKg.value,
                appVersion = BuildConfig.VERSION_NAME,
                world = world,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "FIT encode failed", t)
            return Outcome.Failure("Couldn't encode ride: ${t.message}")
        }

        val base: String
        val auth: String
        if (token.isNotEmpty()) {
            base = IntervalsIcuOAuth.ACTIVITIES_URL
            auth = "Bearer $token"
        } else {
            // Intervals.icu accepts the athlete id with or without the leading
            // "i". Normalise so we never double it up or drop it.
            val normAthlete = athleteId.removePrefix("i").removePrefix("I").let { "i$it" }
            base = "https://intervals.icu/api/v1/athlete/$normAthlete/activities"
            val basic = "API_KEY:$apiKey".toByteArray(Charsets.UTF_8)
            auth = "Basic " + Base64.encodeToString(basic, Base64.NO_WRAP)
        }

        val safeName = (session.workoutName.ifBlank { "ride" })
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "ride" }
        val filename = "indoorbike-$safeName-${session.id}.fit"
        // The device, the ride's name and, for a ride a game drove, where it was
        // ridden; and the ride's own id, so the activity can always be traced
        // back to this ride. See [IntervalsIcuUpload].
        val url = base + "?" + IntervalsIcuUpload.parameters(session.workoutName, session.externalId, world)
            .joinToString("&") { (key, value) ->
                // URLEncoder writes a space as '+', which a query string reads
                // as a space too, but %20 is unambiguous everywhere.
                key + "=" + java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }

        val result = try {
            HttpFetcher.postMultipartFile(
                url = url,
                fileFieldName = "file",
                fileName = filename,
                fileBytes = fitBytes,
                fileMimeType = "application/octet-stream",
                headers = mapOf("Authorization" to auth, "Accept" to "application/json"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Upload network error", t)
            return Outcome.Failure(t.message ?: "Network error")
        }

        // A token revoked on the website, or replaced by signing in elsewhere.
        // Only a token can be fixed by signing in again; a rejected API key is
        // a wrong key, and says so below like any other failure.
        if (token.isNotEmpty() && (result.code == 401 || result.code == 403)) {
            return Outcome.Unauthorized
        }
        if (!result.isSuccess) {
            // Intervals returns plain text on error (auth failures = 401, etc.)
            val msg = result.body.take(160).ifBlank { "HTTP ${result.code}" }
            Log.w(TAG, "Upload rejected (HTTP ${result.code}): $msg")
            return Outcome.Failure("Intervals.icu: $msg")
        }

        // Success body is JSON like { "id": 123456789, ... }. We don't pull
        // in a JSON parser just for this: the id is nice to have but not
        // load-bearing. Cheap regex sniff:
        val activityId = Regex("\"id\"\\s*:\\s*\"?([\\w-]+)\"?")
            .find(result.body)?.groupValues?.getOrNull(1)
        return Outcome.Success(activityId)
    }

    sealed class SignIn {
        data class Success(val athleteName: String?) : SignIn()
        data class Failure(val message: String) : SignIn()
    }

    sealed class Outcome {
        data class Success(val activityId: String?) : Outcome()
        data object NotConfigured : Outcome()

        /** The token was refused. Signing in again is what fixes it. */
        data object Unauthorized : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    companion object {
        private const val TAG = "IntervalsIcuUploader"
        private const val KEY_STATE = "state"
    }
}
