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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.net.queryParameters
import app.indoorbike.shared.sync.FitTrackeeUploader
import app.indoorbike.shared.sync.IntervalsIcuOAuth
import app.indoorbike.shared.sync.IntervalsIcuUploader
import app.indoorbike.shared.sync.OwnSlice
import app.indoorbike.shared.sync.SyncStatus
import app.indoorbike.shared.sync.SyncStorage
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The backup folder, a plain folder on the phone the rider picked: the same
 * files and names as Android's `SafSyncStorage` and iOS's `IosSyncStorage`,
 * `indoorbike-device-<id>.json` with the copy it replaced beside it as
 * `.prev`, so all three can share one folder that something else (Nextcloud,
 * Syncthing) keeps in step between devices.
 */
internal class LinuxSyncStorage(private val folder: String) : SyncStorage {

    private fun path(name: String) = "${folder.trimEnd('/')}/$name"

    override suspend fun readAllSlices(): List<String> = withContext(Dispatchers.IO) {
        PosixFiles.list(folder)
            .filter { it.startsWith(PREFIX) && it.endsWith(".json") }
            .mapNotNull { name -> runCatching { PosixFiles.readText(path(name)) }.getOrNull() }
    }

    override suspend fun readOwnSlice(deviceId: String): OwnSlice = withContext(Dispatchers.IO) {
        // A folder that is not there (a memory card taken out, a folder
        // renamed) is not a first sync: that would bring back rides this
        // device deleted when the folder returns.
        if (!PosixFiles.exists(folder)) return@withContext OwnSlice.Unreadable("the sync folder isn't there")
        val name = sliceFileName(deviceId)
        val present = listOf(name, "$name.prev").map(::path).filter(PosixFiles::exists)
        if (present.isEmpty()) return@withContext OwnSlice.Missing
        val texts = present.mapNotNull { runCatching { PosixFiles.readText(it) }.getOrNull() }
        if (texts.isEmpty()) OwnSlice.Unreadable("$name couldn't be read") else OwnSlice.Found(texts)
    }

    override suspend fun writeOwnSlice(deviceId: String, json: String): Unit = withContext(Dispatchers.IO) {
        if (!PosixFiles.exists(folder)) throw IllegalStateException("The sync folder isn't there")
        val target = path(sliceFileName(deviceId))
        // Keep the copy being replaced, so a slice written half way still has
        // a whole one behind it.
        PosixFiles.readBytes(target)?.let { previous ->
            runCatching { PosixFiles.writeAtomically("$target.prev", previous) }
                .onFailure { Log.w(TAG, "Couldn't keep the previous slice", it) }
        }
        PosixFiles.writeTextAtomically(target, json)
    }

    companion object {
        private const val TAG = "LinuxSyncStorage"
        private const val PREFIX = "indoorbike-device-"

        /** Android's name for a device's slice, so every platform shares one folder. */
        fun sliceFileName(deviceId: String): String =
            PREFIX + deviceId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "device" } + ".json"
    }
}

/**
 * The backup folder and the two upload services, as topics and commands:
 *
 *  - `sync`: the last round's status, the folder and whether syncing is paused.
 *  - `connections`: what is set up for Intervals.icu and FitTrackee.
 *  - `uploads`: the uploads under way, as "rideId:SERVICE"; and the event
 *    `uploads.recorded` when one is recorded on a ride, so a page showing it
 *    can read it again.
 */
internal fun SailfishCore.installSync() {
    val s = userSettings

    bus.publish("sync", combine(syncEngine.status, s.syncFolderUri, s.syncPaused) { status, folder, paused ->
        Triple(status, folder, paused)
    }) { (status, folder, paused) ->
        buildJsonObject {
            put("status", encodeSyncStatus(status))
            put("folder", folder)
            put("paused", paused)
        }
    }

    bus.command("sync.now") {
        scope.launch { syncEngine.syncNow() }
        JsonNull
    }
    bus.command("sync.restore") {
        scope.launch { syncEngine.resolveRestoreFromBackup() }
        JsonNull
    }
    bus.command("sync.keepLocal") {
        scope.launch { syncEngine.resolveKeepLocal() }
        JsonNull
    }
    /** {"cmd": "sync.setFolder", "path": "/home/..."}: sync there from now on; no path stops syncing. */
    bus.command("sync.setFolder") { args ->
        val path = args.string("path")?.trim()?.removePrefix("file://")?.ifEmpty { null }
        s.setSyncFolderUri(path)
        if (path != null) scope.launch { syncEngine.syncNow() }
        JsonNull
    }

    val connectionFlows: List<Flow<Any?>> = listOf(
        s.intervalsAthleteId, s.intervalsApiKey, s.intervalsAccessToken, s.intervalsAthleteName, s.intervalsAutoUpload,
        s.fitTrackeeUrl, s.fitTrackeeClientId, s.fitTrackeeClientSecret, s.fitTrackeeAccessToken,
        s.fitTrackeeSportId, s.fitTrackeeAutoUpload,
    )
    bus.publish("connections", combine(connectionFlows) { it.toList() }) { v ->
        val athleteId = v[0] as String?
        val apiKey = v[1] as String?
        val token = v[2] as String?
        buildJsonObject {
            put("intervals", buildJsonObject {
                put("athleteId", athleteId.orEmpty())
                put("apiKey", apiKey.orEmpty())
                put("signedIn", !token.isNullOrEmpty())
                put("athleteName", (v[3] as String?).orEmpty())
                put("configured", !token.isNullOrEmpty() || (!athleteId.isNullOrBlank() && !apiKey.isNullOrBlank()))
                // A build carrying the OAuth client signs in; one without it takes the API key.
                put("signInAvailable", intervalsIcuUploader.signInAvailable)
                put("autoUpload", v[4] as Boolean)
            })
            put("fitTrackee", buildJsonObject {
                put("url", (v[5] as String?).orEmpty())
                put("clientId", (v[6] as String?).orEmpty())
                put("clientSecret", (v[7] as String?).orEmpty())
                put("connected", !(v[8] as String?).isNullOrEmpty())
                put("sportId", v[9] as Int)
                put("autoUpload", v[10] as Boolean)
                put("redirectUri", FitTrackeeUploader.REDIRECT_URI)
            })
        }
    }

    /** {"cmd": "connections.set", "key": "intervalsApiKey", "value": "..."}: one connection setting. */
    bus.command("connections.set") { args ->
        fun text() = args.string("value")?.trim()?.ifEmpty { null }
        fun flag() = args.bool("value") ?: false
        when (val key = args.requireString("key")) {
            "intervalsAthleteId" -> s.setIntervalsAthleteId(text())
            "intervalsApiKey" -> s.setIntervalsApiKey(text())
            "intervalsAutoUpload" -> s.setIntervalsAutoUpload(flag())
            "fitTrackeeUrl" -> s.setFitTrackeeUrl(text()?.trimEnd('/'))
            "fitTrackeeClientId" -> s.setFitTrackeeClientId(text())
            "fitTrackeeClientSecret" -> s.setFitTrackeeClientSecret(text())
            "fitTrackeeSportId" -> s.setFitTrackeeSportId(args.requireInt("value"))
            "fitTrackeeAutoUpload" -> s.setFitTrackeeAutoUpload(flag())
            else -> throw UserFacing("No setting $key")
        }
        JsonNull
    }

    /** The rider's server's consent page, to open in the browser. */
    bus.command("fitTrackee.authorizeUrl") {
        JsonPrimitive(fitTrackeeUploader.authorizeUrl() ?: throw UserFacing("Enter your server address and client ID first"))
    }
    suspend fun completeSignIn(url: String): String? {
        if (!url.startsWith(FitTrackeeUploader.REDIRECT_URI)) {
            return "That isn't the address FitTrackee sends back. It starts with ${FitTrackeeUploader.REDIRECT_URI}"
        }
        return when (val result = fitTrackeeUploader.completeAuthorization(queryParameters(url))) {
            is FitTrackeeUploader.SignIn.Success -> null
            is FitTrackeeUploader.SignIn.Failure -> result.message
        }
    }
    /** {"cmd": "fitTrackee.complete", "url": "indoorbike://oauth?code=..."}: finish a sign-in pasted by hand. */
    bus.request("fitTrackee.complete") { args ->
        completeSignIn(args.requireString("url").trim())?.let { throw UserFacing(it) }
        JsonNull
    }
    /**
     * The same, for the redirect the browser handed to the app: the answer is
     * the event `fitTrackee.signedIn`, {ok, message}, for whichever page is open.
     */
    bus.command("fitTrackee.redirect") { args ->
        val url = args.requireString("url").trim()
        scope.launch {
            val failure = completeSignIn(url)
            bus.event("fitTrackee.signedIn", buildJsonObject {
                put("ok", failure == null)
                put("message", failure)
            })
        }
        JsonNull
    }
    bus.request("fitTrackee.sports") {
        buildJsonArray {
            fitTrackeeUploader.sports().forEach { sport ->
                add(buildJsonObject {
                    put("id", sport.id)
                    put("label", sport.label)
                })
            }
        }
    }
    bus.command("fitTrackee.signOut") {
        fitTrackeeUploader.signOut()
        s.setFitTrackeeAutoUpload(false)
        JsonNull
    }
    bus.request("intervals.disconnect") {
        JsonPrimitive(intervalsIcuUploader.disconnect())
    }

    /** Intervals.icu's consent page, to open in the browser. */
    bus.command("intervals.authorizeUrl") {
        JsonPrimitive(intervalsIcuUploader.authorizeUrl() ?: throw UserFacing("This build cannot sign in to Intervals.icu"))
    }
    suspend fun completeIntervals(url: String): String? {
        // The page on indoorbike.app hands the code on as indoorbike://oauth/intervals; a rider
        // pasting the address may paste either.
        if (!url.startsWith(INTERVALS_APP_REDIRECT) && !url.startsWith(IntervalsIcuOAuth.REDIRECT_URI)) {
            return "That isn't the address Intervals.icu sends back. It starts with ${IntervalsIcuOAuth.REDIRECT_URI}"
        }
        return when (val result = intervalsIcuUploader.completeAuthorization(queryParameters(url))) {
            is IntervalsIcuUploader.SignIn.Success -> null
            is IntervalsIcuUploader.SignIn.Failure -> result.message
        }
    }
    /** {"cmd": "intervals.complete", "url": "..."}: finish a sign-in pasted by hand. */
    bus.request("intervals.complete") { args ->
        completeIntervals(args.requireString("url").trim())?.let { throw UserFacing(it) }
        JsonNull
    }
    /** The same, for the link the browser handed to the app: the answer is the event `intervals.signedIn`. */
    bus.command("intervals.redirect") { args ->
        val url = args.requireString("url").trim()
        scope.launch {
            val failure = completeIntervals(url)
            bus.event("intervals.signedIn", buildJsonObject {
                put("ok", failure == null)
                put("message", failure)
            })
        }
        JsonNull
    }

    bus.publish("uploads", rideUploads.inFlight) { running ->
        buildJsonArray { running.forEach { (id, service) -> add(JsonPrimitive("$id:${service.name}")) } }
    }
    rideUploads.recorded
        .onEach { id -> bus.event("uploads.recorded", buildJsonObject { put("id", id.toString()) }) }
        .launchIn(scope)

    /** {"cmd": "upload.intervals", "id": "12"}: send a saved ride; the reply says how it went, in words. */
    bus.request("upload.intervals") { args ->
        val session = historyRepository.getSessionById(rideId(args)) ?: throw UserFacing("That ride is gone.")
        val ftp = session.ftp.toInt().takeIf { it > 0 } ?: s.ftp.value
        when (val outcome = rideUploads.uploadIntervals(session, workoutLibrary.find(session.workoutId), ftp)) {
            is IntervalsIcuUploader.Outcome.Success -> JsonPrimitive("Uploaded to Intervals.icu")
            IntervalsIcuUploader.Outcome.NotConfigured ->
                throw UserFacing("Connect Intervals.icu in Settings, Connections first")
            IntervalsIcuUploader.Outcome.Unauthorized ->
                throw UserFacing("Intervals.icu turned the athlete ID and API key down. Check them in Settings, Connections.")
            is IntervalsIcuUploader.Outcome.Failure -> throw UserFacing(outcome.message)
        }
    }
    bus.request("upload.fitTrackee") { args ->
        val session = historyRepository.getSessionById(rideId(args)) ?: throw UserFacing("That ride is gone.")
        val ftp = session.ftp.toInt().takeIf { it > 0 } ?: s.ftp.value
        when (val outcome = rideUploads.uploadFitTrackee(session, workoutLibrary.find(session.workoutId), ftp)) {
            is FitTrackeeUploader.Outcome.Success -> JsonPrimitive(
                if (outcome.withStreams) "Uploaded to FitTrackee"
                else "Uploaded to FitTrackee as a summary (it can't take indoor files)"
            )
            FitTrackeeUploader.Outcome.NotConfigured ->
                throw UserFacing("Connect FitTrackee in Settings, Connections first")
            FitTrackeeUploader.Outcome.Unauthorized ->
                throw UserFacing("The FitTrackee sign-in has expired. Sign in again in Settings, Connections.")
            is FitTrackeeUploader.Outcome.Failure -> throw UserFacing(outcome.message)
        }
    }
}

private fun rideId(args: JsonObject): Long =
    args.string("id")?.toLongOrNull() ?: args.long("id") ?: throw UserFacing("Missing id")

private fun encodeSyncStatus(status: SyncStatus): JsonObject = buildJsonObject {
    when (status) {
        SyncStatus.Idle -> put("kind", "idle")
        SyncStatus.Syncing -> put("kind", "syncing")
        SyncStatus.NotConfigured -> put("kind", "notConfigured")
        is SyncStatus.Success -> {
            put("kind", "success")
            put("syncedAt", status.syncedAtEpoch)
            put("devicesSeen", status.devicesSeen)
            put("slicesSkipped", status.slicesSkipped)
        }
        is SyncStatus.Failed -> {
            put("kind", "failed")
            put("message", status.message)
        }
        is SyncStatus.NeedsReview -> {
            put("kind", "needsReview")
            put("localSessions", status.localSessions)
            put("backupSessions", status.backupSessions)
            put("localImports", status.localImports)
            put("backupImports", status.backupImports)
        }
    }
}

/** Where indoorbike.app's sign-in page hands an Intervals.icu code to the Sailfish app. */
internal const val INTERVALS_APP_REDIRECT = "indoorbike://oauth/intervals"
