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

import app.indoorbike.shared.settings.DEFAULT_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.DEFAULT_FTP
import app.indoorbike.shared.settings.DEFAULT_MAX_HR
import app.indoorbike.shared.settings.DEFAULT_WEIGHT_KG
import app.indoorbike.shared.settings.MAX_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_MAX_HR
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_MAX_HR
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.simulation.Drivetrain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Where the app keeps secrets: the Keychain, implemented in Swift. */
interface SecretStore {
    fun get(key: String): String?
    /** Null removes it. */
    fun set(key: String, value: String?)
}

/**
 * [UserSettings] in [NSUserDefaults], under the key names the Android app uses
 * in DataStore, and with the same limits: a value outside a setting's range is
 * ignored rather than clamped, as on Android.
 *
 * NSUserDefaults is synchronous and cached in memory, so each setter writes
 * straight through; there is no background scope to dispose of.
 *
 * **Secrets go to [secrets]**, which the app backs with the Keychain: the
 * Intervals.icu API key and token, and the FitTrackee client secret and
 * tokens, the same five the Android app seals with its keystore. They are
 * never written to the plain-text defaults.
 */
@OptIn(ExperimentalUuidApi::class)
class IosUserSettings(
    private val secrets: SecretStore,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : UserSettings {

    private fun int(key: String, default: Int): MutableStateFlow<Int> =
        MutableStateFlow(if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default)

    private fun float(key: String, default: Float): MutableStateFlow<Float> =
        MutableStateFlow(if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else default)

    private fun bool(key: String, default: Boolean = false): MutableStateFlow<Boolean> =
        MutableStateFlow(if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default)

    private fun string(key: String): MutableStateFlow<String?> = MutableStateFlow(defaults.stringForKey(key))

    private fun putInt(key: String, flow: MutableStateFlow<Int>, value: Int) {
        flow.value = value
        defaults.setInteger(value.toLong(), key)
    }

    private fun putFloat(key: String, flow: MutableStateFlow<Float>, value: Float) {
        flow.value = value
        defaults.setFloat(value, key)
    }

    private fun putBool(key: String, flow: MutableStateFlow<Boolean>, value: Boolean) {
        flow.value = value
        defaults.setBool(value, key)
    }

    /** Trimmed, and removed when blank, as every optional string on Android is. */
    private fun putString(key: String, flow: MutableStateFlow<String?>, raw: String?) {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() }
        flow.value = cleaned
        if (cleaned == null) defaults.removeObjectForKey(key) else defaults.setObject(cleaned, key)
    }

    private fun secret(key: String): MutableStateFlow<String?> = MutableStateFlow(secrets.get(key))

    /** Trimmed and removed when blank, into the Keychain; see the class comment. */
    private fun putSecret(key: String, flow: MutableStateFlow<String?>, raw: String?) {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() }
        flow.value = cleaned
        secrets.set(key, cleaned)
    }

    private val _ftp = int(KEY_FTP, DEFAULT_FTP)
    override val ftp: StateFlow<Int> = _ftp.asStateFlow()
    override fun setFtp(newFtp: Int) {
        if (newFtp in MIN_FTP..MAX_FTP) putInt(KEY_FTP, _ftp, newFtp)
    }

    private val _weightKg = float(KEY_WEIGHT, DEFAULT_WEIGHT_KG)
    override val weightKg: StateFlow<Float> = _weightKg.asStateFlow()
    override fun setWeightKg(newWeightKg: Float) {
        if (newWeightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG) putFloat(KEY_WEIGHT, _weightKg, newWeightKg)
    }

    private val _maxHr = int(KEY_MAX_HR, DEFAULT_MAX_HR)
    override val maxHr: StateFlow<Int> = _maxHr.asStateFlow()
    override fun setMaxHr(newMaxHr: Int) {
        if (newMaxHr in MIN_MAX_HR..MAX_MAX_HR) putInt(KEY_MAX_HR, _maxHr, newMaxHr)
    }

    private val _bikeWeightKg = float(KEY_BIKE_WEIGHT, DEFAULT_BIKE_WEIGHT_KG)
    override val bikeWeightKg: StateFlow<Float> = _bikeWeightKg.asStateFlow()
    override fun setBikeWeightKg(value: Float) {
        if (value in MIN_BIKE_WEIGHT_KG..MAX_BIKE_WEIGHT_KG) putFloat(KEY_BIKE_WEIGHT, _bikeWeightKg, value)
    }

    private val _drivetrainId = MutableStateFlow(defaults.stringForKey(KEY_DRIVETRAIN) ?: Drivetrain.DEFAULT.id)
    override val drivetrainId: StateFlow<String> = _drivetrainId.asStateFlow()
    override fun setDrivetrainId(id: String) {
        if (Drivetrain.ALL.none { it.id == id }) return
        _drivetrainId.value = id
        defaults.setObject(id, KEY_DRIVETRAIN)
    }

    private val _pairedTrainerId = string(KEY_TRAINER)
    override val pairedTrainerId: StateFlow<String?> = _pairedTrainerId.asStateFlow()
    override fun setPairedTrainerId(id: String?) = putString(KEY_TRAINER, _pairedTrainerId, id)

    private val _pairedHrmId = string(KEY_HRM)
    override val pairedHrmId: StateFlow<String?> = _pairedHrmId.asStateFlow()
    override fun setPairedHrmId(id: String?) = putString(KEY_HRM, _pairedHrmId, id)

    private val _pairedControllerId = string(KEY_CONTROLLER)
    override val pairedControllerId: StateFlow<String?> = _pairedControllerId.asStateFlow()
    override fun setPairedControllerId(id: String?) = putString(KEY_CONTROLLER, _pairedControllerId, id)

    private val _hasCompletedOnboarding = bool(KEY_ONBOARDED)
    override val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()
    override fun setHasCompletedOnboarding(value: Boolean) = putBool(KEY_ONBOARDED, _hasCompletedOnboarding, value)

    private val _hasDismissedStarterCard = bool(KEY_DISMISSED_STARTER)
    override val hasDismissedStarterCard: StateFlow<Boolean> = _hasDismissedStarterCard.asStateFlow()
    override fun setHasDismissedStarterCard(value: Boolean) =
        putBool(KEY_DISMISSED_STARTER, _hasDismissedStarterCard, value)

    private val _intervalsAthleteId = string(KEY_INTERVALS_ATHLETE)
    override val intervalsAthleteId: StateFlow<String?> = _intervalsAthleteId.asStateFlow()
    override fun setIntervalsAthleteId(id: String?) = putString(KEY_INTERVALS_ATHLETE, _intervalsAthleteId, id)

    private val _intervalsApiKey = secret(KEY_INTERVALS_API_KEY)
    override val intervalsApiKey: StateFlow<String?> = _intervalsApiKey.asStateFlow()
    override fun setIntervalsApiKey(key: String?) = putSecret(KEY_INTERVALS_API_KEY, _intervalsApiKey, key)

    private val _intervalsAutoUpload = bool(KEY_INTERVALS_AUTO)
    override val intervalsAutoUpload: StateFlow<Boolean> = _intervalsAutoUpload.asStateFlow()
    override fun setIntervalsAutoUpload(value: Boolean) = putBool(KEY_INTERVALS_AUTO, _intervalsAutoUpload, value)

    private val _intervalsAccessToken = secret(KEY_INTERVALS_ACCESS)
    override val intervalsAccessToken: StateFlow<String?> = _intervalsAccessToken.asStateFlow()
    override fun setIntervalsAccessToken(token: String?) = putSecret(KEY_INTERVALS_ACCESS, _intervalsAccessToken, token)

    private val _intervalsAthleteName = string(KEY_INTERVALS_ATHLETE_NAME)
    override val intervalsAthleteName: StateFlow<String?> = _intervalsAthleteName.asStateFlow()
    override fun setIntervalsAthleteName(name: String?) =
        putString(KEY_INTERVALS_ATHLETE_NAME, _intervalsAthleteName, name)

    private val _companionShowAllData = bool(KEY_COMPANION_ALL_DATA)
    override val companionShowAllData: StateFlow<Boolean> = _companionShowAllData.asStateFlow()
    override fun setCompanionShowAllData(value: Boolean) =
        putBool(KEY_COMPANION_ALL_DATA, _companionShowAllData, value)

    private val _fitTrackeeUrl = string(KEY_FT_URL)
    override val fitTrackeeUrl: StateFlow<String?> = _fitTrackeeUrl.asStateFlow()
    override fun setFitTrackeeUrl(url: String?) = putString(KEY_FT_URL, _fitTrackeeUrl, url?.trim()?.trimEnd('/'))

    private val _fitTrackeeClientId = string(KEY_FT_CLIENT_ID)
    override val fitTrackeeClientId: StateFlow<String?> = _fitTrackeeClientId.asStateFlow()
    override fun setFitTrackeeClientId(id: String?) = putString(KEY_FT_CLIENT_ID, _fitTrackeeClientId, id)

    private val _fitTrackeeClientSecret = secret(KEY_FT_CLIENT_SECRET)
    override val fitTrackeeClientSecret: StateFlow<String?> = _fitTrackeeClientSecret.asStateFlow()
    override fun setFitTrackeeClientSecret(secret: String?) = putSecret(KEY_FT_CLIENT_SECRET, _fitTrackeeClientSecret, secret)

    private val _fitTrackeeAccessToken = secret(KEY_FT_ACCESS)
    override val fitTrackeeAccessToken: StateFlow<String?> = _fitTrackeeAccessToken.asStateFlow()
    override fun setFitTrackeeAccessToken(token: String?) = putSecret(KEY_FT_ACCESS, _fitTrackeeAccessToken, token)

    private val _fitTrackeeRefreshToken = secret(KEY_FT_REFRESH)
    override val fitTrackeeRefreshToken: StateFlow<String?> = _fitTrackeeRefreshToken.asStateFlow()
    override fun setFitTrackeeRefreshToken(token: String?) = putSecret(KEY_FT_REFRESH, _fitTrackeeRefreshToken, token)

    private val _fitTrackeeSportId = int(KEY_FT_SPORT, DEFAULT_FITTRACKEE_SPORT_ID)
    override val fitTrackeeSportId: StateFlow<Int> = _fitTrackeeSportId.asStateFlow()
    override fun setFitTrackeeSportId(id: Int) = putInt(KEY_FT_SPORT, _fitTrackeeSportId, id)

    private val _fitTrackeeAutoUpload = bool(KEY_FT_AUTO)
    override val fitTrackeeAutoUpload: StateFlow<Boolean> = _fitTrackeeAutoUpload.asStateFlow()
    override fun setFitTrackeeAutoUpload(value: Boolean) = putBool(KEY_FT_AUTO, _fitTrackeeAutoUpload, value)

    private val _favouriteWorkoutIds = MutableStateFlow(
        defaults.stringArrayForKey(KEY_FAVOURITES)?.filterIsInstance<String>()?.toSet() ?: emptySet()
    )
    override val favouriteWorkoutIds: StateFlow<Set<String>> = _favouriteWorkoutIds.asStateFlow()
    override fun setFavouriteWorkoutIds(ids: Set<String>) {
        if (ids == _favouriteWorkoutIds.value) return
        _favouriteWorkoutIds.value = ids
        defaults.setObject(ids.toList(), KEY_FAVOURITES)
    }
    override fun setWorkoutFavourite(workoutId: String, favourite: Boolean) {
        val current = _favouriteWorkoutIds.value
        if (favourite == workoutId in current) return
        setFavouriteWorkoutIds(if (favourite) current + workoutId else current - workoutId)
    }

    /** Generated once per install and kept; it namespaces this device's rides for sync. */
    override val deviceId: String = defaults.stringForKey(KEY_DEVICE_ID) ?: Uuid.random().toString().also {
        defaults.setObject(it, KEY_DEVICE_ID)
    }

    private val _deviceLabel = MutableStateFlow(defaults.stringForKey(KEY_DEVICE_LABEL) ?: defaultDeviceLabel())
    override val deviceLabel: StateFlow<String> = _deviceLabel.asStateFlow()
    override fun setDeviceLabel(label: String) {
        val cleaned = label.trim().take(MAX_DEVICE_LABEL_LEN).ifEmpty { return }
        if (cleaned == _deviceLabel.value) return
        _deviceLabel.value = cleaned
        defaults.setObject(cleaned, KEY_DEVICE_LABEL)
    }

    private val _syncFolderUri = string(KEY_SYNC_FOLDER_URI)
    override val syncFolderUri: StateFlow<String?> = _syncFolderUri.asStateFlow()
    override fun setSyncFolderUri(uri: String?) = putString(KEY_SYNC_FOLDER_URI, _syncFolderUri, uri)

    private val _syncPaused = bool(KEY_SYNC_PAUSED)
    override val syncPaused: StateFlow<Boolean> = _syncPaused.asStateFlow()
    override fun setSyncPaused(paused: Boolean) = putBool(KEY_SYNC_PAUSED, _syncPaused, paused)

    private companion object {
        // The Android app's DataStore keys, so a backup's settings read the same.
        const val KEY_FTP = "user_ftp"
        const val KEY_WEIGHT = "user_weight_kg"
        const val KEY_BIKE_WEIGHT = "bike_weight_kg"
        const val KEY_DRIVETRAIN = "simulation_drivetrain_id"
        const val KEY_MAX_HR = "user_max_hr"
        const val KEY_TRAINER = "paired_trainer_id"
        const val KEY_HRM = "paired_hrm_id"
        const val KEY_CONTROLLER = "paired_controller_id"
        const val KEY_ONBOARDED = "has_completed_onboarding"
        const val KEY_DISMISSED_STARTER = "has_dismissed_starter_card"
        const val KEY_INTERVALS_ATHLETE = "intervals_athlete_id"
        const val KEY_INTERVALS_AUTO = "intervals_auto_upload"
        const val KEY_COMPANION_ALL_DATA = "companion_show_all_data"
        const val KEY_INTERVALS_ATHLETE_NAME = "intervals_athlete_name"
        const val KEY_FT_URL = "fittrackee_url"
        const val KEY_FT_CLIENT_ID = "fittrackee_client_id"
        const val KEY_FT_SPORT = "fittrackee_sport_id"

        // The secrets' names in the Keychain: Android's DataStore keys.
        const val KEY_INTERVALS_API_KEY = "intervals_api_key"
        const val KEY_INTERVALS_ACCESS = "intervals_access_token"
        const val KEY_FT_CLIENT_SECRET = "fittrackee_client_secret"
        const val KEY_FT_ACCESS = "fittrackee_access_token"
        const val KEY_FT_REFRESH = "fittrackee_refresh_token"
        const val KEY_FT_AUTO = "fittrackee_auto_upload"
        const val KEY_FAVOURITES = "favourite_workout_ids"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_LABEL = "device_label"
        const val KEY_SYNC_FOLDER_URI = "sync_folder_uri"
        const val KEY_SYNC_PAUSED = "sync_paused"

        /** FitTrackee's "Indoor cycling"; the Android uploader's DEFAULT_SPORT_ID. */
        const val DEFAULT_FITTRACKEE_SPORT_ID = 13

        /** Capped so it can't bloat a backup file; the Android limit. */
        const val MAX_DEVICE_LABEL_LEN = 64

        fun defaultDeviceLabel(): String =
            UIDevice.currentDevice.name.trim().takeIf { it.isNotEmpty() } ?: UIDevice.currentDevice.model
    }
}
