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
import android.util.Base64
import app.indoorbike.library.HttpFetcher
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Uploads a finished ride to the rider's own **FitTrackee** server.
 *
 * Authentication is OAuth2 authorization-code with PKCE, done in the device
 * browser. **The rider's password never reaches this app.** What the app holds
 * is an access token scoped to `profile:read workouts:read workouts:write`,
 * renewable with a
 * refresh token and revocable from FitTrackee without touching the account.
 * That is the whole reason for the browser round trip: a password typed into a
 * third-party app grants everything forever, and cannot be taken back without
 * changing it.
 *
 *   browser → {instance}/profile/apps/authorize?…&code_challenge=…&state=…
 *   back    → indoorbike://oauth?code=…&state=…
 *   POST      {instance}/api/oauth/token   (code + verifier + client secret)
 *   POST      {instance}/api/workouts      multipart: file=… data={"sport_id":N}
 *
 * FitTrackee will not let a client register itself: creating one needs an
 * already-authenticated request, which is the thing we don't have yet. So the
 * rider creates an app in their FitTrackee profile and enters its id and
 * secret. A per-user secret on a server the same user runs is a very different
 * object from a shipped-in-the-binary one, and it is still strictly less
 * dangerous than their password.
 *
 * The PKCE verifier is persisted rather than held in memory, because the app
 * can be killed while the browser is in front of it and returning to a lost
 * verifier would mean an authorization code that can never be redeemed.
 */
class FitTrackeeUploader(
    context: Context,
    private val historyRepository: HistoryRepository,
    private val userSettings: UserSettings,
) {
    private val appContext = context.applicationContext

    /**
     * Short-lived PKCE state, kept out of [UserSettings] because it is neither
     * a user preference nor cross-platform: it exists only between opening the
     * browser and coming back, and is cleared the moment it is spent.
     */
    private val pending by lazy {
        appContext.getSharedPreferences("fittrackee_oauth", Context.MODE_PRIVATE)
    }

    // ── Step 1: send the rider to their own server's consent page ─────────

    /**
     * The URL to open in a browser, or null when the instance and client
     * haven't been entered yet. Generates and stores a fresh PKCE verifier and
     * CSRF state as a side effect, so calling this twice invalidates the first.
     */
    fun authorizeUrl(): String? {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/').orEmpty()
        val clientId = userSettings.fitTrackeeClientId.value.orEmpty()
        if (base.isEmpty() || clientId.isEmpty()) return null

        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        pending.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()

        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return buildString {
            append(base).append("/profile/apps/authorize")
            append("?response_type=code")
            append("&client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(SCOPES))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
        }
    }

    // ── Step 2: the browser sends us back with a code ─────────────────────

    /**
     * Handle `indoorbike://oauth?code=…&state=…`. Verifies the state matches
     * the request we started, then trades the code for tokens.
     */
    suspend fun completeAuthorization(redirect: Uri): SignIn {
        val error = redirect.getQueryParameter("error")
        if (error != null) return SignIn.Failure("FitTrackee declined: $error")

        val code = redirect.getQueryParameter("code")
            ?: return SignIn.Failure("No authorization code came back.")
        val state = redirect.getQueryParameter("state")
        val expectedState = pending.getString(KEY_STATE, null)
        val verifier = pending.getString(KEY_VERIFIER, null)
            ?: return SignIn.Failure("This sign-in wasn't started here. Try again.")
        // A mismatched state means the redirect didn't come from the request we
        // made, so the code is not ours to spend.
        if (expectedState == null || state != expectedState) {
            clearPending()
            return SignIn.Failure("Sign-in couldn't be verified. Try again.")
        }
        clearPending()

        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/').orEmpty()
        val clientId = userSettings.fitTrackeeClientId.value.orEmpty()
        val secret = userSettings.fitTrackeeClientSecret.value.orEmpty()

        val form = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "client_secret" to secret,
            "code_verifier" to verifier,
        )
        val result = try {
            HttpFetcher.postForm("$base/api/oauth/token", form)
        } catch (t: Throwable) {
            Log.w(TAG, "Token exchange network error", t)
            return SignIn.Failure(t.message ?: "Could not reach that address.")
        }
        if (!result.isSuccess) {
            return SignIn.Failure(errorMessage(result) ?: "Sign-in failed (HTTP ${result.code}).")
        }
        val access = firstMatch(result.body, "access_token")
            ?: return SignIn.Failure("The server didn't return a token.")
        userSettings.setFitTrackeeAccessToken(access)
        userSettings.setFitTrackeeRefreshToken(firstMatch(result.body, "refresh_token"))
        return SignIn.Success
    }

    /** Renew the access token silently. Returns the new token, or null. */
    private suspend fun refreshAccessToken(): String? {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/').orEmpty()
        val refresh = userSettings.fitTrackeeRefreshToken.value ?: return null
        val result = try {
            HttpFetcher.postForm(
                "$base/api/oauth/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to userSettings.fitTrackeeClientId.value.orEmpty(),
                    "client_secret" to userSettings.fitTrackeeClientSecret.value.orEmpty(),
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Token refresh failed", t)
            return null
        }
        if (!result.isSuccess) return null
        val access = firstMatch(result.body, "access_token") ?: return null
        userSettings.setFitTrackeeAccessToken(access)
        firstMatch(result.body, "refresh_token")?.let { userSettings.setFitTrackeeRefreshToken(it) }
        return access
    }

    /** Drop the tokens. The client id and secret survive, so reconnecting is one tap. */
    fun signOut() {
        userSettings.setFitTrackeeAccessToken(null)
        userSettings.setFitTrackeeRefreshToken(null)
        clearPending()
    }

    private fun clearPending() = pending.edit().clear().apply()

    // ── Using the connection ──────────────────────────────────────────────

    /**
     * The sports this instance offers, for the rider to file rides under.
     * Ids differ between instances, so they cannot be hardcoded.
     */
    suspend fun sports(): List<Sport> {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/') ?: return emptyList()
        val token = userSettings.fitTrackeeAccessToken.value ?: return emptyList()
        val body = try {
            HttpFetcher.getString("$base/api/sports", mapOf("Authorization" to "Bearer $token"))
        } catch (t: Throwable) {
            Log.w(TAG, "Sport list fetch failed", t)
            return emptyList()
        }
        // Deliberately not pulling in a JSON parser for two fields, the same
        // call the Intervals uploader makes. Sports look like
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
     * Send the ride to the configured instance.
     *
     * Tries the FIT file first, then falls back to a summary-only workout.
     * FitTrackee is an outdoor tracker: its file import requires coordinates
     * and rejects our rides with "no valid segments with GPS found in fit
     * file", confirmed against a real 1.3.4 instance. So in practice the
     * fallback is always the path taken today.
     *
     * Attempting the file anyway is deliberate rather than wasteful. It is the
     * approach garmin-to-fittrackee settled on for the same problem, and it
     * means the day FitTrackee accepts a GPS-less FIT, riders get the full
     * power trace without a line of this changing. Guessing "this is indoor,
     * skip the file" would instead hard-code today's limitation forever.
     *
     * A 401 is retried once behind a token refresh, so an expired access token
     * is invisible to the rider. [Outcome.Unauthorized] means the refresh
     * failed too, and reconnecting is genuinely needed.
     */
    suspend fun upload(session: WorkoutSession, workout: Workout?, ftp: Int): Outcome {
        val base = userSettings.fitTrackeeUrl.value?.trimEnd('/').orEmpty()
        // An unset sport means the list was unreadable, not that the rider
        // still has to go and choose one. It is never a reason to refuse.
        val sportId = userSettings.fitTrackeeSportId.value.takeIf { it > 0 } ?: DEFAULT_SPORT_ID
        var token = userSettings.fitTrackeeAccessToken.value.orEmpty()
        if (base.isEmpty() || token.isEmpty()) return Outcome.NotConfigured

        val telemetry = historyRepository.getTelemetryForSession(session.id)
        if (telemetry.isEmpty()) return Outcome.Failure("No telemetry recorded for this ride.")

        val fitBytes = try {
            RideFitBuilder.build(session, telemetry, workout, ftp, userSettings.weightKg.value)
        } catch (t: Throwable) {
            Log.e(TAG, "FIT encode failed", t)
            return Outcome.Failure("Couldn't encode ride: ${t.message}")
        }

        val safeName = session.workoutName.ifBlank { "ride" }
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "ride" }
        val filename = "indoorbike-$safeName-${session.id}.fit"
        val data = """{"sport_id":$sportId,"title":${jsonString(session.workoutName.ifBlank { "Indoor ride" })}}"""

        suspend fun post(bearer: String) = HttpFetcher.postMultipartFile(
            url = "$base/api/workouts",
            fileFieldName = "file",
            fileName = filename,
            fileBytes = fitBytes,
            fileMimeType = "application/octet-stream",
            // FitTrackee takes its non-file parameters as a JSON blob in a form
            // field called `data`, not as separate fields.
            extraFormFields = mapOf("data" to data),
            headers = mapOf("Authorization" to "Bearer $bearer", "Accept" to "application/json"),
        )

        var result = try {
            post(token)
        } catch (t: Throwable) {
            Log.e(TAG, "Upload network error", t)
            return Outcome.Failure(t.message ?: "Network error")
        }

        if (result.code == 401) {
            token = refreshAccessToken() ?: return Outcome.Unauthorized
            result = try {
                post(token)
            } catch (t: Throwable) {
                return Outcome.Failure(t.message ?: "Network error")
            }
            if (result.code == 401) return Outcome.Unauthorized
        }

        if (!result.isSuccess) {
            val msg = errorMessage(result) ?: "HTTP ${result.code}"
            Log.i(TAG, "FIT upload rejected (HTTP ${result.code}): $msg - falling back to summary")
            return uploadSummaryOnly(session, telemetry.map { it.power }, sportId, base, token)
        }
        return Outcome.Success(firstMatch(result.body, "id"), withStreams = true)
    }

    /**
     * Record the ride via `/api/workouts/no_gpx`, which takes figures rather
     * than a file and so needs no coordinates.
     *
     * The endpoint carries sport, date, distance, duration and text only:
     * there is nowhere to put power, cadence or heart rate as a series. Rather
     * than drop them, the ride's numbers go into `notes`, so what the app
     * actually measured survives as something a rider can read even though
     * FitTrackee cannot chart it.
     */
    private suspend fun uploadSummaryOnly(
        session: WorkoutSession,
        powerSamples: List<Long>,
        sportId: Int,
        base: String,
        token: String,
    ): Outcome {
        val stats = computeRideStats(
            telemetryWatts = powerSamples,
            durationSeconds = session.durationSeconds,
            ftp = userSettings.ftp.value,
            riderKg = userSettings.weightKg.value,
        )
        // FitTrackee wants local wall-clock to the minute; seconds are rejected
        // outright with "invalid format for workout date".
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(Date(session.startTimeEpoch))

        val notes = buildString {
            append("Indoor ride recorded by Indoor Bike.\n")
            append("Average power ").append(session.avgPower).append(" W")
            if (stats.normalizedPowerWatts > 0) append(", normalised ").append(stats.normalizedPowerWatts).append(" W")
            append('\n')
            if (session.avgCadence > 0) append("Average cadence ").append(session.avgCadence).append(" rpm\n")
            if (session.avgHeartRate > 0) append("Average heart rate ").append(session.avgHeartRate).append(" bpm\n")
            if (stats.tss > 0) append("TSS ").append(stats.tss).append(", IF ").append(stats.intensityFactor).append('\n')
            if (stats.kilojoules > 0) append(stats.kilojoules).append(" kJ\n")
            append("Distance and speed are estimated from power; the trainer measures neither.")
        }

        val body = buildString {
            append('{')
            append("\"sport_id\":").append(sportId)
            append(",\"workout_date\":").append(jsonString(date))
            // Kilometres, and seconds, per the endpoint's units.
            append(",\"distance\":").append(stats.distanceMeters / 1000.0)
            append(",\"duration\":").append(session.durationSeconds)
            append(",\"title\":").append(jsonString(session.workoutName.ifBlank { "Indoor ride" }))
            append(",\"notes\":").append(jsonString(notes))
            append('}')
        }

        val result = try {
            HttpFetcher.postJson(
                "$base/api/workouts/no_gpx", body,
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Summary upload network error", t)
            return Outcome.Failure(t.message ?: "Network error")
        }
        if (result.code == 401) return Outcome.Unauthorized
        if (!result.isSuccess) {
            val msg = errorMessage(result) ?: "HTTP ${result.code}"
            Log.w(TAG, "Summary upload rejected (HTTP ${result.code}): $msg")
            return Outcome.Failure("FitTrackee: $msg")
        }
        return Outcome.Success(firstMatch(result.body, "id"), withStreams = false)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    private fun base64Url(raw: ByteArray): String =
        Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /** FitTrackee reports problems as {"message": "...", "status": "error"}. */
    private fun errorMessage(result: HttpFetcher.PostResult): String? =
        (firstMatch(result.body, "error_description")
            ?: firstMatch(result.body, "message")
            ?: firstMatch(result.body, "error"))?.take(160)

    private fun firstMatch(body: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"?([^\",}]+)\"?").find(body)?.groupValues?.getOrNull(1)?.trim()

    /** Minimal JSON string escaping; a workout title can contain anything. */
    private fun jsonString(raw: String): String {
        val escaped = buildString {
            for (c in raw) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        return "\"$escaped\""
    }

    data class Sport(val id: Int, val label: String)

    sealed class SignIn {
        data object Success : SignIn()
        data class Failure(val message: String) : SignIn()
    }

    sealed class Outcome {
        /**
         * [withStreams] is false when the ride went up as a summary because
         * FitTrackee refused the file. The rider is told, since "uploaded" and
         * "uploaded with your power trace" are different promises.
         */
        data class Success(val workoutId: String?, val withStreams: Boolean) : Outcome()
        data object NotConfigured : Outcome()
        data object Unauthorized : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    companion object {
        private const val TAG = "FitTrackeeUploader"

        /** Must match the intent-filter in the manifest, and what the rider registers. */
        const val REDIRECT_URI = "indoorbike://oauth"

        /**
         * Enough to upload a ride and know who we are uploading as.
         *
         * Deliberately *not* asking for `workouts:read`. GET /api/sports is
         * gated behind it, but FitTrackee's own app-creation screen does not
         * reliably offer that scope, so asking for it left riders unable to
         * connect at all for the sake of a list the app can do without.
         * See [DEFAULT_SPORT_ID].
         */
        const val SCOPES = "profile:read workouts:write"

        /**
         * FitTrackee's "Cycling (Virtual)", which is what an indoor trainer
         * ride actually is. Sport ids come from FitTrackee's own migrations
         * rather than being chosen per-instance, and this one is documented in
         * their reference table, so it is the same number on every server.
         *
         * Used whenever the sport list can't be read, which is the normal case
         * given [SCOPES]. A rider whose server does share the list can still
         * pick something else.
         */
        const val DEFAULT_SPORT_ID = 13

        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "state"
    }
}
